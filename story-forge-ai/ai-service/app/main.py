"""FastAPI application factory."""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.agents import TopicAgent
from app.agents.topic_agent import TopicGenerationUnavailable
from app.api import workflow_router
from app.config import Settings
from app.infrastructure.llm_factory import WorkflowModelError
from app.models import TopicGenerateRequest, TopicGenerationResponse
from app.providers import LocalTemplateProvider, OpenAICompatibleProvider
from app.workflow import (
    StoryWorkflowConflict,
    StoryWorkflowNotFound,
    StoryWorkflowService,
)


def _build_agent(settings: Settings) -> TopicAgent:
    local = LocalTemplateProvider()
    if not settings.openai_api_key:
        return TopicAgent(provider=local)

    remote = OpenAICompatibleProvider(
        api_key=settings.openai_api_key,
        base_url=settings.openai_base_url,
        model=settings.openai_model,
        timeout_seconds=settings.openai_timeout_seconds,
    )
    return TopicAgent(
        provider=remote,
        fallback_provider=local if settings.openai_fallback_enabled else None,
    )


def create_app(
    settings: Settings | None = None,
    *,
    topic_agent: TopicAgent | None = None,
    workflow_service: StoryWorkflowService | None = None,
) -> FastAPI:
    resolved_settings = settings or Settings.from_env()
    app = FastAPI(
        title=resolved_settings.app_name,
        version=resolved_settings.app_version,
    )
    app.state.topic_agent = topic_agent or _build_agent(resolved_settings)
    app.state.workflow_service = workflow_service or StoryWorkflowService()
    app.include_router(workflow_router)

    @app.exception_handler(TopicGenerationUnavailable)
    async def generation_unavailable_handler(
        _request: Request, exc: TopicGenerationUnavailable
    ) -> JSONResponse:
        return JSONResponse(
            status_code=503,
            content={
                "error": {
                    "code": "TOPIC_GENERATION_UNAVAILABLE",
                    "message": str(exc),
                }
            },
        )

    @app.exception_handler(StoryWorkflowNotFound)
    async def workflow_not_found_handler(
        _request: Request, exc: StoryWorkflowNotFound
    ) -> JSONResponse:
        return JSONResponse(
            status_code=404,
            content={
                "error": {
                    "code": "WORKFLOW_NOT_FOUND",
                    "message": str(exc),
                }
            },
        )

    @app.exception_handler(StoryWorkflowConflict)
    async def workflow_conflict_handler(
        _request: Request, exc: StoryWorkflowConflict
    ) -> JSONResponse:
        return JSONResponse(
            status_code=409,
            content={
                "error": {
                    "code": "WORKFLOW_STATE_CONFLICT",
                    "message": str(exc),
                }
            },
        )

    @app.exception_handler(WorkflowModelError)
    async def workflow_model_error_handler(
        _request: Request, exc: WorkflowModelError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=503,
            content={
                "error": {
                    "code": "WORKFLOW_GENERATION_UNAVAILABLE",
                    "message": str(exc),
                }
            },
        )

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post(
        "/ai/topic/generate",
        response_model=TopicGenerationResponse,
        response_model_by_alias=True,
    )
    async def generate_topics(
        payload: TopicGenerateRequest, request: Request
    ) -> TopicGenerationResponse:
        agent: TopicAgent = request.app.state.topic_agent
        return await agent.generate(payload)

    return app


app = create_app()
