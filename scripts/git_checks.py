"""Check immutable Git snapshots without stashing or modifying the working tree."""
from pathlib import Path, PurePosixPath
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile


class CheckError(Exception):
    pass


def git(root, *args):
    result = subprocess.run(["git", *args], cwd=root, capture_output=True)
    if result.returncode:
        raise CheckError(result.stderr.decode("utf-8", "replace") + result.stdout.decode("utf-8", "replace"))
    return result.stdout.decode("utf-8", "replace").strip()


def setting(root, key, default):
    result = subprocess.run(["git", "config", "--get", key], cwd=root, capture_output=True, text=True)
    if result.returncode not in (0, 1):
        raise CheckError(result.stderr)
    return result.stdout.strip() or default


def export_snapshot(root, tree, destination):
    archive = subprocess.run(["git", "archive", "--format=tar", tree], cwd=root, capture_output=True)
    if archive.returncode:
        raise CheckError(archive.stderr.decode("utf-8", "replace"))
    with tarfile.open(fileobj=io.BytesIO(archive.stdout)) as source:
        for member in source.getmembers():
            path = PurePosixPath(member.name)
            # A tracked symlink must never make extraction or a build escape the snapshot.
            if path.is_absolute() or ".." in path.parts or "\\" in member.name or ":" in member.name:
                raise CheckError(f"Unsafe archive path: {member.name}")
            if not (member.isfile() or member.isdir()):
                raise CheckError(f"Unsupported archive entry (links/submodules): {member.name}")
        if hasattr(tarfile, "data_filter"):
            source.extractall(destination, filter="data")
        else:
            source.extractall(destination)
    if not (destination / "gradlew.bat").is_file() or not (destination / "gradlew").is_file():
        raise CheckError("Snapshot has no Gradle wrapper; select an Android integration branch.")
    # Only the machine-specific SDK location is borrowed from the working tree.
    properties = root / "local.properties"
    if properties.is_file():
        sdk = [line for line in properties.read_text(encoding="utf-8-sig").splitlines()
               if line.lstrip().startswith("sdk.dir=")]
        if sdk:
            # Java properties require escaped drive colons. Use LF consistently;
            # machine-specific formatting must not create nonportable baselines.
            sdk = [re.sub(r"(?<!\\):", r"\\:", line) for line in sdk]
            (destination / "local.properties").write_text(
                "\n".join(sdk) + "\n", encoding="utf-8", newline="\n")


def check_snapshot(root, tree, stage, tasks):
    print(f"[{stage}] Checking Git snapshot {tree}", flush=True)
    with tempfile.TemporaryDirectory(prefix="notes-git-check-") as directory:
        snapshot = Path(directory)
        export_snapshot(root, tree, snapshot)
        if os.name == "nt":
            command = [str(snapshot / "gradlew.bat")]
        else:
            command = ["sh", str(snapshot / "gradlew")]
        result = subprocess.run(command + ["--console=plain", *tasks], cwd=snapshot)
        reports = snapshot / "app" / "build" / "reports"
        if reports.exists():
            target = root / "build" / "hook-reports" / stage / tree
            shutil.copytree(reports, target, dirs_exist_ok=True)
            print(f"[{stage}] Reports: {target}", flush=True)
        if result.returncode:
            raise CheckError(f"{stage}: Gradle failed; commit/push blocked.")


def pre_commit(root):
    git(root, "diff", "--cached", "--check")
    tree = git(root, "write-tree")
    check_snapshot(root, tree, "pre-commit", [":app:lintDebug"])


def pushed_commits(root, lines):
    commits = []
    for line in lines:
        fields = line.split()
        if len(fields) != 4:
            raise CheckError(f"Invalid pre-push input: {line!r}")
        local_ref, local_oid, _, _ = fields
        if set(local_oid) == {"0"}:  # Branch/tag deletion has no code to validate.
            continue
        commit = git(root, "rev-parse", "--verify", f"{local_oid}^{{commit}}")
        if commit not in commits:
            print(f"[pre-push] {local_ref}: {commit}", flush=True)
            commits.append(commit)
    return commits


def merge_tree(root, commit, base):
    # Git 2.38+ creates a merge tree without checkout, merge commits, or index changes.
    return git(root, "merge-tree", "--write-tree", commit, base).splitlines()[0]


def pre_push(root, remote, lines):
    commits = pushed_commits(root, lines)
    if not commits:
        return
    branch = setting(root, "hooks.integrationBranch", "frontend")
    integration_remote = setting(root, "hooks.integrationRemote", remote)
    git(root, "check-ref-format", f"refs/heads/{branch}")
    print(f"[pre-push] Fetching integration branch {integration_remote}/{branch}", flush=True)
    # Fetch failure blocks the push; stale local tracking refs are never accepted.
    git(root, "fetch", "--no-tags", "--", integration_remote, f"refs/heads/{branch}")
    base = git(root, "rev-parse", "--verify", "FETCH_HEAD^{commit}")
    checked = set()
    for commit in commits:
        # Check both the published commit and its integration result. A target-branch
        # fix must not hide a broken feature branch that is being published.
        for tree in (git(root, "rev-parse", f"{commit}^{{tree}}"), merge_tree(root, commit, base)):
            if tree not in checked:
                check_snapshot(root, tree, "pre-push", [
                    ":app:lintDebug", ":app:testDebugUnitTest",
                    ":app:assembleDebug", ":app:assembleRelease",
                ])
                checked.add(tree)


def main():
    try:
        root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel"))
        if len(sys.argv) == 2 and sys.argv[1] == "pre-commit":
            pre_commit(root)
        elif len(sys.argv) >= 3 and sys.argv[1] == "pre-push":
            pre_push(root, sys.argv[2], sys.stdin)
        else:
            raise CheckError("Usage: git_checks.py pre-commit | pre-push <remote> [url]")
    except (CheckError, OSError, subprocess.SubprocessError) as error:
        print(f"Git checks failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
