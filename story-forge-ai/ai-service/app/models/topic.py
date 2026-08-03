"""Models shared by the topic generation endpoint and its agents."""

from __future__ import annotations

import re
from datetime import datetime
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_serializer

ShortText = Annotated[str, Field(min_length=1, max_length=120)]


class TopicGenerateRequest(BaseModel):
    """A validated creative direction supplied by the product UI."""

    model_config = ConfigDict(
        populate_by_name=True,
        str_strip_whitespace=True,
        extra="forbid",
    )

    genre: str = Field(min_length=2, max_length=50)
    audience: str = Field(min_length=1, max_length=50)
    keywords: list[str] = Field(default_factory=list, max_length=10)
    content_mode: str = Field(default="SHORT_STORY", alias="contentMode", pattern="^(SHORT_STORY|NOVEL)$")
    story_id: int | None = Field(default=None, alias="storyId", ge=1)
    prompt_version: str | None = Field(
        default=None, alias="promptVersion", max_length=64
    )
    prompt_system: str | None = Field(
        default=None, alias="promptSystem", max_length=50_000
    )

    @field_validator("keywords", mode="before")
    @classmethod
    def normalize_keywords(cls, value: object) -> object:
        """Accept either a UI-friendly string or a JSON string array."""

        if value is None:
            return []
        if isinstance(value, str):
            return [part for part in re.split(r"[,，、;；\s]+", value) if part]
        return value

    @field_validator("keywords")
    @classmethod
    def validate_keywords(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        seen: set[str] = set()
        for value in values:
            keyword = value.strip()
            if not keyword:
                continue
            if len(keyword) > 30:
                raise ValueError("each keyword must contain at most 30 characters")
            if keyword not in seen:
                seen.add(keyword)
                normalized.append(keyword)
        return normalized


class ProviderTopic(BaseModel):
    """Strict, score-free output expected from a topic provider."""

    model_config = ConfigDict(str_strip_whitespace=True, extra="forbid")

    title: str = Field(min_length=2, max_length=120)
    hook: str = Field(min_length=2, max_length=240)
    summary: str = Field(min_length=10, max_length=600)
    tags: list[ShortText] = Field(min_length=1, max_length=10)


class ProviderResult(BaseModel):
    """Normalized provider result before scoring."""

    model_config = ConfigDict(extra="forbid")

    topics: list[ProviderTopic] = Field(min_length=10, max_length=10)
    model: str = Field(min_length=1, max_length=120)


class CriterionScore(BaseModel):
    """A normalized 0-100 criterion score with an explainable reason."""

    model_config = ConfigDict(extra="forbid")

    score: int = Field(ge=0, le=100)
    reason: str = Field(min_length=1, max_length=160)


class ScoreReasons(BaseModel):
    """The four product criteria evaluated by Score Agent."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    conflict: CriterionScore
    reversal: CriterionScore
    emotional_value: CriterionScore = Field(alias="emotionalValue")
    short_drama_fit: CriterionScore | None = Field(default=None, alias="shortDramaFit")
    novel_fit: CriterionScore | None = Field(default=None, alias="novelFit")

    @model_serializer(mode="plain")
    def serialize_without_empty_profiles(self) -> dict[str, object]:
        return {
            key: value.model_dump(by_alias=True)
            for key, value in (
                ("conflict", self.conflict),
                ("reversal", self.reversal),
                ("emotionalValue", self.emotional_value),
                ("shortDramaFit", self.short_drama_fit),
                ("novelFit", self.novel_fit),
            )
            if value is not None
        }


class TopicItem(BaseModel):
    """A topic ready for rendering and persistence by the backend."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    id: int = Field(ge=1, le=10)
    title: str
    hook: str
    summary: str
    score: int = Field(ge=0, le=100)
    score_reasons: ScoreReasons = Field(alias="scoreReasons")
    tags: list[str]


class TopicGenerationResponse(BaseModel):
    """Stable response contract consumed by Spring Boot."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    topics: list[TopicItem] = Field(min_length=10, max_length=10)
    model: str = Field(min_length=1, max_length=120)
    generated_at: datetime = Field(alias="generatedAt")
    prompt_version: str = Field(default="topic_v1", alias="promptVersion")
