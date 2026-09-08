"""Build the staged source locally, then publish that exact tested JAR through Git."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

from prepare_artifact import prepare

BRANCH = 'codex/prebuilt'


def run(command, cwd, **kwargs):
    return subprocess.run(command, cwd=cwd, check=True, **kwargs)


def git(repo, *args, **kwargs):
    return subprocess.check_output(['git', '-C', str(repo), *args], **kwargs).decode().strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def state_directory(repo):
    path = Path(git(repo, 'rev-parse', '--path-format=absolute', '--git-common-dir')) / 'local-publish'
    path.mkdir(exist_ok=True)
    return path


def load_config(repo):
    return json.loads((repo / 'scripts/publish-config.json').read_text())


def check_source(repo, manifest):
    if git(repo, 'branch', '--show-current') != 'main':
        raise RuntimeError('Publish from main.')
    if git(repo, 'rev-parse', 'HEAD^{tree}') != manifest['source_tree']:
        raise RuntimeError('The committed source differs from the tested source. Stage changes and prepare again.')
    if git(repo, 'write-tree') != manifest['source_tree']:
        raise RuntimeError('There are staged changes after the build. Commit and prepare again.')


def build(repo, maven):
    config = load_config(repo)
    state = state_directory(repo)
    # An old successful build must not remain publishable after a failed attempt.
    (state / 'prepared.json').unlink(missing_ok=True)
    if git(repo, 'branch', '--show-current') != 'main':
        raise RuntimeError('Prepare from main, after staging the intended source changes.')
    tree = git(repo, 'write-tree')
    snapshot = git(repo, 'commit-tree', tree, '-p', 'HEAD', '-m', 'Temporary local build snapshot')
    base = Path.home() / '.rc-publish'
    base.mkdir(exist_ok=True)
    work_root = Path(tempfile.mkdtemp(prefix='b-', dir=base)).resolve()
    worktrees = []
    dependencies = {}
    def checkout(source, name, ref):
        path = work_root / name
        run(['git', '-c', 'core.longpaths=true', '-C', str(source), 'worktree', 'add', '--quiet', '--detach', str(path), ref], repo)
        worktrees.append((source, path))
        return path
    try:
        workspace = checkout(repo, config['local_name'], snapshot)
        for name in config['checkouts']:
            source = repo.parent / name
            commit = git(source, 'rev-parse', 'main')
            dependencies[name] = commit
            checkout(source, name, commit)
        cache_root = base / 'cache'
        cache_root.mkdir(exist_ok=True)
        for dependency in config['dependencies']:
            name, arguments = dependency['name'], dependency['arguments']
            required = ['RCPlatform'] if name == 'RCPlatform' else ['RCPlatform', 'RCUI'] if name == 'RCUI' else ['RCPlatform', 'RCUI', 'RCBusiness']
            dependency_sources = {n: dependencies[n] for n in required if n in dependencies}
            key = hashlib.sha256(json.dumps([name, arguments, dependency_sources, os.environ.get('JAVA_HOME'), str(maven)], sort_keys=True).encode()).hexdigest()
            cache_file = cache_root / (key + '.json')
            cached = json.loads(cache_file.read_text()) if cache_file.exists() else {}
            if cached and all(Path(p).is_file() and digest(Path(p)) == sha for p, sha in cached.items()):
                print('Reusing locally verified dependency: ' + name, flush=True)
                continue
            log = state / (name + '-dependency.log')
            with log.open('w') as output:
                run([maven, '--batch-mode', '--no-transfer-progress', *arguments, 'clean', 'install'], work_root / name, stdout=output, stderr=subprocess.STDOUT)
            installed = {}
            for line in log.read_text(errors='replace').splitlines():
                if '[INFO] Installing ' in line and ' to ' in line:
                    path = Path(line.rsplit(' to ', 1)[1].strip())
                    if path.is_file() and path.suffix in ('.jar', '.pom'):
                        installed[str(path)] = digest(path)
            if installed:
                cache_file.write_text(json.dumps(installed))
        log = state / 'build.log'
        print('Building and testing the staged source. Log: ' + str(log), flush=True)
        with log.open('w') as output:
            run([sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts/tests', '-v'], workspace, stdout=output, stderr=subprocess.STDOUT)
            run([maven, '--batch-mode', '--no-transfer-progress', 'clean', 'verify'], workspace, stdout=output, stderr=subprocess.STDOUT)
        artifact = state / (config['identity'] + '.jar')
        prepare(workspace / config['artifact_directory'], config['identity'], artifact)
        manifest = {
            'schema': 1, 'repository': config['repository'], 'identity': config['identity'],
            'source_tree': tree, 'jar': artifact.name, 'sha256': digest(artifact),
            'dependencies': dependencies, 'tested_at': datetime.now(timezone.utc).isoformat(),
            'tests': 'python unittest and mvn clean verify',
        }
        (state / 'prepared.json').write_text(json.dumps(manifest, indent=2) + '\n')
        print('Build passed. Commit the staged changes, then run: python scripts/publish_local.py publish', flush=True)
    finally:
        for source, path in reversed(worktrees):
            if path.resolve().parent != work_root or work_root.parent != base.resolve():
                raise RuntimeError('Unexpected build workspace path; refusing cleanup.')
            run(['git', '-c', 'core.longpaths=true', '-C', str(source), 'worktree', 'remove', '--force', str(path)], repo)
        work_root.rmdir()


def publish(repo):
    config = load_config(repo)
    state = state_directory(repo)
    manifest = json.loads((state / 'prepared.json').read_text())
    check_source(repo, manifest)
    artifact = state / manifest['jar']
    if digest(artifact) != manifest['sha256']:
        raise RuntimeError('The tested JAR changed. Prepare again.')
    expected_remote = 'ssh://git@forgejo.vhosts.win:2222/' + config['repository'] + '.git'
    if git(repo, 'remote', 'get-url', 'origin') != expected_remote:
        raise RuntimeError('Origin differs from the configured Forgejo repository.')
    commit = git(repo, 'rev-parse', 'HEAD')
    run(['git', 'push', 'origin', 'main:main'], repo)
    if git(repo, 'ls-remote', 'origin', 'refs/heads/main').split()[0] != commit:
        raise RuntimeError('Remote main advanced. Build its latest source before publishing.')
    manifest['source_commit'] = commit
    manifest_file = state / 'manifest.json'
    manifest_file.write_text(json.dumps(manifest, indent=2) + '\n')
    previous = git(repo, 'ls-remote', 'origin', 'refs/heads/' + BRANCH)
    parents = []
    if previous:
        run(['git', 'fetch', '--quiet', 'origin', 'refs/heads/' + BRANCH], repo)
        parents = ['-p', git(repo, 'rev-parse', 'FETCH_HEAD')]
    index = state / 'artifact.index'
    index.unlink(missing_ok=True)
    env = dict(os.environ, GIT_INDEX_FILE=str(index))
    git(repo, 'read-tree', '--empty', env=env)
    files = {
        'manifest.json': manifest_file,
        'artifact/' + artifact.name: artifact,
    }
    for file in ['.forgejo/workflows/verify.yml', 'scripts/deploy_pterodactyl.py', 'scripts/forward_prebuilt.py', 'scripts/publish-config.json']:
        # Read the committed, tested scripts, never unstaged working-tree changes.
        content = subprocess.check_output(['git', '-C', str(repo), 'show', commit + ':' + file])
        blob = git(repo, 'hash-object', '-w', '--stdin', input=content)
        git(repo, 'update-index', '--add', '--cacheinfo', '100644,' + blob + ',' + file, env=env)
    for name, path in files.items():
        blob = git(repo, 'hash-object', '-w', str(path))
        git(repo, 'update-index', '--add', '--cacheinfo', '100644,' + blob + ',' + name, env=env)
    tree = git(repo, 'write-tree', env=env)
    artifact_commit = git(repo, 'commit-tree', tree, *parents, '-m', 'Publish tested ' + config['identity'] + ' from ' + commit[:12])
    # A normal fast-forward push fails safely if another publisher wins the race.
    run(['git', 'push', 'origin', artifact_commit + ':refs/heads/' + BRANCH], repo)
    print('Published the tested JAR; NAS forwarding is queued. Source: ' + commit, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'publish'])
    parser.add_argument('--maven', default=shutil.which('mvn.cmd') or shutil.which('mvn') or 'mvn')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    if args.command == 'prepare':
        build(repo, args.maven)
    else:
        publish(repo)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as exc:
        print('Publish stopped: ' + str(exc), file=sys.stderr)
        sys.exit(1)
