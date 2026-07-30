"""Shared bookkeeping helpers for workflow agent nodes."""

from __future__ import annotations

from time import perf_counter
from typing import Any, TypeVar

from pydantic import BaseModel

from app.infrastructure.llm_factory import (
    StructuredGeneration,
    StructuredModel,
    WorkflowModelError,
)
from app.prompts import PROMPT_VERSION
from app.schemas.workflow import (
    ArtifactRecord,
    ModelCallRecord,
    ProgressEvent,
)

SchemaT = TypeVar("SchemaT", bound=BaseModel)


class WorkflowInvocationError(WorkflowModelError):
    """Model failure carrying a persistable failed-call record."""

    def __init__(self, message: str, call: dict[str, Any]) -> None:
        super().__init__(message)
        self.call = call


async def invoke_structured(
    model: StructuredModel,
    schema: type[SchemaT],
    *,
    node: str,
    prompt_name: str,
    prompt: str,
    payload: dict[str, Any],
    purpose: str,
) -> tuple[StructuredGeneration[SchemaT], dict[str, Any]]:
    started = perf_counter()
    try:
        result = await model.generate(
            schema,
            system_prompt=prompt,
            payload=payload,
            purpose=purpose,
        )
    except Exception as exc:
        duration_ms = round((perf_counter() - started) * 1000)
        failed_call = ModelCallRecord(
            node=node,
            model_name=model.model_name,
            prompt_version=f"{prompt_name}_{PROMPT_VERSION}",
            duration_ms=duration_ms,
            success=False,
            error=str(exc)[:1000],
        ).model_dump(mode="json")
        raise WorkflowInvocationError(
            f"{node}模型调用失败：{exc}",
            failed_call,
        ) from exc
    duration_ms = round((perf_counter() - started) * 1000)
    call = ModelCallRecord(
        node=node,
        model_name=result.model_name,
        prompt_version=f"{prompt_name}_{PROMPT_VERSION}",
        input_tokens=result.input_tokens,
        output_tokens=result.output_tokens,
        duration_ms=duration_ms,
        success=True,
    )
    return result, call.model_dump(mode="json")


def progress(
    node: str,
    message: str,
    *,
    status: str = "completed",
    revision_no: int | None = None,
) -> dict[str, Any]:
    return ProgressEvent(
        node=node,
        status=status,
        message=message,
        revision_no=revision_no,
    ).model_dump(mode="json")


def artifact(
    *,
    artifact_type: str,
    version_no: int,
    status: str,
    content: dict[str, Any],
    prompt_name: str,
    model_name: str,
) -> dict[str, Any]:
    return ArtifactRecord.model_validate(
        {
            "artifact_type": artifact_type,
            "version_no": version_no,
            "status": status,
            "content": content,
            "prompt_version": f"{prompt_name}_{PROMPT_VERSION}",
            "model_name": model_name,
        }
    ).model_dump(mode="json")
