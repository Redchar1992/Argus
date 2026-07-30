"""Provider protocol and errors."""

from __future__ import annotations

from typing import Protocol

from app.models import ProviderResult, TopicGenerateRequest


class ProviderError(RuntimeError):
    """Raised when an external provider cannot return a valid result."""


class TopicProvider(Protocol):
    """Minimal interface implemented by local and remote providers."""

    async def generate(self, request: TopicGenerateRequest) -> ProviderResult:
        """Generate exactly ten unscored topic candidates."""
