"""Stage an existing Paper plugin update through the Pterodactyl Client API.

Only the final rename exposes a verified JAR to Paper. This script never sends
power commands or changes the installed JAR, configuration, or plugin data.
"""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid
import zipfile
import yaml


class DeploymentError(Exception):
    pass


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the panel key or a signed URL to a redirect destination.
        raise DeploymentError("Unexpected HTTP redirect; check the configured panel/node URL.")


def require_https(url):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise DeploymentError("Panel and node URLs must use HTTPS without embedded credentials.")
    return parsed


class Panel:
    def __init__(self, url, server_id, api_key):
        parsed = require_https(url)
        if parsed.query or parsed.fragment or parsed.path not in ("", "/"):
            raise DeploymentError("PTERODACTYL_URL must be the panel origin, without a path.")
        if not re.fullmatch(r"[a-zA-Z0-9-]+", server_id):
            raise DeploymentError("PTERODACTYL_SERVER_ID is missing or invalid.")
        if not api_key.strip():
            raise DeploymentError("Set the Forgejo Actions secret PTERODACTYL_API_KEY.")
        self.base = url.rstrip("/") + "/api/client/servers/" + server_id
        self.api_key = api_key.strip()
        self.opener = build_opener(NoRedirects())

    def request(self, method, url, data=None, headers=None):
        require_https(url)
        try:
            with self.opener.open(Request(url, data=data, headers=headers or {}, method=method),
                                  timeout=120) as response:
                return response.read()
        except HTTPError as exc:
            # Responses and signed URLs may contain credentials. Log only status.
            raise DeploymentError(f"Pterodactyl request failed (HTTP {exc.code}). Check file permissions and URLs.") from None
        except (URLError, TimeoutError, OSError):
            raise DeploymentError("Pterodactyl connection failed; check panel/node reachability and TLS.") from None

    def api(self, method, path, payload=None, **query):
        url = self.base + path
        if query:
            url += "?" + urlencode(query)
        headers = {"Authorization": "Bearer " + self.api_key, "Accept": "application/json"}
        data = None
        if payload is not None:
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        try:
            response = self.request(method, url, data, headers)
        except DeploymentError as exc:
            raise DeploymentError(f"{method} {path}: {exc}") from None
        return json.loads(response) if response else None

    def files(self, directory):
        return {entry["attributes"]["name"]: entry["attributes"]
                for entry in self.api("GET", "/files/list", directory=directory)["data"]}

    def ensure_directory(self, parent, name):
        existing = self.files(parent).get(name)
        if existing is None:
            self.api("POST", "/files/create-folder", {"root": parent, "name": name})
        elif existing.get("is_file") or existing.get("is_symlink"):
            raise DeploymentError(f"Expected a normal directory at {parent}/{name}.")

    def download(self, remote_path):
        url = self.api("GET", "/files/download", file=remote_path)["attributes"]["url"]
        # Wings uses a short-lived signed URL; the panel API key is not sent.
        return self.request("GET", url)

    def checksum(self, remote_path):
        return hashlib.sha256(self.download(remote_path)).hexdigest()

    def upload(self, directory, filename, contents):
        signed_url = self.api("GET", "/files/upload")["attributes"]["url"]
        parsed = require_https(signed_url)
        query = [(k, v) for k, v in parse_qsl(parsed.query) if k != "directory"]
        query.append(("directory", directory))
        url = urlunsplit(parsed._replace(query=urlencode(query)))
        boundary = "forgejo" + uuid.uuid4().hex
        body = (f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="files"; filename="{filename}"\r\n'
                "Content-Type: application/java-archive\r\n\r\n").encode()
        body += contents + f"\r\n--{boundary}--\r\n".encode()
        self.request("POST", url, body, {"Content-Type": "multipart/form-data; boundary=" + boundary})


def plugin_name(contents):
    """Read Paper's plugin identity, independently of the archive filename/version."""
    try:
        with zipfile.ZipFile(io.BytesIO(contents)) as archive:
            names = []
            for descriptor in ("paper-plugin.yml", "plugin.yml"):
                if descriptor not in archive.namelist():
                    continue
                if archive.getinfo(descriptor).file_size > 1024 * 1024:
                    raise DeploymentError("Plugin descriptor is too large.")
                metadata = yaml.safe_load(archive.read(descriptor))
                name = metadata.get("name") if isinstance(metadata, dict) else None
                if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
                    raise DeploymentError("Plugin descriptor has an invalid name.")
                names.append(name)
            if not names:
                return None
            if len(set(names)) != 1:
                raise DeploymentError("Plugin descriptors have conflicting names.")
            return names[0]
    except (zipfile.BadZipFile, UnicodeError, yaml.YAMLError, RuntimeError):
        raise DeploymentError("Cannot read plugin identity from the JAR.") from None


def matching_jars(panel, directory, identity):
    """Filter the directory listing without downloading unrelated plugin JARs."""
    matches = []
    for name, attributes in panel.files(directory).items():
        if not name.lower().endswith(".jar") or name[:-4].split("-", 1)[0].casefold() != identity.casefold():
            continue
        if not attributes.get("is_file") or attributes.get("is_symlink"):
            raise DeploymentError(f"Expected a normal plugin file at {directory}/{name}.")
        matches.append(name)
    return matches


