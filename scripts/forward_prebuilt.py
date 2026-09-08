"""Verify and forward a locally tested JAR; never compile or test Java on the NAS."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

from deploy_pterodactyl import DeploymentError, plugin_name, main as deploy_main


def validate(manifest, config, root):
    if manifest.get('schema') != 1 or manifest.get('repository') != config['repository']:
        raise DeploymentError('The build manifest belongs to a different repository or schema.')
    if manifest.get('identity') != config['identity'] or manifest.get('jar') != config['identity'] + '.jar':
        raise DeploymentError('The build manifest has an unexpected plugin or filename.')
    if not re.fullmatch(r'[0-9a-f]{40}', manifest.get('source_commit', '')):
        raise DeploymentError('The build manifest needs an exact source commit.')
    jar = root / 'artifact' / manifest['jar']
    if jar.is_symlink() or not jar.is_file():
        raise DeploymentError('The published artifact must be a regular file.')
    contents = jar.read_bytes()
    if hashlib.sha256(contents).hexdigest() != manifest.get('sha256'):
        raise DeploymentError('The published JAR failed checksum verification.')
    if plugin_name(contents) != config['identity']:
        raise DeploymentError('The published JAR has the wrong plugin identity.')
    return jar


def main():
    root = Path(__file__).resolve().parent.parent
    config = json.loads((root / 'scripts/publish-config.json').read_text())
    manifest = json.loads((root / 'manifest.json').read_text())
    jar = validate(manifest, config, root)
    latest = subprocess.check_output(['git', 'ls-remote', 'origin', 'refs/heads/main'], cwd=root, text=True).split()
    if not latest:
        raise DeploymentError('Could not resolve the source main branch.')
    if latest[0] != manifest['source_commit']:
        print('A newer source commit is on main; this older JAR will not be uploaded.')
        return
    print('Forwarding locally tested source ' + manifest['source_commit'])
    sys.argv = [sys.argv[0], str(jar)]
    return deploy_main()


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (DeploymentError, OSError, ValueError, subprocess.CalledProcessError) as exc:
        print('Forwarding stopped: ' + str(exc), file=sys.stderr)
        sys.exit(1)
