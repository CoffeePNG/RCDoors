# Automatic RCDoors updates

The local machine builds and tests once. Forgejo stores that exact tested JAR on
`codex/prebuilt`; the NAS downloads it, verifies its SHA-256 and plugin identity,
and stages it in `/plugins/update` on Pterodactyl server `6c2b7716`.
The NAS does not run Java, Maven, or plugin tests.

## Normal development

Requirements: Java 25, Maven, Python 3 and PyYAML. Keep the dependency repositories
as siblings with their committed `main` branches up to date.

1. Edit and stage the intended source files.
2. `python scripts/publish_local.py prepare --tests <Surefire-selection>`
3. Commit the staged changes on `main`.
4. `python scripts/publish_local.py publish`

Preparation requires an explicit verification choice. Use `--tests MessageCatalogTest`
(or `--tests TestClass#method`) for affected Maven behavior. Add, for example,
`--python-tests scripts.tests.test_publish_selection.VerificationSelectionTests`
only when publisher logic changed. Maven integration-test suites are skipped for
focused verification. Reactor modules without a selected test may skip; preparation
fails if no selected test actually executes anywhere.

Use `--package-only` when the relevant verification is a configuration/manual check
or selected Python tests; it skips Maven test compilation and execution. Full Maven
and Python discovery runs require `--full-suite` and explicit user authorization for
the current work. A bare `prepare` fails instead of silently choosing a full suite.
Unchanged dependencies always install with tests skipped, using separate cache keys.
The artifact manifest records the chosen scope and named checks.

The publisher pushes source and the tested JAR using existing Git SSH access;
no new API token is needed. A plain source-only push does not deploy. A failed
build cannot be published, and publication refuses a commit whose source tree
differs from the tested snapshot. Logs and the tested artifact are stored under
the repository's Git metadata in `local-publish`. Dependency build results are
reused only when their source commits, package-only command, tool settings and installed-file hashes match.

The manifest records the exact source and dependency commits and JAR checksum.
Older source commits are skipped by the NAS. Previous published JARs remain in
the artifact branch's Git history; this increases repository storage over time.
Manual workflow runs must select `codex/prebuilt`.

The uploader first filters the directory listing by the exact plugin name before
the first hyphen (case-insensitive), or the entire name for an unversioned JAR.
Use `PluginName.jar` or `PluginName-VERSION.jar`; arbitrary renamed JARs are not
supported. Only the matching installed JAR and relevant pending JAR are downloaded
to verify their internal identities. Unrelated and third-party JARs are never
downloaded. Multiple matching filenames stop deployment before any replacement.
Updates always use the stable `PluginName.jar` filename. A single pending
versioned JAR is migrated to that stable name after the new upload is verified;
multiple pending candidates are rejected. The prior pending file and its name
are restored if promotion fails. Paper matches the internal plugin identity
at startup, replaces the old installed JAR, and adopts the stable update filename.
The running installed file is not renamed or replaced by the uploader.

To verify a release, open the latest Forgejo Actions run and expand
"Verify and forward the locally tested JAR". The log reports the exact source
commit, the version read from inside the JAR, and its verified SHA-256 checksum.
Use these values rather than inferring a version from the stable filename. No server restart, live JAR replacement,
configuration edits or plugin-data changes occur. Restart after uploads finish
to apply the pending updates.
