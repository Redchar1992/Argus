"""Structured contracts for whole-book final review."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


def _camel(value: str) -> str:
    words = value.split("_")
    return words[0] + "".join(word.title() for word in words[1:])


class FinalReviewModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=_camel,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )


class FinalReviewChapter(FinalReviewModel):
    chapter_no: int = Field(ge=1, le=200)
    title: str = Field(min_length=1, max_length=255)
    content: str = Field(min_length=1, max_length=100_000)


class EvidenceLocation(FinalReviewModel):
    chapter_no: int = Field(ge=1, le=200)
    description: str = Field(min_length=1, max_length=500)
    excerpt: str | None = Field(default=None, max_length=1_000)


FinalIssueType = Literal[
    "CONTINUITY",
    "CHARACTER",
    "TIMELINE",
    "PLOT_THREAD",
    "FORESHADOWING",
    "PACING",
    "REPETITION",
    "LANGUAGE",
    "ENDING",
    "COMMERCIAL",
]
FinalIssueSeverity = Literal["CRITICAL", "HIGH", "MEDIUM", "LOW"]


class FinalIssue(FinalReviewModel):
    issue_type: FinalIssueType
    severity: FinalIssueSeverity
    title: str = Field(min_length=2, max_length=200)
    description: str = Field(min_length=2, max_length=1_000)
    evidence: list[EvidenceLocation] = Field(min_length=1, max_length=8)
    suggested_fix: str = Field(min_length=2, max_length=1_000)
    affected_chapters: list[int] = Field(min_length=1, max_length=20)


class ScoreSection(FinalReviewModel):
    score: int = Field(ge=0, le=100)
    summary: str = Field(min_length=2, max_length=500)
    strengths: list[str] = Field(default_factory=list, max_length=8)
    weaknesses: list[str] = Field(default_factory=list, max_length=8)


class FinalStoryReport(FinalReviewModel):
    content_quality: ScoreSection
    hit_potential: ScoreSection
    short_drama_adaptation: ScoreSection
    critical_issues: list[FinalIssue] = Field(default_factory=list, max_length=30)
    normal_issues: list[FinalIssue] = Field(default_factory=list, max_length=100)
    unresolved_threads: list[str] = Field(default_factory=list, max_length=30)
    unresolved_foreshadowing: list[str] = Field(default_factory=list, max_length=30)
    strongest_chapters: list[int] = Field(default_factory=list, max_length=20)
    weakest_chapters: list[int] = Field(default_factory=list, max_length=20)
    suggested_titles: list[str] = Field(min_length=1, max_length=10)
    suggested_tags: list[str] = Field(default_factory=list, max_length=20)
    revision_order: list[str] = Field(default_factory=list, max_length=30)
    total: int = Field(ge=0, le=100)
    level: Literal["S", "A", "B", "C", "D"]
    disclaimer: str = Field(min_length=10, max_length=300)


class FinalReviewRequest(FinalReviewModel):
    story_title: str = Field(min_length=1, max_length=255)
    genre: str = Field(min_length=1, max_length=100)
    target_audience: str | None = Field(default=None, max_length=200)
    chapters: list[FinalReviewChapter] = Field(min_length=1, max_length=200)
    characters: list[dict] = Field(default_factory=list, max_length=100)
    canon_facts: list[dict] = Field(default_factory=list, max_length=300)
    unresolved_threads: list[dict] = Field(default_factory=list, max_length=100)
    foreshadowing_ledger: list[dict] = Field(default_factory=list, max_length=100)
