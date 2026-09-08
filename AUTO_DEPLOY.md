# Automatic RCDoors updates

The local machine builds and tests once. Forgejo stores that exact tested JAR on
`codex/prebuilt`; the NAS downloads it, verifies its SHA-256 and plugin identity,
and stages it in `/plugins/update` on Pterodactyl server `6c2b7716`.
The NAS does not run Java, Maven, or plugin tests.

## Normal development

Requirements: Java 25, Maven, Python 3 and PyYAML. Keep the dependency repositories
as siblings with their committed `main` branches up to date.

1. Edit and stage the intended source files.
2. `python scripts/publish_local.py prepare`
3. Commit the staged changes on `main`.
4. `python scripts/publish_local.py publish`

The publisher pushes source and the tested JAR using existing Git SSH access;
no new API token is needed. A plain source-only push does not deploy. A failed
build cannot be published, and publication refuses a commit whose source tree
differs from the tested snapshot. Logs and the tested artifact are stored under
the repository's Git metadata in `local-publish`. Dependency build results are
reused only when their source commits, tool settings and installed-file hashes match.

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
The installed filename is retained even if it contains an older version number;
the contents are the new version. Temporary uploads are verified, and the prior
pending update is preserved if promotion fails. No server restart, live JAR replacement,
configuration edits or plugin-data changes occur. Restart after uploads finish
to apply the pending updates.
