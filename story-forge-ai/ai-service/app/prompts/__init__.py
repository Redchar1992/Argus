"""Versioned prompt loader for second-week workflow agents."""

from __future__ import annotations

from pathlib import Path

PROMPT_VERSION = "v1"
_PROMPT_DIR = Path(__file__).resolve().parent


def load_prompt(name: str, version: str = PROMPT_VERSION) -> str:
    path = _PROMPT_DIR / f"{name}_{version}.txt"
    return path.read_text(encoding="utf-8").strip()


def profile_prompt_name(name: str, content_mode: str = "SHORT_STORY") -> str:
    return f"{name}_novel" if content_mode == "NOVEL" else name


def load_profile_prompt(name: str, content_mode: str = "SHORT_STORY") -> tuple[str, str]:
    prompt_name = profile_prompt_name(name, content_mode)
    return load_prompt(prompt_name), prompt_name


__all__ = ["PROMPT_VERSION", "load_prompt", "load_profile_prompt", "profile_prompt_name"]
