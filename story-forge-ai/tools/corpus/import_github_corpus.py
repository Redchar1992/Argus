"""Import selected Markdown/TXT files from private GitHub repositories.

The importer deliberately uses the authenticated ``gh`` CLI instead of storing a
GitHub token in this project.  It downloads only the paths listed in a config,
normalizes text locally, splits chapters, and writes a reproducible manifest with
commit SHAs and content hashes.  Raw corpus output should stay outside Git.

Example::

    python tools/corpus/import_github_corpus.py \
      --config tools/corpus/corpus-config.example.json \
      --output /tmp/story-forge-corpus \
      --manifest /tmp/story-forge-corpus/manifest.json
"""

from __future__ import annotations

import argparse
import base64
import fnmatch
import hashlib
import json
import re
import subprocess
import unicodedata
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

SUPPORTED_EXTENSIONS = {".md", ".markdown", ".txt"}
HEADING_RE = re.compile(
    r"^\s*(?:#{1,6}\s*)?"
    r"(?P<title>(?:第\s*[0-9０-９一二三四五六七八九十百千万零〇两]+\s*章)"
    r"|(?:Chapter\s+[0-9]{1,4})"
    r"|(?:序章|楔子|尾声|番外(?:篇)?))"
    r"(?:\s*[-—:：.、]?\s*(?P<suffix>[^\n]{0,80}))?\s*$",
    re.IGNORECASE,
)


class CorpusImportError(RuntimeError):
    """Raised when a corpus source cannot be imported safely."""


@dataclass(frozen=True)
class Chapter:
    """A normalized chapter extracted from one source file."""

    number: int
    title: str
    text: str
    source_path: str


def normalize_text(raw: bytes | str) -> str:
    """Decode and normalize author text without changing its semantic content."""

    if isinstance(raw, bytes):
        for encoding in ("utf-8-sig", "utf-8", "gb18030"):
            try:
                text = raw.decode(encoding)
                break
            except UnicodeDecodeError:
                continue
        else:
            text = raw.decode("utf-8", errors="replace")
    else:
        text = raw

    text = unicodedata.normalize("NFKC", text)
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = "\n".join(line.rstrip() for line in text.split("\n"))
    return text.strip() + ("\n" if text.strip() else "")


def _heading_title(match: re.Match[str]) -> str:
    title = match.group("title").strip()
    suffix = (match.group("suffix") or "").strip()
    return f"{title} {suffix}".strip()


def extract_chapters(
    text: str,
    source_path: str,
    *,
    include_preface: bool = True,
) -> list[Chapter]:
    """Split Markdown/TXT content on conservative chapter heading patterns.

    Files without a recognizable heading become a single chapter.  This is
    intentional: short stories often have no chapter marker and should still be
    usable as a deterministic one-chapter fixture.
    """

    normalized = normalize_text(text)
    lines = normalized.splitlines()
    matches = [
        (index, _heading_title(match))
        for index, line in enumerate(lines)
        if (match := HEADING_RE.match(line))
    ]

    if not matches:
        title = Path(source_path).stem.replace("_", " ").strip() or "未命名章节"
        return [Chapter(1, title, normalized, source_path)]

    chapters: list[Chapter] = []
    if include_preface and matches[0][0] > 0:
        preface = "\n".join(lines[: matches[0][0]]).strip()
        if preface:
            chapters.append(Chapter(1, "前置内容", preface + "\n", source_path))

    for position, (start, title) in enumerate(matches):
        end = matches[position + 1][0] if position + 1 < len(matches) else len(lines)
        body = "\n".join(lines[start + 1 : end]).strip()
        if not body:
            body = "（本章暂无正文）"
        chapters.append(Chapter(len(chapters) + 1, title, body + "\n", source_path))
    return chapters


def _run_gh(gh_args: list[str], *, gh_bin: str = "gh") -> str:
    command = [gh_bin, "api", *gh_args]
    try:
        completed = subprocess.run(
            command,
            check=True,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError as exc:
        raise CorpusImportError("未找到 gh CLI，请先安装并登录 GitHub CLI") from exc
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or exc.stdout).strip()
        raise CorpusImportError(f"GitHub API 请求失败：{detail}") from exc
    return completed.stdout


def resolve_commit(repo: str, ref: str, *, gh_bin: str = "gh") -> str:
    """Resolve a branch/tag/SHA to an immutable commit SHA."""

    payload = json.loads(_run_gh([f"repos/{repo}/commits/{ref}"], gh_bin=gh_bin))
    sha = payload.get("sha")
    if not isinstance(sha, str) or len(sha) < 7:
        raise CorpusImportError(f"无法解析 {repo}@{ref} 的 commit SHA")
    return sha


