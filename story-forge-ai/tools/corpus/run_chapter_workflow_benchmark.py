"""Run a synthetic 120-chapter PLAN → GENERATE → APPROVE benchmark.

This exercises the third-week chapter graph with the deterministic local model.
Each chapter receives exactly two outline nodes and a bounded memory packet; the
benchmark records only status, counts, timings and memory telemetry.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
import tracemalloc
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

DEFAULT_CHAPTERS = 120


def _characters() -> list[dict[str, Any]]:
    return [
        {"name": "林晚", "role": "主角"},
        {"name": "顾承泽", "role": "反派"},
        {"name": "苏晴", "role": "盟友"},
    ]


def _outline_node(number: int) -> dict[str, Any]:
    return {
        "nodeNo": number,
        "stage": "发展" if number < 120 else "高潮",
        "event": f"主角推进第{number}个合成事件",
        "conflict": "对手试图阻止证据核验",
        "protagonistGoal": "保全证据并查清真相",
        "emotionalTarget": "紧张与期待",
        "newInformation": f"出现第{number}条可验证线索",
        "cliffhanger": "线索指向更深利益关系",
        "isTwist": number % 8 == 0,
        "setupOrPayoff": "承接并回收长期伏笔",
    }


def _context(chapter_count: int) -> dict[str, Any]:
    return {
        "storyTitle": "120章合成长篇",
        "genre": "现代言情",
        "targetAudience": "女性读者",
        "contentMode": "NOVEL",
        "targetChapterCount": chapter_count,
        "targetTotalWords": chapter_count * 2_500,
        "chapterTargetWords": 800,
        "targetLength": 800,
        "viewpoint": "THIRD_LIMITED",
        "styleProfile": {"tone": "克制", "pacing": "紧凑"},
        "characters": _characters(),
        "canonFacts": [{"factKey": "story-premise", "value": "合成基准"}],
        "relationshipStates": [],
        "recentSummaries": [],
        "unresolvedThreads": [],
        "foreshadowingLedger": [],
    }


async def run(ai_service_root: Path, chapter_count: int) -> dict[str, Any]:
    sys.path.insert(0, str(ai_service_root))
    from app.schemas.chapter import ChapterCommand, ChapterRunStatus
    from app.workflow.chapter_graph import build_chapter_graph
    from app.workflow.chapter_service import ChapterWorkflowService

    service = ChapterWorkflowService(build_chapter_graph())
    context = _context(chapter_count)
    outline = [_outline_node(number) for number in range(1, chapter_count * 2 + 1)]
    started = time.perf_counter()
    tracemalloc.start()
    completed_count = 0
    plan_count = 0
    generated_count = 0
    failure: dict[str, Any] | None = None

    for chapter_no in range(1, chapter_count + 1):
        chapter_id = 120_000 + chapter_no
        current_nodes = outline[(chapter_no - 1) * 2 : chapter_no * 2]
        chapter_context = {
            **context,
            "outlineNodes": current_nodes,
            "currentOutlineNodes": current_nodes,
        }
        plan_thread = f"chapter-benchmark-plan-{chapter_no}"
        generate_thread = f"chapter-benchmark-generate-{chapter_no}"
        try:
            planned = await service.start(
                ChapterCommand(
                    taskId=f"chapter-plan-{chapter_no}",
                    storyId=99_120,
                    chapterId=chapter_id,
                    chapterNo=chapter_no,
                    action="PLAN",
                    threadId=plan_thread,
                    idempotencyKey=f"benchmark-plan-{chapter_no}",
                    payload=chapter_context,
                )
            )
            if planned.status is not ChapterRunStatus.PLAN_READY or not planned.chapter_plan:
                raise RuntimeError(f"第{chapter_no}章计划状态异常：{planned.status}")
            plan_count += 1

            generated = await service.start(
                ChapterCommand(
                    taskId=f"chapter-generate-{chapter_no}",
                    storyId=99_120,
                    chapterId=chapter_id,
                    chapterNo=chapter_no,
                    action="GENERATE",
                    threadId=generate_thread,
                    idempotencyKey=f"benchmark-generate-{chapter_no}",
                    payload={
                        **chapter_context,
                        "chapterPlan": planned.chapter_plan.model_dump(
                            mode="json", by_alias=True
                        ),
                    },
                )
            )
            if generated.status is not ChapterRunStatus.REVIEW_REQUIRED:
                raise RuntimeError(f"第{chapter_no}章未进入审核：{generated.status}")
            generated_count += 1

            completed = await service.finalize(
                ChapterCommand(
                    taskId=f"chapter-approve-{chapter_no}",
                    storyId=99_120,
                    chapterId=chapter_id,
                    chapterNo=chapter_no,
                    action="FINALIZE",
                    threadId=generate_thread,
                    idempotencyKey=f"benchmark-approve-{chapter_no}",
                    payload={
                        "approved": True,
                        "notes": "",
                        "currentContent": generated.draft_content,
                    },
                )
            )
            if (
                completed.status is not ChapterRunStatus.COMPLETED
                or not completed.final_content
                or completed.chapter_summary is None
                or completed.memory_update is None
            ):
                raise RuntimeError(f"第{chapter_no}章完成状态异常：{completed.status}")
            completed_count += 1

            summary = completed.chapter_summary.model_dump(mode="json", by_alias=True)
            memory = completed.memory_update.model_dump(mode="json", by_alias=True)
            context["recentSummaries"] = [
                *context["recentSummaries"],
                summary,
            ][-5:]
            context["canonFacts"] = [
                *context["canonFacts"],
                *memory.get("newFacts", []),
            ]
            context["relationshipStates"] = memory.get(
                "changedRelationships", context["relationshipStates"]
            )
            context["unresolvedThreads"] = memory.get(
                "openedThreads", context["unresolvedThreads"]
            )
            context["foreshadowingLedger"] = memory.get(
                "newForeshadowing", context["foreshadowingLedger"]
            )
        except Exception as exc:  # noqa: BLE001 - benchmark records chapter failures
            failure = {"chapterNo": chapter_no, "error": str(exc)[:1000]}
            break

    _current, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    duration_ms = round((time.perf_counter() - started) * 1000)
    passed = (
        failure is None
        and completed_count == chapter_count
        and plan_count == chapter_count
        and generated_count == chapter_count
        and duration_ms <= 120_000
    )
    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(UTC).isoformat(),
        "status": "PASS" if passed else "FAIL",
        "synthetic": True,
        "manuscriptTextSent": False,
        "chapterCount": chapter_count,
        "planCount": plan_count,
        "generatedCount": generated_count,
        "completedCount": completed_count,
        "durationMs": duration_ms,
        "perChapterMs": round(duration_ms / max(1, completed_count), 2),
        "peakMiB": round(peak / 1024 / 1024, 2),
        "failure": failure,
        "provider": "local-workflow-template",
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
    print(f"Chapter workflow benchmark: {report['status']} ({args.chapter_count} chapters)")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
