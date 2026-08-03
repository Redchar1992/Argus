"""Run an opt-in, schema-only OpenAI-compatible model smoke test.

The default is a no-op report.  A real request requires both
``--confirm-remote`` and ``STORY_FORGE_REMOTE_SMOKE_CONFIRM=I_UNDERSTAND``.
The payload contains a synthetic topic only; no imported manuscript text is
ever sent by this script.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

CONFIRMATION = "I_UNDERSTAND"


def _base_report() -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(UTC).isoformat(),
        "manuscriptTextSent": False,
        "promptVersion": "remote_smoke_v1",
    }


def skipped_report(reason: str) -> dict[str, Any]:
    report = _base_report()
    report.update({"status": "SKIPPED", "reason": reason})
    return report


def _settings_or_skip(ai_service_root: Path) -> tuple[Any | None, dict[str, Any] | None]:
    sys.path.insert(0, str(ai_service_root))
    from app.config import Settings

    try:
        settings = Settings.from_env()
    except RuntimeError as exc:
        return None, skipped_report(f"运行配置不满足远程模型约束：{exc}")

    provider = settings.model_provider
    if provider == "local":
        return None, skipped_report("MODEL_PROVIDER=local，不执行远程模型请求")
    if provider == "ollama":
        return None, skipped_report("Ollama 属于本地模型，不纳入远程 smoke test")
    if not settings.openai_api_key:
        return None, skipped_report("未配置 OPENAI_API_KEY")

    endpoint = urlparse(settings.openai_base_url)
    if endpoint.scheme != "https" or not endpoint.hostname:
        return None, skipped_report("远程 smoke test 要求 HTTPS 的 OPENAI_BASE_URL")
    return settings, None


async def run(
    *,
    ai_service_root: Path,
    confirm_remote: bool,
) -> dict[str, Any]:
    if not confirm_remote:
        return skipped_report("未提供 --confirm-remote，默认不触发付费请求")
    if os.getenv("STORY_FORGE_REMOTE_SMOKE_CONFIRM") != CONFIRMATION:
        return skipped_report(
            "缺少 STORY_FORGE_REMOTE_SMOKE_CONFIRM=I_UNDERSTAND，默认不触发付费请求"
        )

    settings, skipped = _settings_or_skip(ai_service_root)
    if skipped is not None:
        return skipped

    from app.infrastructure.llm_factory import (
        OpenAICompatibleStructuredModel,
    )
    from app.schemas.character import CharacterPack

    model = OpenAICompatibleStructuredModel(
        api_key=settings.openai_api_key,
        base_url=settings.openai_base_url,
        model=settings.openai_creative_model,
        temperature=0.1,
        timeout_seconds=min(settings.openai_timeout_seconds, 45.0),
    )
    started = time.perf_counter()
    try:
        result = await model.generate(
            CharacterPack,
            system_prompt=(
                "你是 Story Forge 的接口 smoke test。只返回符合 JSON Schema 的人物卡，"
                "不要输出解释，不要引用任何外部作品。"
            ),
            payload={
                "topic": {
                    "title": "远程接口合约测试",
                    "hook": "合成冲突用于验证结构化 JSON 返回能力。",
                },
                "contentMode": "SHORT_STORY",
            },
            purpose="remote_smoke",
        )
    except Exception as exc:  # noqa: BLE001 - report remote failures without secrets
        report = _base_report()
        report.update(
            {
                "status": "FAIL",
                "provider": settings.model_provider,
                "model": settings.openai_creative_model,
                "error": str(exc)[:1000],
                "durationMs": round((time.perf_counter() - started) * 1000),
            }
        )
        return report

    report = _base_report()
    report.update(
        {
            "status": "PASS",
            "provider": settings.model_provider,
            "model": result.model_name,
            "endpointHost": urlparse(settings.openai_base_url).hostname,
            "characterCount": len(result.value.characters),
            "inputTokens": result.input_tokens,
            "outputTokens": result.output_tokens,
            "durationMs": round((time.perf_counter() - started) * 1000),
        }
    )
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ai-service-root", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--confirm-remote", action="store_true")
    args = parser.parse_args()

    report = asyncio.run(
        run(
            ai_service_root=args.ai_service_root,
            confirm_remote=args.confirm_remote,
        )
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Remote model smoke test: {report['status']} ({report.get('reason', '')})")
    return 0 if report["status"] in {"PASS", "SKIPPED"} else 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
