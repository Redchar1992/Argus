"""Run offline structural checks against an imported corpus manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_manifest(manifest: dict[str, Any], corpus_root: Path) -> dict[str, Any]:
    """Validate reproducibility and mode-specific corpus invariants."""

    checks: list[dict[str, Any]] = []

    def check(name: str, passed: bool, details: str) -> None:
        checks.append({"name": name, "passed": passed, "details": details})

    sources = manifest.get("sources", [])
    ids = [source.get("id") for source in sources]
    check(
        "unique_source_ids",
        len(ids) == len(set(ids)) and all(isinstance(value, str) for value in ids),
        f"{len(ids)} source(s)",
    )
    check(
        "contains_short_story_and_novel",
        {source.get("mode") for source in sources} == {"SHORT_STORY", "NOVEL"},
        f"modes={sorted({source.get('mode') for source in sources})}",
    )

    for source in sources:
        source_id = str(source.get("id"))
        chapters = source.get("chapters", [])
        numbers = [chapter.get("number") for chapter in chapters]
        check(
            f"{source_id}.chapter_numbers",
            numbers == list(range(1, len(numbers) + 1)),
            f"count={len(numbers)}",
        )
        minimum = 20 if source.get("mode") == "NOVEL" else 1
        check(
            f"{source_id}.chapter_count",
            len(chapters) >= minimum,
            f"actual={len(chapters)}, minimum={minimum}",
        )
        for chapter in chapters:
            output_path = corpus_root / str(chapter.get("outputPath", ""))
            exists = output_path.is_file()
            hashed = exists and _sha256(output_path) == chapter.get("sha256")
            check(
                f"{source_id}.chapter_{chapter.get('number')}.content_hash",
                bool(exists and hashed and chapter.get("characters", 0) > 0),
                str(chapter.get("outputPath")),
            )

    failed = [item for item in checks if not item["passed"]]
    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(UTC).isoformat(),
        "status": "PASS" if not failed else "FAIL",
        "sourceCount": len(sources),
        "chapterCount": manifest.get("chapterCount", 0),
        "checks": checks,
        "failedChecks": len(failed),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--corpus-root", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    report = validate_manifest(manifest, args.corpus_root)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Offline corpus regression: {report['status']} ({len(report['checks'])} checks)")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
