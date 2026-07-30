"""HTTP routers for the AI service."""

from app.api.workflow import router as workflow_router

__all__ = ["workflow_router"]
