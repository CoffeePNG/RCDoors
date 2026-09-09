import sys
from pathlib import Path
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publish_local import (dependency_cache_key, dependency_command,
                           require_executed_tests, verification_commands)


class VerificationSelectionTests(unittest.TestCase):
    def test_prepare_never_infers_full_suite_from_missing_selection(self):
        for options in ({}, {'tests': ''}, {'tests': '*'}, {'tests': '**'},
                        {'tests': 'TargetTest,'}, {'tests': 'TargetTest', 'full_suite': True}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                verification_commands('mvn', **options)

    def test_focused_maven_selects_only_named_behavior_without_python_discovery(self):
        commands = verification_commands('mvn', tests='CatalogTest,ReceiptTest#retry')
        self.assertEqual(commands, [['mvn', '--batch-mode', '--no-transfer-progress',
            '-Dtest=CatalogTest,ReceiptTest#retry', '-Dsurefire.failIfNoSpecifiedTests=false',
            '-DskipITs=true', 'clean', 'verify']])

    def test_package_only_can_run_explicit_publisher_tests_without_maven_tests(self):
        selected = 'scripts.tests.test_publish_selection.VerificationSelectionTests'
        commands = verification_commands('mvn', package_only=True, python_tests=[selected])
        self.assertEqual(commands[0], [sys.executable, '-m', 'unittest', '-v', selected])
        self.assertIn('-Dmaven.test.skip=true', commands[1])
        self.assertIn('-DskipTests=true', commands[1])
        self.assertIn('-DskipITs=true', commands[1])
        self.assertNotIn('discover', [value for command in commands for value in command])
        with self.assertRaises(ValueError):
            verification_commands('mvn', package_only=True, python_tests=['discover'])

    def test_full_suite_is_available_only_with_explicit_mode(self):
        commands = verification_commands('mvn', full_suite=True)
        self.assertEqual(commands[0], [sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts/tests', '-v'])
        self.assertEqual(commands[1], ['mvn', '--batch-mode', '--no-transfer-progress', 'clean', 'verify'])
        with self.assertRaises(ValueError):
            verification_commands('mvn', full_suite=True, python_tests=['scripts.tests.test_publish_selection'])

    def test_dependencies_skip_tests_and_the_effective_command_separates_cache_keys(self):
        command = dependency_command('mvn', ['-pl', 'api', '-am'])
        self.assertEqual(command[-5:], ['-Dmaven.test.skip=true', '-DskipTests=true', '-DskipITs=true', 'clean', 'install'])
        sources = {'RCPlatform': 'abc'}
        key = dependency_cache_key('RCPlatform', command, sources, 'java25')
        self.assertEqual(key, dependency_cache_key('RCPlatform', command, sources, 'java25'))
        self.assertNotEqual(key, dependency_cache_key('RCPlatform', ['mvn', 'clean', 'install'], sources, 'java25'))
        self.assertNotEqual(key, dependency_cache_key('RCPlatform', command, {'RCPlatform': 'new'}, 'java25'))

    def test_empty_or_only_skipped_reactor_selection_cannot_be_reported_as_verified(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            with self.assertRaises(RuntimeError):
                require_executed_tests(workspace)
            report = workspace / 'module/target/surefire-reports/TEST-CatalogTest.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite tests="2" skipped="2"/>')
            with self.assertRaises(RuntimeError):
                require_executed_tests(workspace)
            report.write_text('<testsuite tests="3" skipped="1"/>')
            self.assertEqual(2, require_executed_tests(workspace))


if __name__ == '__main__':
    unittest.main()
