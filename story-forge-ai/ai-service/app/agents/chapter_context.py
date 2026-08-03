"""Assemble bounded, structured context instead of replaying the whole novel."""

from __future__ import annotations

from typing import Any
import hashlib
import json

from app.agents.chapter_utils import chapter_progress


class ChapterContextAssembler:
    max_context_chars = 40_000

    def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        characters = list(state.get("characters") or [])
        if any(not isinstance(item, dict) for item in characters):
            raise ValueError("章节上下文人物卡必须是JSON对象")
        names = [
            str(item.get("name", "")).strip()
            for item in characters
            if isinstance(item, dict)
        ]
        if not names or any(not name for name in names):
            raise ValueError("章节上下文必须包含已批准人物卡")
        if len(names) != len(set(names)):
            raise ValueError("章节上下文人物姓名不得重复")

        current_outline_nodes = list(
            state.get("current_outline_nodes") or state.get("outline_nodes") or []
        )
        if len(current_outline_nodes) != 2 or any(
            not isinstance(node, dict) for node in current_outline_nodes
        ):
            raise ValueError("章节上下文必须包含恰好两个当前大纲节点")

        packet = {
            "contentMode": state.get("content_mode", "SHORT_STORY"),
            "viewpoint": state.get("viewpoint", "THIRD_LIMITED"),
            "styleProfile": dict(state.get("style_profile") or {}),
            "characters": characters,
            "canonFacts": list(state.get("canon_facts") or []),
            "relationshipStates": list(state.get("relationship_states") or []),
            # Only the latest three approved summaries are supplied to the model.
            "recentSummaries": list(state.get("recent_summaries") or [])[-3:],
            # outlineNodes remains a compatibility alias. Both keys are bounded
            # to the same two beats so a cloud model never sees future chapters.
            "outlineNodes": current_outline_nodes,
            "currentOutlineNodes": current_outline_nodes,
            "unresolvedThreads": list(state.get("unresolved_threads") or []),
            "foreshadowingLedger": list(state.get("foreshadowing_ledger") or []),
        }
        omitted: dict[str, int] = {}
        for key, limit in {
            "characters": 24,
            "canonFacts": 80,
            "relationshipStates": 80,
            "recentSummaries": 3,
            "unresolvedThreads": 60,
            "foreshadowingLedger": 60,
        }.items():
            values = packet[key]
            if len(values) > limit:
                omitted[key] = len(values) - limit
                packet[key] = values[-limit:]

        trim_order = ("foreshadowingLedger", "unresolvedThreads", "relationshipStates", "canonFacts", "characters")
        while len(json.dumps(packet, ensure_ascii=False, separators=(",", ":"))) > self.max_context_chars:
            for key in trim_order:
                if len(packet[key]) > 1:
                    packet[key] = packet[key][1:]
                    omitted[key] = omitted.get(key, 0) + 1
                    break
            else:
                break
        if omitted:
            packet["contextOmitted"] = omitted
        packet["contextSnapshotHash"] = state.get("context_snapshot_hash") or hashlib.sha256(
            json.dumps(packet, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        return {
            "context_packet": packet,
            "recent_summaries": packet["recentSummaries"],
            "current_node": "load_context",
            "progress_events": [
                chapter_progress("load_context", "章节结构化上下文已装配")
            ],
        }
