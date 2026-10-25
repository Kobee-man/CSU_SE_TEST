"""Install the versioned hooks for this clone and its existing worktrees."""
from pathlib import Path
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    hooks = root / ".githooks"
    previous = subprocess.run(
        ["git", "config", "--get", "core.hooksPath"], cwd=root,
        text=True, capture_output=True, check=False,
    ).stdout.strip()
    # Do not silently replace another team's hook manager.
    if previous and previous != str(hooks) and previous != ".githooks":
        raise SystemExit(f"Existing core.hooksPath={previous}; integrate it before installing.")
    for name in ("pre-commit", "pre-push"):
        path = hooks / name
        path.chmod(path.stat().st_mode | 0o111)
    # Absolute path also enables hooks in worktrees predating this change.
    subprocess.run(["git", "config", "--local", "core.hooksPath", str(hooks)], cwd=root, check=True)
    print(f"Installed pre-commit and pre-push: {hooks}")


if __name__ == "__main__":
    main()