def list_paths(repo: str, ref: str, *, gh_bin: str = "gh") -> list[str]:
    payload = json.loads(
        _run_gh([f"repos/{repo}/git/trees/{ref}?recursive=1"], gh_bin=gh_bin)
    )
    if payload.get("truncated"):
        raise CorpusImportError(
            f"{repo}@{ref} 的 Git tree 被截断，请在 config 中显式列出 paths"
        )
    return [
        item["path"]
        for item in payload.get("tree", [])
        if item.get("type") == "blob"
        and Path(item.get("path", "")).suffix.lower() in SUPPORTED_EXTENSIONS
    ]


def fetch_file(repo: str, ref: str, path: str, *, gh_bin: str = "gh") -> bytes:
    endpoint = f"repos/{repo}/contents/{path}?ref={ref}"
    payload = json.loads(_run_gh([endpoint], gh_bin=gh_bin))
    if payload.get("encoding") != "base64" or not isinstance(payload.get("content"), str):
        raise CorpusImportError(f"无法读取文本文件：{repo}@{ref}:{path}")
    return base64.b64decode(payload["content"].replace("\n", ""))


def _safe_id(value: str) -> str:
    value = re.sub(r"[^\w.-]+", "-", value, flags=re.UNICODE).strip("-.")
    return value or "source"


def _sha256(data: bytes | str) -> str:
    if isinstance(data, str):
        data = data.encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def import_source(
    source: dict[str, Any],
    output_root: Path,
    *,
    gh_bin: str = "gh",
) -> dict[str, Any]:
    repo = str(source.get("repo", "")).strip()
    if "/" not in repo:
        raise CorpusImportError(f"source.repo 必须是 owner/name：{repo!r}")
    source_id = _safe_id(str(source.get("id") or repo.rsplit("/", 1)[-1]))
    mode = str(source.get("mode", "SHORT_STORY")).upper()
    if mode not in {"SHORT_STORY", "NOVEL"}:
        raise CorpusImportError(f"不支持的 content mode：{mode}")
    requested_ref = str(source.get("ref") or "main")
    commit = resolve_commit(repo, requested_ref, gh_bin=gh_bin)

    paths = [str(path) for path in source.get("paths", [])]
    if not paths:
        patterns = [str(pattern) for pattern in source.get("include", ["**/*.md", "**/*.txt"])]
        paths = [
            path
            for path in list_paths(repo, commit, gh_bin=gh_bin)
            if any(fnmatch.fnmatch(path, pattern) for pattern in patterns)
        ]
    if not paths:
        raise CorpusImportError(f"{repo}@{commit} 没有匹配的文本文件")

    source_root = output_root / mode.lower() / source_id
    chapter_root = source_root / "chapters"
    chapter_root.mkdir(parents=True, exist_ok=True)
    file_records: list[dict[str, Any]] = []
    chapter_records: list[dict[str, Any]] = []
    chapter_number = 0

    for path in paths:
        raw = fetch_file(repo, commit, path, gh_bin=gh_bin)
        normalized = normalize_text(raw)
        chapters = extract_chapters(normalized, path, include_preface=False)
        source_record = {
            "path": path,
            "sha256": _sha256(normalized),
            "bytes": len(raw),
            "characters": len(normalized),
            "chapterCount": len(chapters),
        }
        file_records.append(source_record)
        for chapter in chapters:
            chapter_number += 1
            output_path = chapter_root / f"{chapter_number:04d}.md"
            output_path.write_text(chapter.text, encoding="utf-8")
            chapter_records.append(
                {
                    "number": chapter_number,
                    "title": chapter.title,
                    "sourcePath": chapter.source_path,
                    "outputPath": str(output_path.relative_to(output_root)),
                    "sha256": _sha256(chapter.text),
                    "characters": len(chapter.text),
                }
            )

    return {
        "id": source_id,
        "repo": repo,
        "ref": requested_ref,
        "commit": commit,
        "mode": mode,
        "files": file_records,
        "chapters": chapter_records,
        "chapterCount": len(chapter_records),
        "characters": sum(item["characters"] for item in file_records),
    }


def import_config(
    config: dict[str, Any],
    output_root: Path,
    *,
    gh_bin: str = "gh",
) -> dict[str, Any]:
    sources = config.get("sources")
    if not isinstance(sources, list) or not sources:
        raise CorpusImportError("config.sources 必须是非空数组")
    output_root.mkdir(parents=True, exist_ok=True)
    imported = [import_source(source, output_root, gh_bin=gh_bin) for source in sources]
    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(UTC).isoformat(),
        "sources": imported,
        "sourceCount": len(imported),
        "chapterCount": sum(item["chapterCount"] for item in imported),
        "characters": sum(item["characters"] for item in imported),
    }


def write_manifest(manifest: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--gh-bin", default="gh")
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    config = json.loads(args.config.read_text(encoding="utf-8"))
    manifest = import_config(config, args.output, gh_bin=args.gh_bin)
    write_manifest(manifest, args.manifest)
    print(
        f"Imported {manifest['sourceCount']} sources, "
        f"{manifest['chapterCount']} chapters, "
        f"{manifest['characters']} characters"
    )
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised by the CLI
    raise SystemExit(main())
