"""Structured workflow-model abstraction with an explicit local fallback.

The first-week topic endpoint keeps its original provider. This module provides
the same OpenAI-compatible transport style for second-week Pydantic outputs,
while keeping agents independent from any one model SDK.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Generic, Protocol, TypeVar

import httpx
from pydantic import BaseModel, ValidationError

from app.config import Settings
from app.schemas.character import CharacterCard, CharacterPack
from app.schemas.outline import OutlineNode, OutlineResult
from app.schemas.score import ScoreDimension, StoryScore

SchemaT = TypeVar("SchemaT", bound=BaseModel)


class WorkflowModelError(RuntimeError):
    """Raised when a workflow model cannot return schema-valid JSON."""


@dataclass(frozen=True, slots=True)
class StructuredGeneration(Generic[SchemaT]):
    value: SchemaT
    model_name: str
    input_tokens: int = 0
    output_tokens: int = 0


class StructuredModel(Protocol):
    model_name: str

    async def generate(
        self,
        schema: type[SchemaT],
        *,
        system_prompt: str,
        payload: dict[str, Any],
        purpose: str,
    ) -> StructuredGeneration[SchemaT]:
        """Return one Pydantic-validated structured value."""


def _stage(node_no: int) -> str:
    if node_no <= 3:
        return "开篇"
    if node_no <= 8:
        return "发展"
    if node_no <= 14:
        return "升级"
    if node_no <= 18:
        return "高潮"
    return "结局"


class LocalStructuredModel:
    """Deterministic, disclosed workflow generator for tests and local demos."""

    model_name = "local-workflow-template"

    async def generate(
        self,
        schema: type[SchemaT],
        *,
        system_prompt: str,
        payload: dict[str, Any],
        purpose: str,
    ) -> StructuredGeneration[SchemaT]:
        del system_prompt
        try:
            if schema is CharacterPack:
                value: BaseModel = self._characters(payload)
            elif schema is OutlineResult and purpose in {"outline", "revise"}:
                value = self._outline(payload, revised=purpose == "revise")
            elif schema is StoryScore:
                value = self._score(payload)
            else:
                raise WorkflowModelError(f"local model does not support {purpose}")
        except (ValidationError, ValueError, KeyError, TypeError) as exc:
            raise WorkflowModelError(
                f"local workflow generation failed for {purpose}"
            ) from exc

        encoded_input = json.dumps(payload, ensure_ascii=False)
        encoded_output = value.model_dump_json()
        return StructuredGeneration(
            value=value,  # type: ignore[arg-type]
            model_name=self.model_name,
            input_tokens=max(1, len(encoded_input) // 4),
            output_tokens=max(1, len(encoded_output) // 4),
        )

    def _characters(self, payload: dict[str, Any]) -> CharacterPack:
        topic = payload["topic"]
        title = str(topic["title"])
        return CharacterPack(
            characters=[
                CharacterCard(
                    name="林晚",
                    role="主角",
                    public_identity=f"围绕《{title}》陷入低谷的普通策划人",
                    hidden_secret="她掌握一份足以改变家族权力结构的原始证据",
                    core_desire="夺回被剥夺的选择权并保护真正关心她的人",
                    greatest_fear="为了复仇变成自己最厌恶的操控者",
                    personality=["克制", "敏锐", "有韧性"],
                    relationship_to_protagonist="本人",
                    character_arc="从习惯忍让到敢于公开真相，并学会为自己的选择负责",
                ),
                CharacterCard(
                    name="顾承泽",
                    role="反派",
                    public_identity="掌控核心资源并维护体面形象的集团继承人",
                    hidden_secret="他的地位依赖一场多年前被精心掩盖的利益交换",
                    core_desire="保住继承权以及由此获得的安全感",
                    greatest_fear="失去权力后再次被家族视为可以牺牲的人",
                    personality=["强势", "谨慎", "控制欲强"],
                    relationship_to_protagonist="既是旧关系中的施压者，也是核心利益对手",
                    character_arc="从确信权力能解决一切，到被迫直面自己制造的代价",
                ),
                CharacterCard(
                    name="苏晴",
                    role="盟友",
                    public_identity="熟悉舆论与证据核验的调查记者",
                    hidden_secret="她曾因一次错误判断间接伤害主角，因此一直暗中补偿",
                    core_desire="让证据被看见，并修复与主角之间失去的信任",
                    greatest_fear="再次为了抢新闻而牺牲无辜者",
                    personality=["直接", "行动力强", "重证据"],
                    relationship_to_protagonist="旧友与查证真相的关键盟友",
                    character_arc="从替主角做决定，到尊重主角并共同承担公开真相的风险",
                ),
                CharacterCard(
                    name="林岚",
                    role="关键配角",
                    public_identity="站在反派阵营、负责家族财务的主角姐姐",
                    hidden_secret="她表面背叛主角，实际保留了能证明资金转移的最后账本",
                    core_desire="在保护家人和纠正旧错之间找到不再逃避的选择",
                    greatest_fear="真相公开会让最亲近的人一起毁掉",
                    personality=["冷静", "矛盾", "责任感强"],
                    relationship_to_protagonist="关系破裂但仍互相牵挂的姐姐",
                    character_arc="从用沉默维持表面和平，到主动交出证据并承担后果",
                ),
            ]
        )

    def _outline(self, payload: dict[str, Any], *, revised: bool) -> OutlineResult:
        topic = payload["topic"]
        revision_count = int(payload.get("revision_count", 0))
        next_version = revision_count + 1 if revised else 0
        note = str(payload.get("review_notes") or "")
        revision_hint = (
            f"；本版针对“{note[:28]}”补足动机和因果"
            if revised and note
            else ("；本版强化反派利益动机与前置证据" if revised else "")
        )
        twists = {4, 8, 12, 16}
        nodes: list[OutlineNode] = []
        for node_no in range(1, 21):
            stage = _stage(node_no)
            is_twist = node_no in twists
            if node_no == 1:
                event = "签字现场，主角被公开逐出项目并收到匿名证据包"
                conflict = "她必须在证据被销毁前决定反击，反派当场施压"
            elif node_no == 20:
                event = "主角公开完整证据链，让责任人受罚并选择新的生活"
                conflict = "她必须在复仇快感与不伤及无辜之间作出最终选择"
            else:
                event = (
                    f"主角推进第{node_no}步调查，行动引发新的可见后果"
                    f"{revision_hint if node_no in {6, 10, 14} else ''}"
                )
                conflict = f"对手以资源和关系阻止第{node_no}步行动，双方代价继续升级"

            if is_twist:
                new_information = (
                    f"第{node_no}节点的旧证据被重新解释，证明表面盟友另有保护动机"
                )
            else:
                new_information = f"获得与核心利益链相关的第{node_no}条可验证信息"

            if node_no <= 10:
                setup = f"埋下证据线索{node_no}，将在高潮阶段回收"
            elif node_no >= 15:
                setup = f"回收前半段证据线索{max(1, node_no - 10)}"
            else:
                setup = "承接既有伏笔并缩小真相范围"

            nodes.append(
                OutlineNode(
                    node_no=node_no,
                    stage=stage,  # type: ignore[arg-type]
                    event=event,
                    conflict=conflict,
                    protagonist_goal=(
                        "保全证据、查清真相，并避免无辜者成为反击代价"
                    ),
                    emotional_target=(
                        "释放压抑后的尊严与新生"
                        if node_no == 20
                        else f"让观众感到第{node_no}轮紧张、期待或逆袭满足"
                    ),
                    new_information=new_information,
                    cliffhanger=(
                        "无"
                        if node_no == 20
                        else f"下一步行动前出现指向更深利益方的第{node_no}个疑问"
                    ),
                    is_twist=is_twist,
                    setup_or_payoff=setup,
                )
            )

        suffix = f"（修订{next_version}）" if revised else ""
        return OutlineResult(
            title=f"{topic['title']}{suffix}",
            core_conflict=(
                "主角必须用完整证据链夺回选择权，同时抵抗反派为保住权力进行的围堵"
            ),
            ending_type="真相公开与自我选择",
            nodes=nodes,
        )

    def _score(self, payload: dict[str, Any]) -> StoryScore:
        revision_count = int(payload.get("revision_count", 0))
        scores = (
            (15, 14, 15, 14, 14)
            if revision_count == 0
            else ((17, 17, 17, 16, 17) if revision_count == 1 else (18,) * 5)
        )
        names = ("开篇", "情绪", "冲突", "反转", "改编")
        dimensions = [
            ScoreDimension(
                score=score,
                reason=f"{name}已有可见事件与因果证据支撑",
                major_problem=(
                    "部分动机铺垫仍然偏晚" if score < 17 else "没有阻断性问题"
                ),
                suggestion=f"在前置节点继续强化{name}对应的行动与代价",
            )
            for score, name in zip(scores, names, strict=True)
        ]
        return StoryScore(
            hook=dimensions[0],
            emotion=dimensions[1],
            conflict=dimensions[2],
            twist=dimensions[3],
            adaptation=dimensions[4],
            fatal_problem=(
                "反派利益动机和关键证据的前置铺垫不足"
                if revision_count == 0
                else "局部节奏仍可压缩，但完整因果链已经成立"
            ),
            revision_priority=[
                "提前展示反派维持权力的现实利益",
                "让关键证据在前半段至少出现两次",
                "压缩只表达情绪而不推动事件的片段",
            ],
        )


class OpenAICompatibleStructuredModel:
    """Generic Pydantic JSON caller using the existing chat-completions contract."""

    def __init__(
        self,
        *,
        api_key: str,
        base_url: str,
        model: str,
        temperature: float,
        timeout_seconds: float,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model_name = model
        self.temperature = temperature
        self.timeout_seconds = timeout_seconds
        self._client = client

    async def generate(
        self,
        schema: type[SchemaT],
        *,
        system_prompt: str,
        payload: dict[str, Any],
        purpose: str,
    ) -> StructuredGeneration[SchemaT]:
        body = {
            "model": self.model_name,
            "temperature": self.temperature,
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": f"story_{purpose}",
                    "strict": True,
                    "schema": schema.model_json_schema(),
                },
            },
            "messages": [
                {"role": "system", "content": system_prompt},
                {
                    "role": "user",
                    "content": json.dumps(payload, ensure_ascii=False),
                },
            ],
        }
        try:
            response = await self._post(body)
            response.raise_for_status()
            api_payload = response.json()
            content = api_payload["choices"][0]["message"]["content"]
            if not isinstance(content, str):
                raise TypeError("message content is not a string")
            decoded = json.loads(content)
            value = schema.model_validate(decoded)
        except (
            httpx.HTTPError,
            json.JSONDecodeError,
            ValidationError,
            KeyError,
            IndexError,
            TypeError,
            ValueError,
        ) as exc:
            raise WorkflowModelError(
                f"model returned invalid {purpose} JSON"
            ) from exc

        usage = api_payload.get("usage")
        usage = usage if isinstance(usage, dict) else {}
        returned_model = api_payload.get("model")
        model_name = (
            returned_model if isinstance(returned_model, str) else self.model_name
        )
        return StructuredGeneration(
            value=value,
            model_name=model_name,
            input_tokens=_non_negative_int(usage.get("prompt_tokens")),
            output_tokens=_non_negative_int(usage.get("completion_tokens")),
        )

    async def _post(self, body: dict[str, Any]) -> httpx.Response:
        url = f"{self.base_url}/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        if self._client is not None:
            return await self._client.post(url, headers=headers, json=body)
        async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
            return await client.post(url, headers=headers, json=body)


class WorkflowModelRouter:
    """Use a remote model when configured and disclose local fallback use."""

    def __init__(
        self,
        primary: StructuredModel,
        fallback: StructuredModel | None = None,
    ) -> None:
        self.primary = primary
        self.fallback = fallback
        self.model_name = primary.model_name

    async def generate(
        self,
        schema: type[SchemaT],
        *,
        system_prompt: str,
        payload: dict[str, Any],
        purpose: str,
    ) -> StructuredGeneration[SchemaT]:
        try:
            return await self.primary.generate(
                schema,
                system_prompt=system_prompt,
                payload=payload,
                purpose=purpose,
            )
        except WorkflowModelError:
            if self.fallback is None:
                raise
            return await self.fallback.generate(
                schema,
                system_prompt=system_prompt,
                payload=payload,
                purpose=purpose,
            )


def get_creative_model(settings: Settings | None = None) -> StructuredModel:
    return _build_model(settings or Settings.from_env(), temperature=0.7, review=False)


def get_review_model(settings: Settings | None = None) -> StructuredModel:
    return _build_model(settings or Settings.from_env(), temperature=0.1, review=True)


def _build_model(
    settings: Settings,
    *,
    temperature: float,
    review: bool,
) -> StructuredModel:
    local = LocalStructuredModel()
    if not settings.openai_api_key:
        return local
    model = (
        settings.openai_review_model if review else settings.openai_creative_model
    )
    remote = OpenAICompatibleStructuredModel(
        api_key=settings.openai_api_key,
        base_url=settings.openai_base_url,
        model=model,
        temperature=temperature,
        timeout_seconds=settings.openai_timeout_seconds,
    )
    return WorkflowModelRouter(
        remote,
        fallback=local if settings.openai_fallback_enabled else None,
    )


def _non_negative_int(value: object) -> int:
    try:
        return max(0, int(value))  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0
