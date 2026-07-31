"""Topic generation orchestration and safe provider fallback."""

from __future__ import annotations

import logging
from datetime import UTC, datetime

from app.agents.score_agent import ScoreAgent
from app.models import TopicGenerateRequest, TopicGenerationResponse
from app.providers import LocalTemplateProvider, ProviderError, TopicProvider

logger = logging.getLogger(__name__)


class TopicGenerationUnavailable(RuntimeError):
    """Raised when generation fails and fallback is disabled."""


class TopicAgent:
    """Generate candidates, fall back safely, then score each result."""

    def __init__(
        self,
        provider: TopicProvider,
        *,
        score_agent: ScoreAgent | None = None,
        fallback_provider: TopicProvider | None = None,
    ) -> None:
        self.provider = provider
        self.score_agent = score_agent or ScoreAgent()
        self.fallback_provider = fallback_provider

    async def generate(
        self, request: TopicGenerateRequest
    ) -> TopicGenerationResponse:
        try:
            result = await self.provider.generate(request)
        except ProviderError as exc:
            if self.fallback_provider is None:
                raise TopicGenerationUnavailable(
                    "topic generation provider is temporarily unavailable"
                ) from exc
            logger.warning(
                "Topic provider failed; using explicit local fallback: %s", exc
            )
            result = await self.fallback_provider.generate(request)

        topics = [
            self.score_agent.score(topic, topic_id=index)
            for index, topic in enumerate(result.topics, start=1)
        ]
        return TopicGenerationResponse(
            topics=topics,
            model=result.model,
            generatedAt=datetime.now(UTC),
            promptVersion=request.prompt_version or "topic_v1",
        )


def local_topic_agent() -> TopicAgent:
    """Convenience factory used when no remote key is configured."""

    return TopicAgent(provider=LocalTemplateProvider())
