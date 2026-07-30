"""Shared helpers for chapter agents and deterministic validation."""

from __future__ import annotations

from time import perf_counter
from typing import Any

from app.chapter_events import emit_chapter_event
from app.infrastructure.llm_factory import TextModel
from app.prompts import PROMPT_VERSION
from app.schemas.workflow import ModelCallRecord


def chapter_progress(
    node: str,
    message: str,
    *,
    status: str = "completed",
    revision_no: int | None = None,
) -> dict[str, Any]:
    from app.schemas.workflow import ProgressEvent

    return ProgressEvent(
        node=node,
        status=status,
        message=message,
        revision_no=revision_no,
    ).model_dump(mode="json")


def chapter_artifact(
    artifact_type: str,
    *,
    version_no: int,
    status: str,
    content: dict[str, Any],
    prompt_name: str,
    model_name: str,
) -> dict[str, Any]:
    from datetime import UTC, datetime

    return {
        "artifactType": artifact_type,
        "versionNo": version_no,
        "status": status,
        "content": content,
        "promptVersion": f"{prompt_name}_{PROMPT_VERSION}",
        "modelName": model_name,
        "createdAt": datetime.now(UTC).isoformat(),
    }


async def invoke_text(
    model: TextModel,
    *,
    node: str,
    prompt_name: str,
    prompt: str,
    payload: dict[str, Any],
    purpose: str,
    stream_event_type: str = "TOKEN_DELTA",
) -> tuple[str, dict[str, Any]]:
    """Collect ordinary prose while forwarding every model delta immediately."""

    started = perf_counter()
    chunks: list[str] = []
    input_tokens = 0
    output_tokens = 0
    returned_model = model.model_name
    try:
        async for delta in model.stream_text(
            system_prompt=prompt,
            payload=payload,
            purpose=purpose,
        ):
            returned_model = delta.model_name
            if delta.text:
                chunks.append(delta.text)
                await emit_chapter_event(
                    stream_event_type,
                    {"text": delta.text, "phase": purpose},
                )
            if delta.done:
                input_tokens = delta.input_tokens
                output_tokens = delta.output_tokens
    except Exception as exc:
        duration_ms = round((perf_counter() - started) * 1000)
        call = ModelCallRecord(
            node=node,
            model_name=returned_model,
            prompt_version=f"{prompt_name}_{PROMPT_VERSION}",
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            duration_ms=duration_ms,
            success=False,
            error=str(exc)[:1000],
        ).model_dump(mode="json")
        from app.agents.workflow_utils import WorkflowInvocationError

        raise WorkflowInvocationError(f"{node}模型调用失败：{exc}", call) from exc

    content = "".join(chunks).strip()
    if not content:
        raise ValueError("章节正文为空")
    duration_ms = round((perf_counter() - started) * 1000)
    call = ModelCallRecord(
        node=node,
        model_name=returned_model,
        prompt_version=f"{prompt_name}_{PROMPT_VERSION}",
        input_tokens=input_tokens,
        output_tokens=output_tokens or max(1, len(content) // 4),
        duration_ms=duration_ms,
        success=True,
    ).model_dump(mode="json")
    return content, call


def validate_chapter_content(
    content: str,
    plan: dict[str, Any],
    known_characters: set[str],
) -> list[str]:
    """Apply only mechanical checks that are deterministic in application code."""

    del known_characters  # Reserved for a future audited name-entity detector.
    errors: list[str] = []
    target = int(plan.get("target_length") or plan.get("targetLength") or 1200)
    actual = len(content)
    if actual < target * 0.75:
        errors.append("章节长度明显不足")
    if actual > target * 1.30:
        errors.append("章节长度明显超限")
    if not content.strip():
        errors.append("章节正文为空")
    paragraphs = [part for part in content.splitlines() if part.strip()]
    if len(paragraphs) < 5:
        errors.append("正文段落结构异常")

    normalized = ["".join(paragraph.split()) for paragraph in paragraphs]
    if len(normalized) != len(set(normalized)):
        errors.append("正文包含完全重复段落")
    return errors
