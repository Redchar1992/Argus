from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("import_github_corpus.py")
SPEC = importlib.util.spec_from_file_location("import_github_corpus", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def test_normalize_text_handles_bom_newlines_and_trailing_spaces() -> None:
    raw = b"\xef\xbb\xbf\xe7\xac\xac\xe4\xb8\x80\xe7\xab\xa0  \r\n\r\n\xe6\x95\x85\xe4\xba\x8b\r"
    assert MODULE.normalize_text(raw) == "第一章\n\n故事\n"


def test_extract_chapters_splits_numbered_headings_and_preserves_preface() -> None:
    text = "简介\n\n# 第一章 初见\n甲\n\n## 第二章 冲突\n乙\n"
    chapters = MODULE.extract_chapters(text, "novel.md")
    assert [chapter.title for chapter in chapters] == ["前置内容", "第一章 初见", "第二章 冲突"]
    assert chapters[0].text == "简介\n"
    assert chapters[-1].text == "乙\n"


def test_extract_chapters_treats_headingless_short_story_as_one_chapter() -> None:
    chapters = MODULE.extract_chapters("这是一个没有分章标题的短篇。\n", "short.md")
    assert len(chapters) == 1
    assert chapters[0].title == "short"
    assert chapters[0].text == "这是一个没有分章标题的短篇。\n"


def test_extract_chapters_handles_empty_chapter_body() -> None:
    chapters = MODULE.extract_chapters("第1章\n第2章\n正文", "empty.md")
    assert [chapter.text for chapter in chapters] == ["（本章暂无正文）\n", "正文\n"]
