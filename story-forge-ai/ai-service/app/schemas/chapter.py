"""Validated contracts for chapter planning, writing, review, and memory."""

from __future__ import annotations

import hashlib
import json
from enum import StrEnum
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


def _camel(value: str) -> str:
    words = value.split("_")
    return words[0] + "".join(word.title() for word in words[1:])


class ChapterModel(BaseModel):
    """Strict camelCase wire model with ergonomic snake_case Python fields."""

    model_config = ConfigDict(
        alias_generator=_camel,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )


class ScenePlan(ChapterModel):
    scene_no: int = Field(ge=1, le=6)
    location: str = Field(min_length=1, max_length=120)
    time: str = Field(min_length=1, max_length=80)
    characters: list[str] = Field(min_length=1, max_length=6)
    protagonist_goal: str = Field(min_length=2, max_length=240)
    opposing_force: str = Field(min_length=2, max_length=240)
    visible_conflict: str = Field(min_length=2, max_length=320)
    information_revealed: str = Field(min_length=1, max_length=320)
    emotional_change: str = Field(min_length=2, max_length=240)
    setup_or_payoff: str = Field(min_length=1, max_length=240)
    exit_hook: str = Field(min_length=2, max_length=240)
    scene_function: Literal["建立", "升级", "反转", "高潮", "过渡", "收束"]

    @field_validator("characters")
    @classmethod
    def unique_characters(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("场景人物不得重复")
        return value


class ChapterPlan(ChapterModel):
    chapter_title: str = Field(min_length=2, max_length=120)
    chapter_goal: str = Field(min_length=4, max_length=400)
    opening_hook: str = Field(min_length=4, max_length=320)
    ending_hook: str = Field(min_length=4, max_length=320)
    target_length: int = Field(ge=800, le=5000)
    scenes: list[ScenePlan] = Field(min_length=3, max_length=6)

    @model_validator(mode="after")
    def scenes_are_contiguous(self) -> ChapterPlan:
        expected = list(range(1, len(self.scenes) + 1))
        if [scene.scene_no for scene in self.scenes] != expected:
            raise ValueError("场景编号必须从1连续递增")
        return self

    def validate_known_characters(self, names: set[str]) -> None:
        unknown = sorted(
            {
                character
                for scene in self.scenes
                for character in scene.characters
                if character not in names
            }
        )
        if unknown:
            raise ValueError(f"章节计划包含未登记角色：{', '.join(unknown)}")

    def validate_outline_coverage(self, current_nodes: list[dict[str, Any]]) -> None:
        """Require concrete anchors from both assigned beats in goal and scenes."""

        if len(current_nodes) != 2:
            raise ValueError("章节计划必须对应恰好两个当前大纲节点")

        def field(node: dict[str, Any], camel: str, snake: str) -> str:
            return str(node.get(camel, node.get(snake, ""))).strip()

        def anchor(value: str) -> str:
            return "".join(value.split())[:24]

        chapter_goal = "".join(self.chapter_goal.split())
        scene_contract = "".join(
            "".join(value.split())
            for scene in self.scenes
            for value in (
                scene.protagonist_goal,
                scene.visible_conflict,
                scene.information_revealed,
                scene.setup_or_payoff,
                scene.exit_hook,
            )
        )
        for position, node in enumerate(current_nodes, start=1):
            event = anchor(field(node, "event", "event"))
            goal = anchor(field(node, "protagonistGoal", "protagonist_goal"))
            if not event or not goal:
                raise ValueError(f"当前大纲节点{position}缺少event或protagonistGoal")
            if event not in chapter_goal or goal not in chapter_goal:
                raise ValueError(f"chapterGoal未覆盖当前大纲节点{position}的事件和目标")
            if event not in scene_contract or goal not in scene_contract:
                raise ValueError(f"场景未覆盖当前大纲节点{position}的事件和目标")


class ReviewDimension(ChapterModel):
    score: int = Field(ge=0, le=20)
    max_score: Literal[10, 15, 20]
    evidence: list[str] = Field(default_factory=list, max_length=5)
    problems: list[str] = Field(default_factory=list, max_length=5)
    suggestions: list[str] = Field(default_factory=list, max_length=5)

    @model_validator(mode="after")
    def score_does_not_exceed_dimension(self) -> ReviewDimension:
        if self.score > self.max_score:
            raise ValueError("维度得分不能超过该维度满分")
        return self


_REVIEW_MAXIMA = {
    "outline_completion": 20,
    "continuity": 20,
    "conflict_progression": 20,
    "emotion_and_visuals": 15,
    "hooks": 15,
    "language_quality": 10,
}


class ChapterReview(ChapterModel):
    """Raw model review; authoritative total is calculated by the application."""

    outline_completion: ReviewDimension
    continuity: ReviewDimension
    conflict_progression: ReviewDimension
    emotion_and_visuals: ReviewDimension
    hooks: ReviewDimension
    language_quality: ReviewDimension
    fatal_problems: list[str] = Field(default_factory=list, max_length=10)
    rewrite_instructions: list[str] = Field(default_factory=list, max_length=10)
    should_rewrite: bool

    @model_validator(mode="after")
    def expected_dimension_maxima(self) -> ChapterReview:
        for name, maximum in _REVIEW_MAXIMA.items():
            if getattr(self, name).max_score != maximum:
                raise ValueError(f"{name}的maxScore必须为{maximum}")
        return self


class ChapterReviewResult(ChapterReview):
    total_score: int = Field(ge=0, le=100)
    mechanical_errors: list[str] = Field(default_factory=list)


def build_chapter_review(
    review: ChapterReview,
    mechanical_errors: list[str] | None = None,
) -> ChapterReviewResult:
    errors = mechanical_errors or []
    total = sum(getattr(review, name).score for name in _REVIEW_MAXIMA)
    should_rewrite = bool(total < 82 or review.fatal_problems or errors)
    return ChapterReviewResult.model_validate(
        {
            **review.model_dump(),
            "total_score": total,
            "mechanical_errors": errors,
            "should_rewrite": should_rewrite,
        }
    )


class ChapterSummary(ChapterModel):
    chapter_no: int = Field(ge=1)
    summary: str = Field(min_length=10, max_length=1200)
    main_events: list[str] = Field(min_length=1, max_length=10)
    character_changes: list[str] = Field(default_factory=list, max_length=10)
    new_facts: list[dict[str, Any]] = Field(default_factory=list, max_length=20)
    opened_threads: list[dict[str, Any]] = Field(default_factory=list, max_length=10)
    resolved_threads: list[str] = Field(default_factory=list, max_length=10)
    ending_hook: str = Field(min_length=2, max_length=400)


class MemoryVisibility(StrEnum):
    PUBLIC = "PUBLIC"
    READER_ONLY = "READER_ONLY"
    CHARACTER_PRIVATE = "CHARACTER_PRIVATE"
    UNKNOWN = "UNKNOWN"


class MemoryFactStatus(StrEnum):
    ACTIVE = "ACTIVE"
    INACTIVE = "INACTIVE"
    SUPERSEDED = "SUPERSEDED"


class PlotThreadStatus(StrEnum):
    OPEN = "OPEN"
    DORMANT = "DORMANT"
    RESOLVED = "RESOLVED"


class ForeshadowingStatus(StrEnum):
    SETUP = "SETUP"
    PAID_OFF = "PAID_OFF"
    ABANDONED = "ABANDONED"


MemoryKey = Annotated[str, Field(min_length=1, max_length=128)]
MemoryText = Annotated[str, Field(min_length=1, max_length=20_000)]
MemoryWarning = Annotated[str, Field(min_length=1, max_length=2_000)]


def _validate_memory_json(value: Any) -> Any:
    if value is None:
        raise ValueError("值不能为空")
    try:
        serialized = json.dumps(
            value,
            ensure_ascii=False,
            separators=(",", ":"),
        )
    except (TypeError, ValueError) as exc:
        raise ValueError("值必须是可序列化的JSON") from exc
    if len(serialized) > 20_000:
        raise ValueError("值序列化后不得超过20000字符")
    return value


class MemoryFact(ChapterModel):
    fact_key: MemoryKey
    fact_type: str = Field(
        default="OTHER",
        min_length=1,
        max_length=32,
        pattern=r"^[A-Z][A-Z0-9_]*$",
    )
    subject: str | None = Field(default=None, max_length=100)
    predicate: str | None = Field(default=None, max_length=100)
    value: Any
    visibility: MemoryVisibility = MemoryVisibility.UNKNOWN
    source_chapter: int | None = Field(default=None, ge=1)
    locked: bool = False
    status: MemoryFactStatus = MemoryFactStatus.ACTIVE

    @field_validator("value")
    @classmethod
    def value_fits_backend_text(cls, value: Any) -> Any:
        return _validate_memory_json(value)


class RelationshipChange(ChapterModel):
    relationship_key: str | None = Field(default=None, max_length=220)
    character_a: str = Field(min_length=1, max_length=100)
    character_b: str = Field(min_length=1, max_length=100)
    relation: str | None = Field(default=None, max_length=100)
    trust: int | None = Field(default=None, ge=0, le=100)
    conflict: int | None = Field(default=None, ge=0, le=100)
    public_status: str | None = Field(default=None, max_length=255)
    private_status: str | None = Field(default=None, max_length=255)
    updated_at_chapter: int | None = Field(default=None, ge=1)


class PlotThreadUpdate(ChapterModel):
    thread_key: MemoryKey
    description: MemoryText
    introduced_chapter: int | None = Field(default=None, ge=1)
    expected_payoff_chapter: int | None = Field(default=None, ge=1)
    resolved_chapter: int | None = Field(default=None, ge=1)
    status: PlotThreadStatus = PlotThreadStatus.OPEN
    known_clues: list[MemoryText] = Field(default_factory=list, max_length=30)


class ForeshadowingUpdate(ChapterModel):
    foreshadow_key: MemoryKey
    setup: MemoryText
    setup_chapter: int | None = Field(default=None, ge=1)
    payoff_plan: str | None = Field(default=None, max_length=20_000)
    payoff_chapter: int | None = Field(default=None, ge=1)
    actual_payoff_chapter: int | None = Field(default=None, ge=1)
    status: ForeshadowingStatus = ForeshadowingStatus.SETUP


class CharacterStateChange(ChapterModel):
    character: str = Field(min_length=1, max_length=100)
    field: str = Field(min_length=1, max_length=100)
    new_value: Any
    updated_at_chapter: int | None = Field(default=None, ge=1)
    visibility: MemoryVisibility = MemoryVisibility.PUBLIC

    @field_validator("new_value")
    @classmethod
    def new_value_fits_backend_text(cls, value: Any) -> Any:
        return _validate_memory_json(value)


class MemoryUpdate(ChapterModel):
    new_facts: list[MemoryFact] = Field(default_factory=list, max_length=30)
    changed_relationships: list[RelationshipChange] = Field(
        default_factory=list, max_length=20
    )
    opened_threads: list[PlotThreadUpdate] = Field(default_factory=list, max_length=20)
    updated_threads: list[PlotThreadUpdate] = Field(default_factory=list, max_length=20)
    resolved_threads: list[MemoryKey] = Field(default_factory=list, max_length=20)
    new_foreshadowing: list[ForeshadowingUpdate] = Field(
        default_factory=list, max_length=20
    )
    paid_off_foreshadowing: list[MemoryKey] = Field(default_factory=list, max_length=20)
    character_state_changes: list[CharacterStateChange] = Field(
        default_factory=list, max_length=20
    )
    continuity_warnings: list[MemoryWarning] = Field(
        default_factory=list, max_length=20
    )


class ChapterCommandAction(StrEnum):
    PLAN = "PLAN"
    GENERATE = "GENERATE"
    REWRITE_SELECTION = "REWRITE_SELECTION"
    FINALIZE = "FINALIZE"


class ChapterCommand(ChapterModel):
    task_id: str = Field(min_length=1, max_length=128)
    story_id: int = Field(ge=1)
    chapter_id: int = Field(ge=1)
    chapter_no: int = Field(ge=1)
    action: ChapterCommandAction
    thread_id: str = Field(default="", max_length=200)
    idempotency_key: str = Field(min_length=1, max_length=240)
    payload: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def action_requirements(self) -> ChapterCommand:
        if self.action is ChapterCommandAction.GENERATE and not self.payload.get(
            "chapterPlan"
        ):
            raise ValueError("GENERATE命令必须包含payload.chapterPlan")
        if self.action is ChapterCommandAction.FINALIZE:
            if not self.thread_id:
                raise ValueError("FINALIZE命令必须包含threadId")
            if "approved" not in self.payload:
                raise ValueError("FINALIZE命令必须包含payload.approved")
            if (
                not self.payload.get("approved")
                and not str(self.payload.get("notes", "")).strip()
            ):
                raise ValueError("拒绝章节时必须填写payload.notes")
        if self.action is ChapterCommandAction.REWRITE_SELECTION:
            required = {
                "chapterVersionId",
                "startOffset",
                "endOffset",
                "selectedText",
                "selectedTextHash",
                "action",
            }
            missing = sorted(required - self.payload.keys())
            if missing:
                raise ValueError("REWRITE_SELECTION缺少字段：" + ", ".join(missing))
            selected = str(self.payload["selectedText"])
            start = int(self.payload["startOffset"])
            end = int(self.payload["endOffset"])
            if start < 0 or end <= start:
                raise ValueError("局部改写范围必须满足0 <= startOffset < endOffset")
            if end - start != len(selected):
                raise ValueError("改写范围长度与selectedText不一致")
            digest = hashlib.sha256(selected.encode()).hexdigest()
            if digest != str(self.payload["selectedTextHash"]):
                raise ValueError("selectedTextHash与选中文本不一致")
        return self


class ChapterDecision(ChapterModel):
    approved: bool
    notes: str = Field(default="", max_length=4000)
    current_content: str | None = Field(default=None, max_length=100_000)
    base_version_id: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def notes_required_for_rejection(self) -> ChapterDecision:
        if not self.approved and not self.notes.strip():
            raise ValueError("拒绝章节时必须填写修改意见")
        return self


class RewriteProposal(ChapterModel):
    chapter_version_id: int = Field(ge=1)
    original_text: str = Field(min_length=1, max_length=20_000)
    replacement_text: str = Field(min_length=1, max_length=20_000)
    reason: str = Field(min_length=2, max_length=500)
    selected_text_hash: str = Field(pattern=r"^[a-f0-9]{64}$")


class ChapterRunStatus(StrEnum):
    RUNNING = "RUNNING"
    PLAN_READY = "PLAN_READY"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class ChapterRunResponse(ChapterModel):
    task_id: str
    story_id: int
    chapter_id: int
    chapter_no: int
    thread_id: str
    status: ChapterRunStatus
    current_node: str
    chapter_plan: ChapterPlan | None = None
    draft_content: str = ""
    final_content: str = ""
    chapter_review: ChapterReviewResult | None = None
    chapter_summary: ChapterSummary | None = None
    memory_update: MemoryUpdate | None = None
    mechanical_errors: list[str] = Field(default_factory=list)
    revision_count: int = Field(default=0, ge=0)
    max_revisions: int = Field(default=2, ge=0, le=2)
    approved: bool = False
    artifacts: list[dict[str, Any]] = Field(default_factory=list)
    model_calls: list[dict[str, Any]] = Field(default_factory=list)
    progress_events: list[dict[str, Any]] = Field(default_factory=list)
    interrupt: dict[str, Any] | None = None
    operation_call_start: int = Field(default=0, ge=0, exclude=True)
    processed_operation_keys: list[str] = Field(default_factory=list, exclude=True)
