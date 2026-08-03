from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("import_github_corpus.py")
SPEC = importlib.util.spec_from_file_location("import_github_corpus_edge", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

FIXTURES = Path(__file__).parent / "fixtures" / "edge-cases"


def test_headingless_short_fixture_is_a_single_chapter() -> None:
    text = (FIXTURES / "headingless-short.txt").read_text(encoding="utf-8")
    chapters = MODULE.extract_chapters(text, "headingless-short.txt")
    assert len(chapters) == 1
    assert chapters[0].title == "headingless-short"


def test_empty_chapter_fixture_gets_explicit_placeholder() -> None:
    text = (FIXTURES / "empty-chapter.md").read_text(encoding="utf-8")
    chapters = MODULE.extract_chapters(text, "empty-chapter.md", include_preface=False)
    assert [chapter.title for chapter in chapters] == ["第1章 空章", "第2章 有内容"]
    assert chapters[0].text == "（本章暂无正文）\n"
    assert "仍然有正文" in chapters[1].text


def test_duplicate_heading_fixture_preserves_both_chapters() -> None:
    text = (FIXTURES / "duplicate-chapter.md").read_text(encoding="utf-8")
    chapters = MODULE.extract_chapters(text, "duplicate-chapter.md", include_preface=False)
    assert len(chapters) == 2
    assert [chapter.title for chapter in chapters] == ["第1章 相同标题", "第1章 相同标题"]
    assert "第一份正文" in chapters[0].text
    assert "第二份正文" in chapters[1].text


def test_prefix_fixture_can_be_kept_or_excluded_explicitly() -> None:
    text = (FIXTURES / "missing-heading-prefix.md").read_text(encoding="utf-8")
    with_preface = MODULE.extract_chapters(text, "missing-heading-prefix.md")
    without_preface = MODULE.extract_chapters(
        text, "missing-heading-prefix.md", include_preface=False
    )
    assert with_preface[0].title == "前置内容"
    assert len(with_preface) == 2
    assert len(without_preface) == 1
    assert without_preface[0].title == "第1章 正式开始"


def test_long_chapter_is_preserved_without_truncation() -> None:
    text = "第1章 超长章节\n" + ("长文本" * 40_000)
    chapters = MODULE.extract_chapters(text, "long.md", include_preface=False)
    assert len(chapters) == 1
    assert len(chapters[0].text) == 120_001


def test_gb18030_text_is_decoded() -> None:
    raw = "第1章 编码异常\n中文正文".encode("gb18030")
    chapters = MODULE.extract_chapters(raw, "gb18030.txt", include_preface=False)
    assert chapters[0].title == "第1章 编码异常"
    assert chapters[0].text == "中文正文\n"
