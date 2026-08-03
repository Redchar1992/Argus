"""Run the deterministic local Story Forge workflow against corpus metadata.

This intentionally does not send manuscript text to a model.  It verifies that
the selected short-story and novel profiles can start the existing workflow,
produce the expected outline shape, and expose only structured telemetry in the
report.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


async def run(
    manifest: dict[str, Any],
    ai_service_root: Path | None = None,
) -> dict[str, Any]:
    ai_root = ai_service_root or (Path(__file__).parents[2] / "ai-service")
    if not (ai_root / "app").is_dir():
        raise RuntimeError(
            f"找不到 Story Forge AI 服务，请通过 --ai-service-root 指定：{ai_root}"
        )
    sys.path.insert(0, str(ai_root))
    from app.agents.topic_agent import local_topic_agent
    from app.models import TopicGenerateRequest
    from app.schemas.workflow import WorkflowStartRequest, WorkflowStatus
    from app.workflow.service import StoryWorkflowService

    service = StoryWorkflowService()
    topic_agent = local_topic_agent()
    results: list[dict[str, Any]] = []
    failures: list[str] = []
    for index, source in enumerate(manifest.get("sources", []), start=1):
        mode = str(source.get("mode", "SHORT_STORY"))
        chapter_count = int(source.get("chapterCount", 1))
        target_chapters = max(1, chapter_count)
        target_words = max(1_000, min(2_000_000, target_chapters * 2_500))
        topic_response = await topic_agent.generate(
            TopicGenerateRequest(
                genre="都市情感" if mode == "SHORT_STORY" else "现代言情长篇",
                audience="女性读者",
                keywords=["回归测试", source.get("id", "固定样本")],
                content_mode=mode,
            )
        )
        selected_topic = topic_response.topics[0].model_dump(by_alias=True)
        request = WorkflowStartRequest(
            task_id=f"corpus-regression-{index}",
            story_id=10_000 + index,
            topic=selected_topic,
            max_revisions=0,
            content_mode=mode,
            target_chapter_count=target_chapters,
            target_total_words=target_words,
            chapter_target_words=2_000 if mode == "NOVEL" else 1_200,
        )
        started = time.perf_counter()
        try:
            response = await service.start(request)
            expected_nodes = target_chapters * 2 if mode == "NOVEL" else 20
            passed = (
                response.status is WorkflowStatus.REVIEW_REQUIRED
                and response.content_mode == mode
                and len(response.outline) == expected_nodes
                and [node.node_no for node in response.outline]
                == list(range(1, expected_nodes + 1))
                and all(call.model_name == "local-workflow-template" for call in response.model_calls)
            )
            if not passed:
                failures.append(str(source.get("id")))
            results.append(
                {
                    "id": source.get("id"),
                    "mode": mode,
                    "status": "PASS" if passed else "FAIL",
                    "workflowStatus": response.status.value,
                    "inputChapterCount": chapter_count,
                    "targetChapterCount": target_chapters,
                    "topicCount": len(topic_response.topics),
                    "topicModel": topic_response.model,
                    "topicScore": selected_topic["score"],
                    "selectedTopicTitle": selected_topic["title"],
                    "outlineNodeCount": len(response.outline),
                    "expectedOutlineNodeCount": expected_nodes,
                    "characterCount": len(response.characters),
                    "score": response.score.total if response.score else None,
                    "modelNames": sorted({call.model_name for call in response.model_calls}),
                    "modelCallCount": len(response.model_calls),
                    "durationMs": round((time.perf_counter() - started) * 1000),
                }
            )
        except Exception as exc:  # noqa: BLE001 - regression must report all failures
            failures.append(str(source.get("id")))
            results.append(
                {
                    "id": source.get("id"),
                    "mode": mode,
                    "status": "FAIL",
                    "error": str(exc)[:1000],
                    "durationMs": round((time.perf_counter() - started) * 1000),
                }
            )

    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(UTC).isoformat(),
        "provider": "local-workflow-template",
        "manuscriptTextSent": False,
        "status": "PASS" if not failures else "FAIL",
        "results": results,
        "failedSources": failures,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--ai-service-root", type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    report = asyncio.run(run(manifest, args.ai_service_root))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Local AI corpus regression: {report['status']}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
