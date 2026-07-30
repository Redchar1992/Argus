"""HTTP start/inspect/resume endpoints for the LangGraph workflow."""

from __future__ import annotations

from fastapi import APIRouter, Request

from app.schemas.workflow import (
    WorkflowResumeRequest,
    WorkflowRunResponse,
    WorkflowStartRequest,
)
from app.workflow.service import StoryWorkflowService

router = APIRouter(prefix="/ai/workflow", tags=["story-workflow"])


def _service(request: Request) -> StoryWorkflowService:
    return request.app.state.workflow_service


@router.post(
    "/start",
    response_model=WorkflowRunResponse,
    response_model_by_alias=True,
)
async def start_workflow(
    payload: WorkflowStartRequest,
    request: Request,
) -> WorkflowRunResponse:
    return await _service(request).start(payload)


@router.get(
    "/{thread_id}",
    response_model=WorkflowRunResponse,
    response_model_by_alias=True,
)
async def get_workflow(
    thread_id: str,
    request: Request,
) -> WorkflowRunResponse:
    return await _service(request).get(thread_id)


@router.post(
    "/{thread_id}/resume",
    response_model=WorkflowRunResponse,
    response_model_by_alias=True,
)
async def resume_workflow(
    thread_id: str,
    payload: WorkflowResumeRequest,
    request: Request,
) -> WorkflowRunResponse:
    return await _service(request).resume(thread_id, payload)