def stage(panel, jar):
    name = jar.name
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.jar", name):
        raise DeploymentError("Supply a JAR with a simple filename, matching the installed plugin.")
    try:
        with zipfile.ZipFile(jar) as archive:
            if not {"plugin.yml", "paper-plugin.yml"}.intersection(archive.namelist()):
                raise DeploymentError("The JAR is not a deployable Paper plugin.")
            if archive.testzip() is not None:
                raise DeploymentError("The JAR is corrupt.")
        contents = jar.read_bytes()
    except (OSError, zipfile.BadZipFile):
        raise DeploymentError("The built plugin JAR is missing, unreadable, or invalid.") from None
    checksum = hashlib.sha256(contents).hexdigest()
    identity = plugin_name(contents)
    installed = matching_jars(panel, "/plugins", identity)
    if len(installed) != 1:
        raise DeploymentError(f"Expected exactly one installed {identity} filename; found {len(installed)}. Use {identity}.jar or {identity}-VERSION.jar and resolve duplicates.")
    name = installed[0]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.jar", name):
        raise DeploymentError("The installed plugin needs a simple filename before enabling updates.")
    if plugin_name(panel.download("/plugins/" + name)) != identity:
        raise DeploymentError("The matching installed filename belongs to a different plugin.")
    print(f"Built {jar.name}; matched plugin {identity} to installed filename {name}.")
    panel.ensure_directory("/plugins", "update")
    other_pending = [item for item in matching_jars(panel, "/plugins/update", identity) if item != name]
    if other_pending:
        raise DeploymentError(f"Conflicting pending updates for {identity}: {', '.join(other_pending)}. Resolve them before deploying.")
    destination = "/plugins/update/" + name
    pending = panel.files("/plugins/update").get(name)
    pending_checksum = None
    if pending:
        if not pending.get("is_file") or pending.get("is_symlink"):
            raise DeploymentError("The update destination must be a normal file.")
        pending_contents = panel.download(destination)
        if plugin_name(pending_contents) != identity:
            raise DeploymentError("The update filename belongs to a different plugin.")
        pending_checksum = hashlib.sha256(pending_contents).hexdigest()
        if pending_checksum == checksum:
            print(f"{name} is already staged with SHA-256 {checksum}.")
            return
    temporary = f".{name}.{uuid.uuid4().hex}.uploading"
    panel.upload("/plugins/update", temporary, contents)
    if panel.checksum("/plugins/update/" + temporary) != checksum:
        raise DeploymentError("Uploaded checksum mismatch. The pending JAR was not replaced.")
    promote(panel, temporary, name, checksum, pending_checksum)
    print(f"Staged {name} in /plugins/update; SHA-256 {checksum}.")
    print("It will take effect on the next server restart. No restart was requested.")


def rename_file(panel, source, target):
    panel.api("PUT", "/files/rename", {
        "root": "/plugins/update", "files": [{"from": source, "to": target}],
    })


def promote(panel, temporary, name, checksum, pending_checksum):
    """Wings refuses overwriting renames, so temporarily preserve a pending JAR.

    The old pending file stays recoverable until the new destination is verified.
    Files with .previous/.uploading suffixes are ignored by Paper.
    """
    directory = "/plugins/update/"
    backup = None
    if pending_checksum is not None:
        backup = f".{name}.{uuid.uuid4().hex}.previous"
        rename_file(panel, name, backup)
        if panel.checksum(directory + backup) != pending_checksum:
            raise DeploymentError(f"Previous pending JAR could not be verified. Inspect {directory}{backup}.")
    try:
        rename_file(panel, temporary, name)
        if panel.checksum(directory + name) != checksum:
            raise DeploymentError("Final staged JAR checksum mismatch.")
    except DeploymentError as promotion_error:
        # A timed-out rename may have completed. Check before doing anything else.
        restored = False
        try:
            current = panel.files("/plugins/update").get(name)
            if current and panel.checksum(directory + name) == checksum:
                pass  # The verified destination proves promotion completed.
            elif backup and current is None:
                rename_file(panel, backup, name)
                if panel.checksum(directory + name) != pending_checksum:
                    raise DeploymentError("Restored pending JAR checksum mismatch.")
                restored = True
            else:
                raise promotion_error
        except DeploymentError as recovery_error:
            location = f" Previous pending JAR recovery path: {directory}{backup}." if backup else ""
            raise DeploymentError(f"{recovery_error}{location}") from None
        if restored:
            raise DeploymentError("Promotion failed; the previous pending JAR was restored.") from None
    if backup:
        # This temporary backup is no longer needed; successful build artifacts
        # are retained in Forgejo. Cleanup failure does not invalidate the upload.
        try:
            panel.api("POST", "/files/delete", {"root": "/plugins/update", "files": [backup]})
        except DeploymentError:
            print(f"Update verified; temporary backup {directory}{backup} could not be removed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    args = parser.parse_args()
    try:
        panel = Panel(os.environ.get("PTERODACTYL_URL", ""),
                      os.environ.get("PTERODACTYL_SERVER_ID", ""),
                      os.environ.get("PTERODACTYL_API_KEY", ""))
        stage(panel, args.jar)
    except (DeploymentError, ValueError, KeyError, TypeError):
        # Print expected safe diagnostics, never raw server responses or URLs.
        error = sys.exc_info()[1]
        print(f"Deployment failed: {error if isinstance(error, DeploymentError) else 'Unexpected API response.'}",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
