# Automatic RCDoors updates

Pushes to `main` build and test the plugin on the UGREEN NAS. Pull requests only
build and test. A successful latest-main build is retained as an Actions artifact
for 30 days and staged on Pterodactyl server `6c2b7716` using the repository secret
`PTERODACTYL_API_KEY`. A manual workflow run on `main` uses the same checks.

The uploader reads `name` from plugin.yml/paper-plugin.yml inside the built and
installed JARs. It requires exactly one installed plugin with that identity and
uses its existing filename in `/plugins/update`. For example, a new 3.1 build
replaces the contents of an installed `Plugin-3.0.jar` on the next normal restart;
the filename can retain 3.0 while the plugin's internal version is 3.1. No second
installed JAR is created. Duplicate installed or differently named pending JARs
stop deployment for review. Install a missing plugin once before enabling uploads.

Uploads use a temporary filename and SHA-256 verification before promotion.
The previous pending update is backed up during replacement and restored if
promotion fails. Failed temporary files can be inspected in the update directory.
No power commands, live JAR replacement, config changes, or plugin-data changes
are made. Changes take effect at the next server restart.

Build dependencies are checked out from their `main` branches and compiled on the
runner. The runner needs read access to the internal RCPlatform/RCUI repositories
(and RCBusiness for RCCriminalEnterprises). Checkout logs record dependency commits.
The NAS runs one job at a time using its existing isolated Docker engine.

The uploader tests require Python 3 and PyYAML (`python3-yaml` on Debian).
Run `python3 -m unittest discover -s scripts/tests -v` and Maven `clean verify`.
Paper's update folder: https://docs.papermc.io/paper/updating/

`FORGEJO_DEPENDENCY_TOKEN` supplies read-only access to the four shared build/test
repositories RCPlatform, RCUI, RCBusiness, and RCCriminalEnterprises. Dependency
checkouts do not persist this token in Git config. Fork PRs without access to this
secret require a trusted maintainer branch to run the private-dependency build.
