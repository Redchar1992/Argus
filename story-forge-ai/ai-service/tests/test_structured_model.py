from __future__ import annotations

import json

import httpx
import pytest

from app.infrastructure.llm_factory import (
    LocalStructuredModel,
    OpenAICompatibleStructuredModel,
    WorkflowModelError,
    WorkflowModelRouter,
)
from app.schemas.character import CharacterPack
from tests.workflow_samples import character


@pytest.mark.asyncio
async def test_openai_compatible_workflow_model_validates_pydantic_json() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["model"] == "creative-model"
        assert body["temperature"] == 0.7
        assert body["response_format"]["type"] == "json_schema"
        assert body["response_format"]["json_schema"]["strict"] is True
        assert (
            body["response_format"]["json_schema"]["schema"]["$defs"][
                "CharacterCard"
            ]["additionalProperties"]
            is False
        )
        assert json.loads(body["messages"][1]["content"]) == {
            "topic": {"title": "测试故事"}
        }
        return httpx.Response(
            200,
            json={
                "model": "creative-model-2026",
                "usage": {"prompt_tokens": 120, "completion_tokens": 240},
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "characters": [
                                        character("林晚", "主角"),
                                        character("顾承泽", "反派"),
                                        character("苏晴", "盟友"),
                                    ]
                                },
                                ensure_ascii=False,
                            )
                        }
                    }
                ],
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        model = OpenAICompatibleStructuredModel(
            api_key="test-key",
            base_url="https://llm.example/v1",
            model="creative-model",
            temperature=0.7,
            timeout_seconds=10,
            client=client,
        )
        generation = await model.generate(
            CharacterPack,
            system_prompt="人物提示词",
            payload={"topic": {"title": "测试故事"}},
            purpose="character",
        )

    assert generation.model_name == "creative-model-2026"
    assert generation.input_tokens == 120
    assert generation.output_tokens == 240
    assert len(generation.value.characters) == 3


@pytest.mark.asyncio
async def test_invalid_remote_schema_uses_disclosed_local_fallback() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "choices": [
                    {"message": {"content": '{"characters": []}'}}
                ]
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        remote = OpenAICompatibleStructuredModel(
            api_key="test-key",
            base_url="https://llm.example/v1",
            model="broken-model",
            temperature=0.7,
            timeout_seconds=10,
            client=client,
        )
        router = WorkflowModelRouter(remote, fallback=LocalStructuredModel())
        generation = await router.generate(
            CharacterPack,
            system_prompt="人物提示词",
            payload={
                "topic": {
                    "title": "都市复仇",
                    "hook": "身份反转",
                }
            },
            purpose="character",
        )

    assert generation.model_name == "local-workflow-template"
    assert len(generation.value.characters) == 4


@pytest.mark.asyncio
async def test_invalid_remote_schema_without_fallback_raises() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "not-json"}}]},
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        model = OpenAICompatibleStructuredModel(
            api_key="test-key",
            base_url="https://llm.example/v1",
            model="broken-model",
            temperature=0.1,
            timeout_seconds=10,
            client=client,
        )
        with pytest.raises(WorkflowModelError):
            await model.generate(
                CharacterPack,
                system_prompt="人物提示词",
                payload={"topic": {"title": "都市复仇"}},
                purpose="character",
            )
