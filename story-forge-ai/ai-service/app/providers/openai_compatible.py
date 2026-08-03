"""OpenAI-compatible Chat Completions provider with strict JSON validation."""

from __future__ import annotations

import json
from typing import Any

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.agents.prompts import build_topic_user_prompt, topic_system_prompt
from app.models import ProviderResult, ProviderTopic, TopicGenerateRequest
from app.providers.base import ProviderError


class _TopicPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    topics: list[ProviderTopic] = Field(min_length=10, max_length=10)


class OpenAICompatibleProvider:
    """Generate topics through a configurable Chat Completions endpoint."""

    def __init__(
        self,
        *,
        api_key: str,
        base_url: str,
        model: str,
        timeout_seconds: float = 30.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout_seconds = timeout_seconds
        self._client = client

    async def generate(self, request: TopicGenerateRequest) -> ProviderResult:
        body = {
            "model": self.model,
            "temperature": 0.8,
            "response_format": {"type": "json_object"},
            "messages": [
                {
                    "role": "system",
                    "content": request.prompt_system or topic_system_prompt(request.content_mode),
                },
                {"role": "user", "content": build_topic_user_prompt(request)},
            ],
        }
        try:
            response = await self._post(body)
            response.raise_for_status()
            api_payload: dict[str, Any] = response.json()
            content = api_payload["choices"][0]["message"]["content"]
            if not isinstance(content, str):
                raise TypeError("message content is not a string")
            decoded = json.loads(content)
            validated = _TopicPayload.model_validate(decoded)
        except (
            httpx.HTTPError,
            json.JSONDecodeError,
            ValidationError,
            KeyError,
            IndexError,
            TypeError,
            ValueError,
        ) as exc:
            raise ProviderError("LLM returned no valid topic JSON") from exc

        returned_model = api_payload.get("model")
        model_name = returned_model if isinstance(returned_model, str) else self.model
        return ProviderResult(topics=validated.topics, model=model_name)

    async def _post(self, body: dict[str, Any]) -> httpx.Response:
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        url = f"{self.base_url}/chat/completions"
        if self._client is not None:
            return await self._client.post(url, headers=headers, json=body)
        async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
            return await client.post(url, headers=headers, json=body)
