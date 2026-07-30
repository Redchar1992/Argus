"""HTTP, event, artifact, and queue schemas for the story workflow."""

from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum
from typing import Any, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
    model_validator,
)

from app.schemas.character import CharacterCard
from app.schemas.outline import OutlineNode
from app.schemas.score import StoryScoreResult


def utc_now() -> datetime:
    return datetime.now(UTC)


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=lambda value: "".join(
            word if index == 0 else word.title()
            for index, word in enumerate(value.split("_"))
        ),
        populate_by_name=True,
        extra="forbid",
    )


class WorkflowStatus(StrEnum):
    RUNNING = "RUNNING"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class SelectedTopic(CamelModel):
    # Week-one topic responses contain scoreReasons and may gain additional
    # explainability fields over time. The workflow only needs the creative
    # fields, so forwards-compatible extras must not reject a valid selection.
    model_config = ConfigDict(extra="ignore")

    id: int | str | None = None
    title: str = Field(min_length=2, max_length=120)
    hook: str = Field(min_length=2, max_length=240)
    summary: str = Field(default="", max_length=600)
    score: int | None = Field(default=None, ge=0, le=100)
    score_reasons: dict[str, Any] | None = None
    tags: list[str] = Field(default_factory=list, max_length=10)


class ProgressEvent(CamelModel):
    node: str = Field(min_length=1, max_length=64)
    status: str = Field(min_length=1, max_length=32)
    message: str = Field(min_length=1, max_length=240)
    occurred_at: datetime = Field(default_factory=utc_now)
    revision_no: int | None = Field(default=None, ge=0)


class ArtifactRecord(CamelModel):
    artifact_type: Literal["CHARACTER", "OUTLINE", "SCORE", "WORKFLOW_FINAL"]
    version_no: int = Field(ge=1)
    status: Literal["DRAFT", "REVIEW_REQUIRED", "APPROVED"]
    content: dict[str, Any]
    prompt_version: str = Field(min_length=1, max_length=32)
    model_name: str = Field(min_length=1, max_length=120)
    created_at: datetime = Field(default_factory=utc_now)


class ModelCallRecord(CamelModel):
    node: str
    model_name: str
    prompt_version: str
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    duration_ms: int = Field(ge=0)
    success: bool
    error: str | None = None


class WorkflowStartRequest(CamelModel):
    task_id: str = Field(min_length=1, max_length=128)
    story_id: int = Field(ge=1)
    topic: SelectedTopic
    thread_id: str | None = Field(default=None, min_length=1, max_length=128)
    max_revisions: int = Field(default=2, ge=0, le=2)


class ReviewDecision(CamelModel):
    approved: bool
    notes: str = Field(default="", max_length=2000)

    @model_validator(mode="after")
    def require_notes_for_revision(self) -> ReviewDecision:
        if not self.approved and not self.notes.strip():
            raise ValueError("拒绝大纲时必须填写具体修改意见")
        return self


class WorkflowResumeRequest(ReviewDecision):
    pass


class WorkflowRunResponse(CamelModel):
    thread_id: str
    task_id: str
    story_id: int
    status: WorkflowStatus
    current_node: str
    revision_count: int = Field(ge=0)
    max_revisions: int = Field(ge=0, le=2)
    characters: list[CharacterCard] = Field(default_factory=list)
    outline: list[OutlineNode] = Field(default_factory=list)
    score: StoryScoreResult | None = None
    approved: bool = False
    progress_events: list[ProgressEvent] = Field(default_factory=list)
    artifacts: list[ArtifactRecord] = Field(default_factory=list)
    model_calls: list[ModelCallRecord] = Field(default_factory=list)
    interrupt: dict[str, Any] | None = None
    # Worker-only checkpoint metadata; excluded from the public HTTP response.
    operation_call_start: int = Field(default=0, ge=0, exclude=True)
    processed_operation_keys: list[str] = Field(
        default_factory=list,
        exclude=True,
    )


class RedisWorkflowMessage(CamelModel):
    task_id: str = Field(min_length=1, max_length=128)
    story_id: int = Field(ge=1)
    thread_id: str = Field(default="", max_length=128)
    action: Literal["START", "RESUME"]
    payload_version: str = Field(default="1", pattern=r"^\d+$")
    revision_no: int = Field(default=0, ge=0)
    idempotency_key: str | None = Field(default=None, max_length=200)
    topic: SelectedTopic | None = None
    approved: bool | None = None
    notes: str = Field(default="", max_length=2000)
    max_revisions: int = Field(default=2, ge=0, le=2)

    @field_validator("thread_id", "notes", mode="before")
    @classmethod
    def normalize_optional_text(cls, value: object) -> object:
        return "" if value is None else value

    @model_validator(mode="after")
    def validate_action_payload(self) -> RedisWorkflowMessage:
        if self.action == "START" and self.topic is None:
            raise ValueError("START消息必须包含topic")
        if self.action == "RESUME":
            if not self.thread_id:
                raise ValueError("RESUME消息必须包含threadId")
            if self.approved is None:
                raise ValueError("RESUME消息必须包含approved")
            if not self.approved and not self.notes.strip():
                raise ValueError("拒绝审核时必须包含notes")
        return self

    def resolved_idempotency_key(self) -> str:
        return self.idempotency_key or (
            f"{self.story_id}:v{self.payload_version}:"
            f"{self.action}:{self.revision_no}"
        )
