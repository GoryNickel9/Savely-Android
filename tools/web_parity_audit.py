"""Read-only source comparison. A clean diff is not proof of functional parity."""
import argparse
import fnmatch
import json
import subprocess
from pathlib import Path


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args], check=True, capture_output=True,
        encoding="utf-8", errors="strict",
    ).stdout.strip()


def classify(path: str, modules: list[dict]) -> list[dict]:
    return [module for module in modules if any(
        fnmatch.fnmatchcase(path, pattern) for pattern in module["sources"]
    )]


def audit(repo: Path, source_map: dict, baseline: str, target: str) -> dict:
    before = git(repo, "rev-parse", "--verify", f"{baseline}^{{commit}}")
    after = git(repo, "rev-parse", "--verify", f"{target}^{{commit}}")
    # --no-renames includes both removed and added paths in the review.
    paths = git(repo, "diff", "--name-only", "--no-renames", before, after).splitlines()
    changes = []
    for path in paths:
        matched = classify(path, source_map["modules"])
        changes.append({
            "path": path,
            "modules": [module["id"] for module in matched],
            "tests": sorted({test for module in matched for test in module["tests"]}),
            "review": "semantic-review-required" if matched else "unmapped-source",
        })
    return {
        "schemaVersion": 1, "baseline": before, "target": after,
        "workingTreeClean": not bool(git(repo, "status", "--porcelain")),
        "changes": changes,
        "unmappedPaths": [change["path"] for change in changes if not change["modules"]],
        "parityVerified": False,
        "limitations": [
            "Source changes require semantic review and execution of affected tests.",
            "Uncommitted changes are excluded from the commit diff.",
            "Live Supabase configuration and deployed web revision are not checked.",
            "This report never advances a baseline or modifies the web repository.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--web-repo", type=Path, required=True)
    parser.add_argument("--source-map", type=Path, default=Path("parity/source-map.json"))
    parser.add_argument("--baseline")
    parser.add_argument("--target", default="HEAD")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source_map = json.loads(args.source_map.read_text(encoding="utf-8-sig"))
    report = audit(args.web_repo, source_map, args.baseline or source_map["referenceRevision"], args.target)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{len(report['changes'])} changed paths; {len(report['unmappedPaths'])} unmapped; parity unverified")


if __name__ == "__main__":
    main()
