import hashlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from deploy_pterodactyl import DeploymentError, NoRedirects, Panel, stage, plugin_name


def jar_bytes(name="RCPlatform", version="old"):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("plugin.yml", f"name: {name}\nmain: example.Plugin\nversion: {version}\n")
    return buffer.getvalue()


INSTALLED = jar_bytes()
PENDING = jar_bytes(version="pending")


class FakePanel:
    def __init__(self):
        self.storage = {"/plugins/RCPlatform.jar": INSTALLED}
        self.mutations = []
        self.corrupt_upload = False

    def files(self, directory):
        return {path.rsplit("/", 1)[1]: {"is_file": True}
                for path in self.storage if path.rsplit("/", 1)[0] == directory}

    def ensure_directory(self, parent, name):
        pass

    def download(self, path):
        return self.storage[path]

    def checksum(self, path):
        return hashlib.sha256(self.storage[path]).hexdigest()

    def upload(self, directory, name, contents):
        self.mutations.append(("upload", name))
        self.storage[directory + "/" + name] = b"broken" if self.corrupt_upload else contents

    def api(self, method, path, payload):
        if method == "POST" and path == "/files/delete":
            self.mutations.append(("delete", payload))
            for name in payload["files"]:
                self.storage.pop(payload["root"] + "/" + name)
            return
        if method != "PUT" or path != "/files/rename":
            raise AssertionError("Only file rename is permitted during promotion")
        self.mutations.append(("rename", payload))
        for item in payload["files"]:
            if payload["root"] + "/" + item["to"] in self.storage:
                raise DeploymentError("Cannot move or rename file, destination already exists.")
            self.storage[payload["root"] + "/" + item["to"]] = self.storage.pop(
                payload["root"] + "/" + item["from"])


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.jar = Path(self.directory.name) / "RCPlatform.jar"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("plugin.yml", "name: RCPlatform\nmain: example.Plugin\nversion: 1\n")
            archive.writestr("example/Plugin.class", b"compiled plugin")
        self.panel = FakePanel()

    def test_upload_verifies_then_promotes_without_changing_installed_plugin(self):
        self.panel.storage["/plugins/update/RCPlatform.jar"] = PENDING
        self.panel.storage["/plugins/RCPlatform/config.yml"] = b"live config"
        stage(self.panel, self.jar)
        self.assertEqual(self.panel.storage["/plugins/update/RCPlatform.jar"], self.jar.read_bytes())
        self.assertEqual(self.panel.storage["/plugins/RCPlatform.jar"], INSTALLED)
        self.assertEqual(self.panel.storage["/plugins/RCPlatform/config.yml"], b"live config")
        self.assertTrue(self.panel.mutations[0][1].endswith(".uploading"))
        self.assertEqual([action[0] for action in self.panel.mutations], ["upload", "rename", "rename", "delete"])
        self.assertFalse(any(path.endswith(".previous") for path in self.panel.storage))

    def test_corrupt_upload_preserves_previous_pending_and_installed_jar(self):
        self.panel.corrupt_upload = True
        self.panel.storage["/plugins/update/RCPlatform.jar"] = PENDING
        with self.assertRaisesRegex(DeploymentError, "checksum mismatch"):
            stage(self.panel, self.jar)
        self.assertEqual(self.panel.storage["/plugins/update/RCPlatform.jar"], PENDING)
        self.assertEqual(self.panel.storage["/plugins/RCPlatform.jar"], INSTALLED)
        self.assertFalse(any(action[0] == "rename" for action in self.panel.mutations))

    def test_identical_pending_build_is_not_uploaded_again(self):
        self.panel.storage["/plugins/update/RCPlatform.jar"] = self.jar.read_bytes()
        stage(self.panel, self.jar)
        self.assertEqual(self.panel.mutations, [])

    def test_versioned_installed_filename_receives_new_version(self):
        self.panel.storage = {"/plugins/RCPlatform-3.0.jar": INSTALLED}
        newer = self.jar.with_name("RCPlatform-3.1.jar")
        newer.write_bytes(self.jar.read_bytes())
        stage(self.panel, newer)
        self.assertEqual(self.panel.storage["/plugins/update/RCPlatform-3.0.jar"], newer.read_bytes())
        self.assertNotIn("/plugins/update/RCPlatform-3.1.jar", self.panel.storage)
        self.assertEqual(self.panel.storage["/plugins/RCPlatform-3.0.jar"], INSTALLED)

    def test_renamed_jar_is_matched_by_identity(self):
        self.panel.storage = {"/plugins/custom-name.jar": INSTALLED}
        stage(self.panel, self.jar)
        self.assertEqual(self.panel.storage["/plugins/update/custom-name.jar"], self.jar.read_bytes())

    def test_missing_or_duplicate_installed_identity_blocks_deployment(self):
        for storage in ({}, {"/plugins/a.jar": INSTALLED, "/plugins/b.jar": INSTALLED}):
            self.panel.storage = storage
            with self.assertRaisesRegex(DeploymentError, "exactly one installed"):
                stage(self.panel, self.jar)
            self.assertEqual(self.panel.mutations, [])

    def test_matching_filename_with_wrong_identity_is_not_replaced(self):
        self.panel.storage = {"/plugins/RCPlatform.jar": jar_bytes("DifferentPlugin")}
        with self.assertRaisesRegex(DeploymentError, "exactly one installed"):
            stage(self.panel, self.jar)
        self.assertEqual(self.panel.mutations, [])

    def test_duplicate_versioned_pending_update_is_rejected(self):
        self.panel.storage["/plugins/update/RCPlatform-3.0.jar"] = PENDING
        with self.assertRaisesRegex(DeploymentError, "Conflicting pending updates"):
            stage(self.panel, self.jar)
        self.assertEqual(self.panel.mutations, [])

    def test_pending_filename_for_different_plugin_is_rejected(self):
        self.panel.storage["/plugins/update/RCPlatform.jar"] = jar_bytes("DifferentPlugin")
        with self.assertRaisesRegex(DeploymentError, "different plugin"):
            stage(self.panel, self.jar)
        self.assertEqual(self.panel.mutations, [])

    def test_library_jar_cannot_be_deployed(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("example/Api.class", b"api")
        with self.assertRaisesRegex(DeploymentError, "not a deployable Paper plugin"):
            stage(self.panel, self.jar)
        self.assertEqual(self.panel.mutations, [])

    def test_failed_rename_preserves_pending_jar(self):
        self.panel.storage["/plugins/update/RCPlatform.jar"] = PENDING
        self.panel.api = Mock(side_effect=DeploymentError("Rename failed"))
        with self.assertRaisesRegex(DeploymentError, "Rename failed"):
            stage(self.panel, self.jar)
        self.assertEqual(self.panel.storage["/plugins/update/RCPlatform.jar"], PENDING)

    def test_failed_promotion_restores_previous_pending_jar(self):
        self.panel.storage["/plugins/update/RCPlatform.jar"] = PENDING
        api = self.panel.api
        def fail_promotion(method, path, payload):
            if path == "/files/rename" and payload["files"][0]["from"].endswith(".uploading"):
                raise DeploymentError("Promotion request failed")
            return api(method, path, payload)
        self.panel.api = fail_promotion
        with self.assertRaisesRegex(DeploymentError, "previous pending JAR was restored"):
            stage(self.panel, self.jar)
        self.assertEqual(self.panel.storage["/plugins/update/RCPlatform.jar"], PENDING)
        self.assertEqual(self.panel.storage["/plugins/RCPlatform.jar"], INSTALLED)

    def test_ambiguous_promotion_success_is_verified_before_cleanup(self):
        self.panel.storage["/plugins/update/RCPlatform.jar"] = PENDING
        api = self.panel.api
        def lose_promotion_response(method, path, payload):
            result = api(method, path, payload)
            if path == "/files/rename" and payload["files"][0]["from"].endswith(".uploading"):
                raise DeploymentError("Connection lost after rename")
            return result
        self.panel.api = lose_promotion_response
        stage(self.panel, self.jar)
        self.assertEqual(self.panel.storage["/plugins/update/RCPlatform.jar"], self.jar.read_bytes())

    def test_cleanup_failure_does_not_fail_a_verified_upload(self):
        self.panel.storage["/plugins/update/RCPlatform.jar"] = PENDING
        api = self.panel.api
        def fail_cleanup(method, path, payload):
            if path == "/files/delete":
                raise DeploymentError("Cleanup permission denied")
            return api(method, path, payload)
        self.panel.api = fail_cleanup
        stage(self.panel, self.jar)
        self.assertEqual(self.panel.storage["/plugins/update/RCPlatform.jar"], self.jar.read_bytes())
        self.assertTrue(any(path.endswith(".previous") for path in self.panel.storage))

    def test_first_upload_without_pending_file(self):
        stage(self.panel, self.jar)
        self.assertEqual(self.panel.storage["/plugins/update/RCPlatform.jar"], self.jar.read_bytes())
        self.assertEqual([item[0] for item in self.panel.mutations], ["upload", "rename"])

    def test_symlink_destination_is_rejected(self):
        files = self.panel.files
        def with_symlink(directory):
            return {"RCPlatform.jar": {"is_file": True, "is_symlink": True}} if directory == "/plugins/update" else files(directory)
        self.panel.files = with_symlink
        with self.assertRaisesRegex(DeploymentError, "normal plugin file"):
            stage(self.panel, self.jar)
        self.assertEqual(self.panel.mutations, [])


class ApiTests(unittest.TestCase):
    def test_large_permission_descriptor_is_supported(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("plugin.yml", "name: LargePlugin\npermissions:\n" + "".join(f"  permission.{i}: {{}}\n" for i in range(5000)))
        self.assertEqual(plugin_name(buffer.getvalue()), "LargePlugin")

    def test_unreasonably_large_descriptor_is_rejected(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("plugin.yml", "name: LargePlugin\n#" + "x" * (1024 * 1024))
        with self.assertRaisesRegex(DeploymentError, "too large"):
            plugin_name(buffer.getvalue())

    def setUp(self):
        self.panel = Panel("https://panel.example", "6c2b7716", "test-key")

    def test_upload_directory_is_sent_to_wings_without_panel_key(self):
        self.panel.api = Mock(return_value={"attributes": {"url": "https://node.example/upload/file?token=signed"}})
        self.panel.request = Mock(return_value=b"")
        self.panel.upload("/plugins/update", ".RCPlatform.uploading", b"jar bytes")
        method, url, body, headers = self.panel.request.call_args.args
        self.assertEqual(method, "POST")
        self.assertEqual(parse_qs(urlsplit(url).query), {"token": ["signed"], "directory": ["/plugins/update"]})
        self.assertNotIn("Authorization", headers)
        self.assertIn(b'name="files"; filename=".RCPlatform.uploading"', body)
        self.assertIn(b"jar bytes", body)

    def test_download_uses_signed_url_without_panel_key(self):
        self.panel.api = Mock(return_value={"attributes": {"url": "https://node.example/download/file?token=signed"}})
        self.panel.request = Mock(return_value=b"jar bytes")
        self.assertEqual(self.panel.checksum("/plugins/update/a.jar"), hashlib.sha256(b"jar bytes").hexdigest())
        self.assertEqual(self.panel.request.call_args.args, ("GET", "https://node.example/download/file?token=signed"))

    def test_missing_secret_fails_before_network(self):
        with self.assertRaisesRegex(DeploymentError, "PTERODACTYL_API_KEY"):
            Panel("https://panel.example", "6c2b7716", "")

    def test_insecure_panel_url_is_rejected(self):
        with self.assertRaisesRegex(DeploymentError, "HTTPS"):
            Panel("http://panel.example", "6c2b7716", "test-key")

    def test_api_errors_do_not_expose_signed_url_or_response(self):
        self.panel.opener = Mock()
        self.panel.opener.open.side_effect = HTTPError(
            "https://node.example/?token=secret-token", 403, "secret-reason", {}, io.BytesIO(b"secret-body"))
        with self.assertRaises(DeploymentError) as caught:
            self.panel.request("GET", "https://node.example/?token=secret-token")
        self.assertIn("403", str(caught.exception))
        self.assertNotIn("secret", str(caught.exception))

    def test_redirect_is_blocked(self):
        with self.assertRaisesRegex(DeploymentError, "redirect"):
            NoRedirects().redirect_request(None, None, 302, "Found", {}, "https://other.example")

    def test_existing_file_cannot_be_used_as_update_directory(self):
        self.panel.files = Mock(return_value={"update": {"is_file": True}})
        with self.assertRaisesRegex(DeploymentError, "normal directory"):
            self.panel.ensure_directory("/plugins", "update")


if __name__ == "__main__":
    unittest.main()
