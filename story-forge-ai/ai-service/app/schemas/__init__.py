"""Validated contracts for the second-week story workflow."""

from app.schemas.character import CharacterCard, CharacterPack, CharacterRole
from app.schemas.outline import (
    OutlineNode,
    OutlineResult,
    OutlineStage,
    validate_outline,
)
from app.schemas.score import (
    SCORE_DIMENSIONS,
    ScoreDimension,
    ScoreLevel,
    StoryScore,
    StoryScoreResult,
    build_score_result,
)
from app.schemas.workflow import (
    ArtifactRecord,
    ModelCallRecord,
    ProgressEvent,
    RedisWorkflowMessage,
    ReviewDecision,
    SelectedTopic,
    WorkflowResumeRequest,
    WorkflowRunResponse,
    WorkflowStartRequest,
    WorkflowStatus,
)

__all__ = [
    "ArtifactRecord",
    "CharacterCard",
    "CharacterPack",
    "CharacterRole",
    "ModelCallRecord",
    "OutlineNode",
    "OutlineResult",
    "OutlineStage",
    "ProgressEvent",
    "RedisWorkflowMessage",
    "ReviewDecision",
    "SCORE_DIMENSIONS",
    "ScoreDimension",
    "ScoreLevel",
    "SelectedTopic",
    "StoryScore",
    "StoryScoreResult",
    "WorkflowResumeRequest",
    "WorkflowRunResponse",
    "WorkflowStartRequest",
    "WorkflowStatus",
    "build_score_result",
    "validate_outline",
]
