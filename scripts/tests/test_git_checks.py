"""Exercise real Git indexes, refs and merge conflicts; Gradle is mocked."""
from pathlib import Path
import importlib.util
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("git_checks", Path(__file__).parents[1] / "git_checks.py")
checks = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checks)


class GitChecksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="notes-hook-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo with spaces"
        self.root.mkdir()
        self.env = patch.dict(os.environ, {
            "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_AUTHOR_NAME": "Hook Test", "GIT_AUTHOR_EMAIL": "test@example.invalid",
            "GIT_COMMITTER_NAME": "Hook Test", "GIT_COMMITTER_EMAIL": "test@example.invalid",
        })
        self.env.start()
        self.addCleanup(self.env.stop)
        self.git("init", "-b", "frontend")
        self.git("config", "core.hooksPath", str(self.root / "no-hooks"))
        self.write("gradlew", "#!/bin/sh\n")
        self.write("gradlew.bat", "@echo off\n")
        self.write("note.txt", "original\n")
        self.commit("initial")

    def git(self, *args):
        return checks.git(self.root, *args)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")

    def commit(self, message):
        self.git("add", ".")
        self.git("-c", "commit.gpgsign=false", "commit", "-m", message)
        return self.git("rev-parse", "HEAD")

    def test_commit_checks_staged_content_and_preserves_unstaged_edits(self):
        self.write("note.txt", "staged\n")
        self.git("add", "note.txt")
        self.write("note.txt", "unstaged\n")
        self.write("local.properties", "sdk.dir=C:/Android/Sdk\nsecret=never-copy\n")
        before = self.git("status", "--porcelain")

        def inspect(root, tree, stage, tasks):
            target = Path(self.temp.name) / "snapshot"
            target.mkdir()
            checks.export_snapshot(root, tree, target)
            self.assertEqual((target / "note.txt").read_text(), "staged\n")
            self.assertEqual((target / "local.properties").read_text(), "sdk.dir=C\\:/Android/Sdk\n")
            self.assertEqual(tasks, [":app:lintDebug"])

        with patch.object(checks, "check_snapshot", side_effect=inspect) as run:
            checks.pre_commit(self.root)
            run.assert_called_once()
        self.assertEqual(before, self.git("status", "--porcelain"))
        self.assertEqual((self.root / "note.txt").read_text(), "unstaged\n")

    def test_whitespace_failure_blocks_before_gradle(self):
        self.write("note.txt", "bad trailing space \n")
        self.git("add", "note.txt")
        with patch.object(checks, "check_snapshot") as run:
            with self.assertRaises(checks.CheckError):
                checks.pre_commit(self.root)
            run.assert_not_called()

    def make_remote(self):
        remote = Path(self.temp.name) / "remote.git"
        subprocess.run(["git", "init", "--bare", str(remote)], capture_output=True, check=True)
        self.git("remote", "add", "origin", str(remote))
        self.git("-c", "core.hooksPath=" + str(self.root / "no-hooks"), "push", "origin", "frontend")

    def push_line(self, commit, branch="feature"):
        return f"refs/heads/{branch} {commit} refs/heads/{branch} {'0' * 40}\n"

    def test_push_checks_published_commit_and_fresh_merged_tree(self):
        self.make_remote()
        self.git("checkout", "-b", "feature")
        self.write("feature.txt", "feature\n")
        feature = self.commit("feature")
        self.git("checkout", "frontend")
        self.write("target.txt", "target\n")
        self.commit("target")
        self.git("push", "origin", "frontend")
        # HEAD deliberately differs from the pushed ref.
        with patch.object(checks, "check_snapshot") as run:
            checks.pre_push(self.root, "origin", [self.push_line(feature)])
            self.assertEqual(run.call_count, 2)
            original, merged = [call.args[1] for call in run.call_args_list]
            self.assertEqual(self.git("show", f"{original}:feature.txt"), "feature")
            self.assertEqual(self.git("show", f"{merged}:target.txt"), "target")
            self.assertEqual(self.git("show", f"{merged}:feature.txt"), "feature")

    def test_merge_conflict_blocks_without_changing_index_or_head(self):
        self.make_remote()
        self.git("checkout", "-b", "feature")
        self.write("note.txt", "feature\n")
        feature = self.commit("feature")
        self.git("checkout", "frontend")
        self.write("note.txt", "target\n")
        head = self.commit("target")
        self.git("push", "origin", "frontend")
        with patch.object(checks, "check_snapshot") as run:
            with self.assertRaises(checks.CheckError):
                checks.pre_push(self.root, "origin", [self.push_line(feature)])
            run.assert_not_called()
        self.assertEqual(self.git("rev-parse", "HEAD"), head)
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_deletion_skips_network_and_build(self):
        with patch.object(checks, "check_snapshot") as run:
            checks.pre_push(self.root, "missing-remote", [self.push_line("0" * 40)])
            run.assert_not_called()

    def test_fetch_failure_blocks_build(self):
        with patch.object(checks, "check_snapshot") as run:
            with self.assertRaises(checks.CheckError):
                checks.pre_push(self.root, "missing-remote", [self.push_line(self.git("rev-parse", "HEAD"))])
            run.assert_not_called()

    def test_gradle_failure_blocks_and_retains_reports(self):
        def fail(command, cwd):
            reports = Path(cwd) / "app/build/reports"
            reports.mkdir(parents=True)
            (reports / "lint-results-debug.html").write_text("failure", encoding="utf-8")
            return subprocess.CompletedProcess(command, 1)

        tree = self.git("rev-parse", "HEAD^{tree}")
        with patch.object(checks, "export_snapshot"), patch.object(checks.subprocess, "run", side_effect=fail):
            with self.assertRaises(checks.CheckError):
                checks.check_snapshot(self.root, tree, "pre-commit", [":app:lintDebug"])
        self.assertTrue((self.root / "build/hook-reports/pre-commit" / tree / "lint-results-debug.html").is_file())


if __name__ == "__main__":
    unittest.main()
