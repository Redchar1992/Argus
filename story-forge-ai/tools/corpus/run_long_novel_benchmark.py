"""Benchmark parser, bounded memory context, and local workflow at 120 chapters.

The benchmark uses synthetic chapter text and never reads or uploads the author
corpus.  It is intended to catch accidental O(N) context replay or outline-size
regressions before a real long novel is enabled for internal testing.
"""

from __future__ import annotations

import argparse
import asyncio
import importlib.util
import json
import sys
import time
import tracemalloc
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

DEFAULT_CHAPTERS = 120


def _load_importer() -> Any:
    module_path = Path(__file__).with_name("import_github_corpus.py")
    spec = importlib.util.spec_from_file_location("corpus_importer_benchmark", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError("无法加载语料导入器")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def benchmark_parser(importer: Any, chapter_count: int) -> dict[str, Any]:
    text = "\n\n".join(
        f"第{number}章 合成章节\n主角推进第{number}个事件，留下下一章的线索。"
        for number in range(1, chapter_count + 1)
    )
    tracemalloc.start()
    started = time.perf_counter()
    chapters = importer.extract_chapters(text, "synthetic-120-chapter.md", include_preface=False)
    duration_ms = round((time.perf_counter() - started) * 1000)
    _current, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    passed = (
        len(chapters) == chapter_count
        and chapters[0].title == "第1章 合成章节"
        and chapters[-1].title == f"第{chapter_count}章 合成章节"
        and duration_ms <= 10_000
    )
    return {
        "status": "PASS" if passed else "FAIL",
        "chapterCount": len(chapters),
        "characters": sum(len(chapter.text) for chapter in chapters),
        "durationMs": duration_ms,
        "peakKiB": round(peak / 1024, 1),
    }


async def benchmark_ai(ai_service_root: Path, chapter_count: int) -> dict[str, Any]:
    sys.path.insert(0, str(ai_service_root))
    from app.agents.chapter_context import ChapterContextAssembler
    from app.agents.final_review_agent import FinalReviewAgent
    from app.agents.topic_agent import local_topic_agent
    from app.infrastructure.llm_factory import LocalStructuredModel
    from app.models import TopicGenerateRequest
    from app.schemas.final_review import FinalReviewRequest
    from app.schemas.workflow import WorkflowStartRequest, WorkflowStatus
    from app.workflow.service import StoryWorkflowService

    context_state = {
        "content_mode": "NOVEL",
        "characters": [
            {
                "name": "林晚",
                "role": "主角",
                "public_identity": "调查记者",
                "hidden_secret": "掌握关键证据",
                "core_desire": "查清真相",
                "greatest_fear": "误伤无辜",
                "personality": ["克制"],
                "relationship_to_protagonist": "本人",
                "character_arc": "从被动到主动",
            }
        ],
        "current_outline_nodes": [
            {
                "node_no": 1,
                "event": "公开冲突发生",
                "conflict": "对手试图销毁证据",
                "protagonist_goal": "保全证据",
                "emotional_target": "紧张",
                "new_information": "出现异常记录",
                "cliffhanger": "记录指向幕后人",
                "is_twist": False,
                "setup_or_payoff": "埋下证据线",
            },
            {
                "node_no": 2,
                "event": "主角锁定新的利益关系",
                "conflict": "盟友身份出现疑点",
                "protagonist_goal": "验证线索",
                "emotional_target": "期待",
                "new_information": "发现第二份证据",
                "cliffhanger": "手机突然亮起",
                "is_twist": True,
                "setup_or_payoff": "回收前置线索",
            },
        ],
        "canon_facts": [{"factKey": f"fact-{i}", "value": "locked"} for i in range(120)],
        "relationship_states": [{"character": "林晚", "state": str(i)} for i in range(120)],
        "recent_summaries": [{"chapterNo": i, "summary": "近期摘要"} for i in range(1, 10)],
        "unresolved_threads": [{"threadKey": f"thread-{i}"} for i in range(120)],
        "foreshadowing_ledger": [{"foreshadowKey": f"foreshadow-{i}"} for i in range(120)],
    }
    started = time.perf_counter()
    context = ChapterContextAssembler()(context_state)
    context_duration_ms = round((time.perf_counter() - started) * 1000)
    packet = context["context_packet"]
    context_passed = (
        len(json.dumps(packet, ensure_ascii=False)) <= 40_000
        and len(packet["recentSummaries"]) == 3
        and len(packet["currentOutlineNodes"]) == 2
        and bool(packet.get("contextOmitted"))
        and len(packet["contextSnapshotHash"]) == 64
    )

    topic_started = time.perf_counter()
    topic = await local_topic_agent().generate(
        TopicGenerateRequest(
            genre="现代言情长篇",
            audience="女性读者",
            keywords=["120章性能基准"],
            content_mode="NOVEL",
        )
    )
    selected = topic.topics[0].model_dump(by_alias=True)
    workflow = await StoryWorkflowService().start(
        WorkflowStartRequest(
            task_id="long-novel-benchmark",
            story_id=99_120,
            topic=selected,
            max_revisions=0,
            content_mode="NOVEL",
            target_chapter_count=chapter_count,
            target_total_words=chapter_count * 2_500,
            chapter_target_words=2_000,
        )
    )
    workflow_duration_ms = round((time.perf_counter() - topic_started) * 1000)
    expected_nodes = chapter_count * 2
    workflow_passed = (
        workflow.status is WorkflowStatus.REVIEW_REQUIRED
        and len(workflow.outline) == expected_nodes
        and [node.node_no for node in workflow.outline]
        == list(range(1, expected_nodes + 1))
        and workflow_duration_ms <= 30_000
    )

    review_started = time.perf_counter()
    final_report = await FinalReviewAgent(model=LocalStructuredModel()).review(
        FinalReviewRequest(
            story_title="120章合成长篇",
            genre="现代言情",
            target_audience="女性读者",
            content_mode="NOVEL",
            chapters=[
                {
                    "chapterNo": number,
                    "title": f"第{number}章",
                    "content": "合成章节正文，用于验证终审 schema 和长篇章节上限。",
                }
                for number in range(1, chapter_count + 1)
            ],
        )
    )
    review_duration_ms = round((time.perf_counter() - review_started) * 1000)
    review_passed = (
        final_report.novel_adaptation is not None
        and review_duration_ms <= 30_000
    )
    return {
        "context": {
            "status": "PASS" if context_passed else "FAIL",
            "packetCharacters": len(json.dumps(packet, ensure_ascii=False)),
            "omittedFields": packet.get("contextOmitted", {}),
            "durationMs": context_duration_ms,
        },
        "workflow": {
            "status": "PASS" if workflow_passed else "FAIL",
            "workflowStatus": workflow.status.value,
            "outlineNodeCount": len(workflow.outline),
            "expectedOutlineNodeCount": expected_nodes,
            "durationMs": workflow_duration_ms,
            "provider": "local-workflow-template",
        },
        "finalReview": {
            "status": "PASS" if review_passed else "FAIL",
            "chapterCount": chapter_count,
            "novelAdaptationScore": final_report.novel_adaptation.score if final_report.novel_adaptation else None,
            "durationMs": review_duration_ms,
            "provider": "local-workflow-template",
        },
    }


async def run(ai_service_root: Path, chapter_count: int) -> dict[str, Any]:
    importer = _load_importer()
    parser_result = benchmark_parser(importer, chapter_count)
    ai_result = await benchmark_ai(ai_service_root, chapter_count)
    sections = [parser_result, *ai_result.values()]
    status = "PASS" if all(item["status"] == "PASS" for item in sections) else "FAIL"
    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(UTC).isoformat(),
        "status": status,
        "synthetic": True,
        "manuscriptTextSent": False,
        "chapterCount": chapter_count,
        "parser": parser_result,
        **ai_result,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ai-service-root", type=Path, required=True)
    parser.add_argument("--chapter-count", type=int, default=DEFAULT_CHAPTERS)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.chapter_count < 100 or args.chapter_count > 200:
        parser.error("--chapter-count 必须在 100 到 200 之间")
    report = asyncio.run(run(args.ai_service_root, args.chapter_count))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Long novel benchmark: {report['status']} ({args.chapter_count} chapters)")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
