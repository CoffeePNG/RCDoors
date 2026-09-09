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
import xml.etree.ElementTree as ET
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


def verification_commands(maven, tests=None, python_tests=(), package_only=False, full_suite=False):
    """Construct the explicitly selected checks; never infer permission for a whole suite."""
    if sum((tests is not None, bool(package_only), bool(full_suite))) != 1:
        raise ValueError('Choose --tests, --package-only, or explicitly --full-suite for prepare.')
    python_tests = tuple(python_tests)
    if any(not name.strip() or name == 'discover' or name.startswith('-') for name in python_tests):
        raise ValueError('--python-tests requires unittest module/class/method names, not discovery options.')
    if full_suite and python_tests:
        raise ValueError('--full-suite already selects the Python suite; do not combine it with --python-tests.')
    maven_command = [maven, '--batch-mode', '--no-transfer-progress']
    if tests is not None:
        tests = tests.strip()
        if not tests or tests in ('*', '**') or any(not item.strip() for item in tests.split(',')):
            raise ValueError('--tests requires a focused Surefire selection; use --full-suite for all tests.')
        maven_command += ['-Dtest=' + tests, '-Dsurefire.failIfNoSpecifiedTests=false', '-DskipITs=true']
    elif package_only:
        maven_command += ['-Dmaven.test.skip=true', '-DskipTests=true', '-DskipITs=true']
    maven_command += ['clean', 'verify']
    commands = []
    if full_suite:
        commands.append([sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts/tests', '-v'])
    elif python_tests:
        commands.append([sys.executable, '-m', 'unittest', '-v', *python_tests])
    commands.append(maven_command)
    return commands


def dependency_command(maven, arguments):
    # Upstream source is unchanged in this request; install its artifacts without unrelated tests.
    return [maven, '--batch-mode', '--no-transfer-progress', *arguments,
            '-Dmaven.test.skip=true', '-DskipTests=true', '-DskipITs=true', 'clean', 'install']


def dependency_cache_key(name, command, sources, java_home):
    return hashlib.sha256(json.dumps(
        ['dependencies-package-only-v1', name, command, sources, java_home],
        sort_keys=True).encode()).hexdigest()


def require_executed_tests(workspace):
    # Reactor modules without matching tests may skip, but a typo must not create a tested manifest.
    executed = 0
    for report in workspace.glob('**/target/surefire-reports/TEST-*.xml'):
        suite = ET.parse(report).getroot()
        executed += int(suite.get('tests', '0')) - int(suite.get('skipped', '0'))
    if executed < 1:
        raise RuntimeError('No selected Maven tests executed; check --tests or explicitly use --package-only.')
    return executed


def build(repo, maven, tests=None, python_tests=(), package_only=False, full_suite=False):
    commands = verification_commands(maven, tests, python_tests, package_only, full_suite)
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
            command = dependency_command(maven, arguments)
            key = dependency_cache_key(name, command, dependency_sources, os.environ.get('JAVA_HOME'))
            cache_file = cache_root / (key + '.json')
            cached = json.loads(cache_file.read_text()) if cache_file.exists() else {}
            if cached and all(Path(p).is_file() and digest(Path(p)) == sha for p, sha in cached.items()):
                print('Reusing locally packaged dependency (tests skipped): ' + name, flush=True)
                continue
            log = state / (name + '-dependency.log')
            with log.open('w') as output:
                run(command, work_root / name, stdout=output, stderr=subprocess.STDOUT)
            installed = {}
            for line in log.read_text(errors='replace').splitlines():
                if '[INFO] Installing ' in line and ' to ' in line:
                    path = Path(line.rsplit(' to ', 1)[1].strip())
                    if path.is_file() and path.suffix in ('.jar', '.pom'):
                        installed[str(path)] = digest(path)
            if installed:
                cache_file.write_text(json.dumps(installed))
        log = state / 'build.log'
        print('Packaging staged source with the selected verification. Log: ' + str(log), flush=True)
        with log.open('w') as output:
            for command in commands:
                output.write('Running: ' + subprocess.list2cmdline(command) + '\n')
                output.flush()
                run(command, workspace, stdout=output, stderr=subprocess.STDOUT)
        executed = require_executed_tests(workspace) if tests is not None else None
        artifact = state / (config['identity'] + '.jar')
        prepare(workspace / config['artifact_directory'], config['identity'], artifact)
        manifest = {
            'schema': 1, 'repository': config['repository'], 'identity': config['identity'],
            'source_tree': tree, 'jar': artifact.name, 'sha256': digest(artifact),
            'dependencies': dependencies, 'tested_at': datetime.now(timezone.utc).isoformat(),
            'tests': 'full suite' if full_suite else 'focused Maven: ' + tests if tests is not None else 'package only (Maven tests skipped)',
            'verification': {
                'mode': 'full-suite' if full_suite else 'focused' if tests is not None else 'package-only',
                'maven_selection': tests, 'maven_tests_executed': executed,
                'python_selections': list(python_tests), 'python_full_suite': bool(full_suite),
                'dependency_tests': 'skipped',
            },
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
    checks = parser.add_mutually_exclusive_group()
    checks.add_argument('--tests', help='Focused Maven Surefire selection, such as MessageCatalogTest or TestClass#method')
    checks.add_argument('--package-only', action='store_true', help='Package without running Maven tests')
    checks.add_argument('--full-suite', action='store_true', help='Explicitly run the full Maven and Python suites')
    parser.add_argument('--python-tests', nargs='+', default=[], help='Explicit unittest module/class/method names')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    if args.command == 'prepare':
        try:
            verification_commands(args.maven, args.tests, args.python_tests, args.package_only, args.full_suite)
        except ValueError as error:
            parser.error(str(error))
        build(repo, args.maven, args.tests, args.python_tests, args.package_only, args.full_suite)
    else:
        if args.tests is not None or args.python_tests or args.package_only or args.full_suite:
            parser.error('Verification options apply to prepare, not publish.')
        publish(repo)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as exc:
        print('Publish stopped: ' + str(exc), file=sys.stderr)
        sys.exit(1)
