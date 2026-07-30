"""Environment-backed service configuration."""

from __future__ import annotations

import os
from dataclasses import dataclass


def _as_bool(value: str | None, *, default: bool) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True, slots=True)
class Settings:
    """Runtime settings.

    Keeping this as a small standard-library dataclass makes the service easy to
    start while still allowing every LLM-specific value to be supplied via the
    environment.
    """

    app_name: str = "Story Forge AI Service"
    app_version: str = "0.1.0"
    openai_api_key: str | None = None
    openai_base_url: str = "https://api.openai.com/v1"
    openai_model: str = "gpt-4o-mini"
    openai_timeout_seconds: float = 30.0
    openai_fallback_enabled: bool = True

    @classmethod
    def from_env(cls) -> Settings:
        defaults = cls()
        key = os.getenv("OPENAI_API_KEY", "").strip() or None
        timeout_raw = os.getenv("OPENAI_TIMEOUT_SECONDS", "30")
        try:
            timeout = float(timeout_raw)
        except ValueError:
            timeout = 30.0

        return cls(
            app_name=os.getenv("APP_NAME", defaults.app_name),
            app_version=os.getenv("APP_VERSION", defaults.app_version),
            openai_api_key=key,
            openai_base_url=os.getenv(
                "OPENAI_BASE_URL", defaults.openai_base_url
            ).rstrip("/"),
            openai_model=os.getenv("OPENAI_MODEL", defaults.openai_model),
            openai_timeout_seconds=max(1.0, timeout),
            openai_fallback_enabled=_as_bool(
                os.getenv("OPENAI_FALLBACK_ENABLED"), default=True
            ),
        )
