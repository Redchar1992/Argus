from __future__ import annotations

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.agents import TopicAgent
from app.main import create_app
from app.models import TopicGenerateRequest
from app.providers import (
    LocalTemplateProvider,
    OpenAICompatibleProvider,
    ProviderError,
)


def _provider(client: httpx.AsyncClient) -> OpenAICompatibleProvider:
    return OpenAICompatibleProvider(
        api_key="test-key",
        base_url="https://llm.example/v1",
        model="mock-model",
        client=client,
    )


@pytest.mark.asyncio
async def test_openai_compatible_provider_parses_strict_json(
    raw_topics: list[dict[str, object]],
) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == "https://llm.example/v1/chat/completions"
        assert request.headers["Authorization"] == "Bearer test-key"
        request_body = json.loads(request.content)
        assert request_body["response_format"] == {"type": "json_object"}
        assert request_body["model"] == "mock-model"
        return httpx.Response(
            200,
            json={
                "model": "mock-model-2026-01",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {"topics": raw_topics}, ensure_ascii=False
                            )
                        }
                    }
                ],
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await _provider(client).generate(
            TopicGenerateRequest(genre="都市情感", audience="女性")
        )

    assert result.model == "mock-model-2026-01"
    assert len(result.topics) == 10
    assert result.topics[0].title == "测试选题 1"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "content",
    [
        "```json\n{}\n```",
        '{"topics": []}',
        '{"topics": [], "explanation": "not allowed"}',
    ],
)
async def test_openai_compatible_provider_rejects_invalid_output(content: str) -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": content}}]},
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ProviderError):
            await _provider(client).generate(
                TopicGenerateRequest(genre="都市情感", audience="女性")
            )


def test_invalid_llm_json_falls_back_and_discloses_local_model(
    valid_payload: dict[str, object],
) -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "not-json"}}]},
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    remote = _provider(client)
    agent = TopicAgent(
        provider=remote,
        fallback_provider=LocalTemplateProvider(),
    )

    try:
        with TestClient(create_app(topic_agent=agent)) as api_client:
            response = api_client.post("/ai/topic/generate", json=valid_payload)
    finally:
        # TestClient owns only the FastAPI lifecycle; close the injected HTTP client.
        import asyncio

        asyncio.run(client.aclose())

    assert response.status_code == 200
    assert response.json()["model"] == "local-template"
    assert len(response.json()["topics"]) == 10


def test_llm_failure_without_fallback_returns_503(
    valid_payload: dict[str, object],
) -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, json={"error": "provider down"})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    agent = TopicAgent(provider=_provider(client))
    try:
        with TestClient(create_app(topic_agent=agent)) as api_client:
            response = api_client.post("/ai/topic/generate", json=valid_payload)
    finally:
        import asyncio

        asyncio.run(client.aclose())

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "TOPIC_GENERATION_UNAVAILABLE"
