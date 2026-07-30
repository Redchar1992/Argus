"""Versioned prompt loader for second-week workflow agents."""

from __future__ import annotations

from pathlib import Path

PROMPT_VERSION = "v1"
_PROMPT_DIR = Path(__file__).resolve().parent


def load_prompt(name: str, version: str = PROMPT_VERSION) -> str:
    path = _PROMPT_DIR / f"{name}_{version}.txt"
    return path.read_text(encoding="utf-8").strip()


__all__ = ["PROMPT_VERSION", "load_prompt"]
