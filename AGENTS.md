# Plugin publication

For every requested plugin change:
1. Make the edits and stage only the intended source files.
2. Run `python scripts/publish_local.py prepare --tests <Surefire-selection>` for the changed behavior only. Add `--python-tests <unittest names>` only for affected publisher tests. For changes already verified without Maven tests, use explicit `--package-only`. This packages the staged source in an isolated checkout and saves the exact verified JAR. Unchanged dependencies are packaged with tests skipped. `--full-suite` is allowed only when the user explicitly requests full-suite testing for the current work. Java 25, Maven, Python 3, and PyYAML are required. Shared build dependencies use the committed local `main` branches in sibling repositories.
3. Commit those staged changes on `main`.
4. Run `python scripts/publish_local.py publish`. This pushes `main` to the configured Forgejo repository and publishes the already-tested JAR to `codex/prebuilt`, which triggers NAS forwarding to the panel.

Use the publisher as the normal push step; a plain source push does not upload a JAR. Do not build/test the plugin again after a successful prepare unless its staged source changes. Do not commit existing IDE files or generated target changes. The NAS only verifies and forwards; it does not rebuild or restart the server.
