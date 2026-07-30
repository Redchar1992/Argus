"""Commercial score contracts with application-owned arithmetic."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

SCORE_DIMENSIONS = ("hook", "emotion", "conflict", "twist", "adaptation")
ScoreLevel = Literal["S", "A", "B", "C"]


class ScoreDimension(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True, extra="forbid")

    score: int = Field(ge=0, le=20)
    reason: str = Field(min_length=2, max_length=240)
    major_problem: str = Field(min_length=1, max_length=240)
    suggestion: str = Field(min_length=2, max_length=320)


class StoryScore(BaseModel):
    """Raw model response. Deliberately excludes total and level."""

    model_config = ConfigDict(str_strip_whitespace=True, extra="forbid")

    hook: ScoreDimension
    emotion: ScoreDimension
    conflict: ScoreDimension
    twist: ScoreDimension
    adaptation: ScoreDimension
    fatal_problem: str = Field(min_length=2, max_length=400)
    revision_priority: list[str] = Field(min_length=1, max_length=5)


class StoryScoreResult(StoryScore):
    """Application-enriched score returned to callers and workflow state."""

    total: int = Field(ge=0, le=100)
    level: ScoreLevel


def build_score_result(result: StoryScore) -> StoryScoreResult:
    """Compute the only authoritative total outside the model."""

    data = result.model_dump()
    total = sum(data[key]["score"] for key in SCORE_DIMENSIONS)
    level: ScoreLevel = (
        "S" if total >= 90 else "A" if total >= 80 else "B" if total >= 70 else "C"
    )
    return StoryScoreResult.model_validate({**data, "total": total, "level": level})
