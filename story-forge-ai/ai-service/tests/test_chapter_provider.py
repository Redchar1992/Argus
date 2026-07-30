from __future__ import annotations

import json

import httpx
import pytest

from app.config import Settings
from app.infrastructure.llm_factory import (
    LocalStructuredModel,
    OpenAICompatibleStructuredModel,
    WorkflowModelRouter,
    get_creative_model,
)


def test_ollama_provider_does_not_require_fake_frontend_credential() -> None:
    settings = Settings(
        model_provider="ollama",
        openai_api_key=None,
        ollama_base_url="http://ollama:11434/v1",
        ollama_model="qwen2.5:7b",
    )
    model = get_creative_model(settings)
    assert isinstance(model, WorkflowModelRouter)
    assert isinstance(model.primary, OpenAICompatibleStructuredModel)
    assert model.primary.base_url == "http://ollama:11434/v1"
    assert model.primary.model_name == "qwen2.5:7b"
    assert model.primary.api_key == "ollama"


def test_local_provider_is_explicit_and_deterministic() -> None:
    model = get_creative_model(Settings(model_provider="local"))
    assert isinstance(model, LocalStructuredModel)
    assert model.model_name == "local-workflow-template"


@pytest.mark.asyncio
async def test_openai_compatible_text_stream_yields_plain_deltas_and_usage() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["stream"] is True
        assert body["messages"][0]["role"] == "system"
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            content=(
                'data: {"model":"remote-writer","choices":[{"delta":'
                '{"content":"林晚推开门。"}}]}\n\n'
                'data: {"model":"remote-writer","choices":[{"delta":'
                '{"content":"证据亮在屏幕上。"}}],'
                '"usage":{"prompt_tokens":12,"completion_tokens":8}}\n\n'
                "data: [DONE]\n\n"
            ),
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        model = OpenAICompatibleStructuredModel(
            api_key="server-only",
            base_url="http://model.test/v1",
            model="writer",
            temperature=0.7,
            timeout_seconds=5,
            client=client,
        )
        deltas = [
            delta
            async for delta in model.stream_text(
                system_prompt="只输出正文",
                payload={"chapterNo": 1},
                purpose="chapter_write",
            )
        ]

    assert "".join(delta.text for delta in deltas) == ("林晚推开门。证据亮在屏幕上。")
    assert deltas[-1].done is True
    assert deltas[-1].model_name == "remote-writer"
    assert deltas[-1].input_tokens == 12
    assert deltas[-1].output_tokens == 8
