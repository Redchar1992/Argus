"""Twenty-node outline schemas and deterministic structural checks."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

OutlineStage = Literal["开篇", "发展", "升级", "高潮", "结局"]


class OutlineNode(BaseModel):
    """A single causally useful story beat."""

    model_config = ConfigDict(str_strip_whitespace=True, extra="forbid")

    node_no: int = Field(ge=1, le=20)
    stage: OutlineStage
    event: str = Field(min_length=4, max_length=400)
    conflict: str = Field(min_length=1, max_length=300)
    protagonist_goal: str = Field(min_length=2, max_length=240)
    emotional_target: str = Field(min_length=2, max_length=160)
    new_information: str = Field(min_length=1, max_length=240)
    cliffhanger: str = Field(min_length=1, max_length=240)
    is_twist: bool
    setup_or_payoff: str = Field(min_length=1, max_length=240)


def validate_outline(nodes: Sequence[OutlineNode | dict[str, object]]) -> None:
    """Enforce constraints that should never be delegated to an LLM."""

    if len(nodes) != 20:
        raise ValueError("大纲节点数必须为20")

    parsed = [
        node if isinstance(node, OutlineNode) else OutlineNode.model_validate(node)
        for node in nodes
    ]
    numbers = [node.node_no for node in parsed]
    if numbers != list(range(1, 21)):
        raise ValueError("大纲节点编号必须从1到20连续递增")

    if sum(node.is_twist for node in parsed) < 4:
        raise ValueError("有效反转数量不得少于4")

    if not any(node.conflict.strip() and node.conflict != "无" for node in parsed[:3]):
        raise ValueError("前3个节点必须建立明确冲突")

    if parsed[-1].stage != "结局":
        raise ValueError("第20个节点必须属于结局阶段")
    if parsed[-1].emotional_target in {"", "无"}:
        raise ValueError("结局节点必须提供明确情绪释放")


class OutlineResult(BaseModel):
    """A complete, mechanically valid outline version."""

    model_config = ConfigDict(str_strip_whitespace=True, extra="forbid")

    title: str = Field(min_length=2, max_length=120)
    core_conflict: str = Field(min_length=4, max_length=400)
    ending_type: str = Field(min_length=2, max_length=80)
    nodes: list[OutlineNode] = Field(min_length=20, max_length=20)

    @model_validator(mode="after")
    def validate_structure(self) -> OutlineResult:
        validate_outline(self.nodes)
        return self
