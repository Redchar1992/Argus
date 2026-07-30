"""Deterministic and explainable commercial-fit scoring."""

from __future__ import annotations

import hashlib
from typing import Any

from app.models import CriterionScore, ProviderTopic, ScoreReasons, TopicItem


class ScoreAgent:
    """Score all candidates on the four MVP criteria and normalize to 0-100."""

    _signals = {
        "conflict": (
            "冲突",
            "被",
            "迫",
            "封杀",
            "羞辱",
            "背锅",
            "审判",
            "赶出",
            "拒绝",
            "失去",
        ),
        "reversal": (
            "反转",
            "身份",
            "竟",
            "却",
            "其实",
            "真相",
            "隐藏",
            "意外",
            "幕后",
            "重来",
        ),
        "emotion": (
            "复仇",
            "逆袭",
            "救赎",
            "成长",
            "夺回",
            "反击",
            "价值",
            "情感",
            "背叛",
            "保护",
        ),
        "fit": (
            "当天",
            "第一",
            "十分钟",
            "倒计时",
            "现场",
            "直播",
            "每次",
            "逐层",
            "片段",
            "揭开",
        ),
    }

    def score(self, topic: ProviderTopic, *, topic_id: int) -> TopicItem:
        text = f"{topic.title} {topic.hook} {topic.summary} {' '.join(topic.tags)}"
        conflict = self._criterion(text, "conflict", topic.title, topic_id)
        reversal = self._criterion(text, "reversal", topic.title, topic_id)
        emotion = self._criterion(text, "emotion", topic.title, topic_id)
        fit = self._criterion(text, "fit", topic.title, topic_id)

        reasons = ScoreReasons(
            conflict=CriterionScore(
                score=conflict,
                reason="开场存在明确对抗或生存压力，便于首集快速入戏。",
            ),
            reversal=CriterionScore(
                score=reversal,
                reason="身份、关系或认知变化能够推动剧情二次升级。",
            ),
            emotionalValue=CriterionScore(
                score=emotion,
                reason="逆袭、成长或情感选择提供了清晰的观众回报。",
            ),
            shortDramaFit=CriterionScore(
                score=fit,
                reason="钩子具体、节点紧凑，可拆分为连续短剧悬念。",
            ),
        )
        # Equal weighting keeps the MVP score transparent and easy to calibrate.
        total = round((conflict + reversal + emotion + fit) / 4)

        return TopicItem(
            id=topic_id,
            title=topic.title,
            hook=topic.hook,
            summary=topic.summary,
            score=max(0, min(100, total)),
            scoreReasons=reasons,
            tags=topic.tags,
        )

    def _criterion(self, text: str, name: str, title: str, topic_id: int) -> int:
        matches = sum(1 for signal in self._signals[name] if signal in text)
        # A stable two-point variation avoids artificial ties without randomness.
        digest = hashlib.sha256(f"{name}:{title}:{topic_id}".encode()).digest()[0]
        variation = digest % 3
        return max(0, min(100, 68 + min(matches, 5) * 6 + variation))


# Keep the first-week ScoreAgent above stable. The second-week commercial score
# has a separate class and Pydantic contract so existing topic generation does
# not change behavior.
class CommercialScoreAgent:
    """Score a complete outline on five 0-20 dimensions."""

    def __init__(self, model: Any = None) -> None:
        from app.infrastructure.llm_factory import get_review_model
        from app.prompts import load_prompt

        self.model = model or get_review_model()
        self.prompt = load_prompt("score")

    async def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        from app.agents.workflow_utils import artifact, invoke_structured, progress
        from app.schemas.score import StoryScore, build_score_result

        generation, call = await invoke_structured(
            self.model,
            StoryScore,
            node="score_outline",
            prompt_name="score",
            prompt=self.prompt,
            payload={
                "topic": state["topic"],
                "characters": state["characters"],
                "outline": state["outline"],
                "revision_count": int(state.get("revision_count", 0)),
            },
            purpose="score",
        )
        score = build_score_result(generation.value)
        score_data = score.model_dump(mode="json")
        version_no = int(state.get("revision_count", 0)) + 1
        return {
            "score": score_data,
            "current_node": "score_outline",
            "progress_events": [
                progress(
                    "score_outline",
                    f"大纲评分完成：{score.total}分",
                    revision_no=version_no,
                )
            ],
            "artifacts": [
                artifact(
                    artifact_type="SCORE",
                    version_no=version_no,
                    status="DRAFT",
                    content=score_data,
                    prompt_name="score",
                    model_name=generation.model_name,
                )
            ],
            "model_calls": [call],
        }


async def score_outline(state: dict[str, Any]) -> dict[str, Any]:
    return await CommercialScoreAgent()(state)
