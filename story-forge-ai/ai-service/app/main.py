"""FastAPI application factory."""

from __future__ import annotations

import hmac
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from app.agents import FinalReviewAgent, TopicAgent
from app.agents.topic_agent import TopicGenerationUnavailable
from app.api import workflow_router
from app.config import Settings
from app.infrastructure.llm_factory import WorkflowModelError, get_review_model
from app.models import TopicGenerateRequest, TopicGenerationResponse
from app.providers import LocalTemplateProvider, OpenAICompatibleProvider
from app.schemas.final_review import FinalReviewRequest, FinalStoryReport
from app.workflow import (
    StoryWorkflowConflict,
    StoryWorkflowNotFound,
    StoryWorkflowService,
    persistent_story_service,
)


async def require_internal_api_key(
    request: Request,
    provided_key: str | None = Header(default=None, alias="X-Internal-API-Key"),
) -> None:
    """Protect service-to-service routes when an internal key is configured.

    Local unit tests and direct development runs may omit the key, while the
    Compose deployment requires it and keeps the AI service off the host
    network. This dependency fails closed whenever the deployment has a key.
    """

    expected_key = request.app.state.internal_api_key
    if expected_key and (
        not provided_key or not hmac.compare_digest(provided_key, expected_key)
    ):
        raise HTTPException(status_code=401, detail="invalid internal API key")


def _build_agent(settings: Settings) -> TopicAgent:
    local = LocalTemplateProvider()
    provider = settings.model_provider
    use_remote = provider in {"openai-compatible", "ollama"} or (
        provider == "auto" and bool(settings.openai_api_key)
    )
    if not use_remote:
        return TopicAgent(provider=local)

    remote = OpenAICompatibleProvider(
        api_key=settings.openai_api_key or "ollama",
        base_url=(
            settings.ollama_base_url
            if provider == "ollama"
            else settings.openai_base_url
        ),
        model=(
            settings.ollama_model if provider == "ollama" else settings.openai_model
        ),
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
    final_review_agent: FinalReviewAgent | None = None,
) -> FastAPI:
    resolved_settings = settings or Settings.from_env()
    resolved_settings.validate_runtime()
    if (
        resolved_settings.require_internal_api_key
        and not resolved_settings.internal_api_key
    ):
        raise RuntimeError(
            "AI_INTERNAL_API_KEY must be configured when internal API key "
            "enforcement is enabled"
        )

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        if workflow_service is not None:
            app.state.workflow_service = workflow_service
            yield
            return
        async with persistent_story_service(
            resolved_settings.story_checkpoint_db
        ) as service:
            app.state.workflow_service = service
            yield

    app = FastAPI(
        title=resolved_settings.app_name,
        version=resolved_settings.app_version,
        lifespan=lifespan,
    )
    app.state.topic_agent = topic_agent or _build_agent(resolved_settings)
    app.state.workflow_service = workflow_service
    app.state.internal_api_key = resolved_settings.internal_api_key
    app.state.final_review_agent = final_review_agent or FinalReviewAgent(
        model=get_review_model(resolved_settings)
    )
    app.include_router(
        workflow_router,
        dependencies=[Depends(require_internal_api_key)],
    )

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
        dependencies=[Depends(require_internal_api_key)],
    )
    async def generate_topics(
        payload: TopicGenerateRequest, request: Request
    ) -> TopicGenerationResponse:
        agent: TopicAgent = request.app.state.topic_agent
        return await agent.generate(payload)

    @app.post(
        "/ai/final-review",
        response_model=FinalStoryReport,
        response_model_by_alias=True,
        response_model_exclude_none=True,
        dependencies=[Depends(require_internal_api_key)],
    )
    async def final_review(
        payload: FinalReviewRequest, request: Request
    ) -> FinalStoryReport:
        agent: FinalReviewAgent = request.app.state.final_review_agent
        return await agent.review(payload)

    return app


app = create_app()
