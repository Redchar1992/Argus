"""Structured workflow-model abstraction with an explicit local fallback.

The first-week topic endpoint keeps its original provider. This module provides
the same OpenAI-compatible transport style for second-week Pydantic outputs,
while keeping agents independent from any one model SDK.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any, Generic, Protocol, TypeVar

import httpx
from pydantic import BaseModel, ValidationError

from app.config import Settings
from app.schemas.chapter import (
    ChapterPlan,
    ChapterReview,
    ChapterSummary,
    MemoryUpdate,
    ReviewDimension,
    RewriteProposal,
    ScenePlan,
)
from app.schemas.character import CharacterCard, CharacterPack
from app.schemas.final_review import FinalStoryReport
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


@dataclass(frozen=True, slots=True)
class TextDelta:
    """One ordinary-text model delta with optional final usage metadata."""

    text: str
    model_name: str
    input_tokens: int = 0
    output_tokens: int = 0
    done: bool = False


class TextModel(Protocol):
    model_name: str

    def stream_text(
        self,
        *,
        system_prompt: str,
        payload: dict[str, Any],
        purpose: str,
    ) -> AsyncIterator[TextDelta]:
        """Stream ordinary prose without wrapping it in structured JSON."""


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
            elif schema is ChapterPlan:
                value = self._chapter_plan(payload)
            elif schema is ChapterReview:
                value = self._chapter_review(payload)
            elif schema is ChapterSummary:
                value = self._chapter_summary(payload)
            elif schema is MemoryUpdate:
                value = self._memory_update(payload)
            elif schema is RewriteProposal:
                value = self._rewrite_proposal(payload)
            elif schema is FinalStoryReport:
                value = self._final_review(payload)
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

    async def stream_text(
        self,
        *,
        system_prompt: str,
        payload: dict[str, Any],
        purpose: str,
    ) -> AsyncIterator[TextDelta]:
        del system_prompt
        if purpose not in {"chapter_write", "chapter_revision"}:
            raise WorkflowModelError(f"local model does not support {purpose}")
        content = self._chapter_text(
            payload,
            revised=purpose == "chapter_revision",
        )
        encoded_input = json.dumps(payload, ensure_ascii=False)
        # Sentence-sized chunks mimic real token streams without delaying tests.
        chunk_size = 24
        for start in range(0, len(content), chunk_size):
            yield TextDelta(
                text=content[start : start + chunk_size],
                model_name=self.model_name,
            )
        yield TextDelta(
            text="",
            model_name=self.model_name,
            input_tokens=max(1, len(encoded_input) // 4),
            output_tokens=max(1, len(content) // 4),
            done=True,
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
                    protagonist_goal=("保全证据、查清真相，并避免无辜者成为反击代价"),
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

    def _chapter_plan(self, payload: dict[str, Any]) -> ChapterPlan:
        characters = payload.get("characters") or []
        names = [
            str(item.get("name", "")).strip()
            for item in characters
            if isinstance(item, dict) and item.get("name")
        ]
        if not names:
            names = ["林晚", "顾承泽", "苏晴"]
        protagonist = next(
            (str(item["name"]) for item in characters if item.get("role") == "主角"),
            names[0],
        )
        opponent = next(
            (str(item["name"]) for item in characters if item.get("role") == "反派"),
            names[1] if len(names) > 1 else names[0],
        )
        ally = names[2] if len(names) > 2 else protagonist
        chapter_no = int(payload.get("chapter_no", 1))
        target = min(5000, max(800, int(payload.get("target_length", 1200))))
        current_nodes = payload.get("currentOutlineNodes") or payload.get(
            "outlineNodes"
        )
        if not isinstance(current_nodes, list) or len(current_nodes) != 2:
            raise ValueError(
                "chapter planning requires exactly two current outline nodes"
            )

        def node_field(node: dict[str, Any], camel: str, snake: str) -> str:
            return str(node.get(camel, node.get(snake, ""))).strip()

        def anchor(value: str) -> str:
            return "".join(value.split())[:24]

        outline_contract = [
            {
                "event": anchor(node_field(node, "event", "event")),
                "goal": anchor(node_field(node, "protagonistGoal", "protagonist_goal")),
                "information": node_field(node, "newInformation", "new_information"),
                "cliffhanger": node_field(node, "cliffhanger", "cliffhanger"),
            }
            for node in current_nodes
            if isinstance(node, dict)
        ]
        if len(outline_contract) != 2 or any(
            not item["event"] or not item["goal"] for item in outline_contract
        ):
            raise ValueError("current outline nodes require event and protagonistGoal")
        scene_functions = ("建立", "升级", "反转", "高潮")
        scenes = []
        for index, scene_function in enumerate(scene_functions, start=1):
            beat = outline_contract[0 if index <= 2 else 1]
            scene_characters = (
                [protagonist, opponent] if index in {1, 4} else [protagonist, ally]
            )
            scenes.append(
                ScenePlan(
                    scene_no=index,
                    location=("集团会议室" if index == 1 else f"线索地点{index}"),
                    time=("清晨" if index == 1 else "当天稍后"),
                    characters=list(dict.fromkeys(scene_characters)),
                    protagonist_goal=beat["goal"],
                    opposing_force=f"{opponent}安排的现实阻碍",
                    visible_conflict=f"落实大纲事件：{beat['event']}，并突破现场阻拦",
                    information_revealed=(
                        beat["information"] or f"第{index}条线索改变人物判断"
                    ),
                    emotional_change="由迟疑转为主动，并承担行动代价",
                    setup_or_payoff=(
                        "埋下账本来源疑点" if index < 4 else "回收前一场的证据异常"
                    ),
                    exit_hook=(
                        beat["cliffhanger"] or f"新的记录指向第{index + 1}层利益关系"
                    ),
                    scene_function=scene_function,  # type: ignore[arg-type]
                )
            )
        return ChapterPlan(
            chapter_title=f"第{chapter_no}章 {outline_contract[0]['event'][:18]}",
            chapter_goal=(
                f"完成事件「{outline_contract[0]['event']}」与目标「{outline_contract[0]['goal']}」；"
                f"完成事件「{outline_contract[1]['event']}」与目标「{outline_contract[1]['goal']}」"
            ),
            opening_hook=f"{outline_contract[0]['event']}突然打破原有局面",
            ending_hook=(
                outline_contract[1]["cliffhanger"]
                or f"{outline_contract[1]['event']}带来新的未解后果"
            ),
            target_length=target,
            scenes=scenes,
        )

    def _chapter_text(
        self,
        payload: dict[str, Any],
        *,
        revised: bool,
    ) -> str:
        plan = payload.get("chapter_plan") or payload.get("chapterPlan") or {}
        characters = payload.get("characters") or []
        names = [
            str(item.get("name", "")).strip()
            for item in characters
            if isinstance(item, dict) and item.get("name")
        ]
        protagonist = next(
            (str(item["name"]) for item in characters if item.get("role") == "主角"),
            names[0] if names else "林晚",
        )
        opponent = next(
            (str(item["name"]) for item in characters if item.get("role") == "反派"),
            names[1] if len(names) > 1 else "顾承泽",
        )
        target = min(
            5000,
            max(
                800,
                int(
                    plan.get("target_length")
                    or plan.get("targetLength")
                    or payload.get("target_length", 1200)
                ),
            ),
        )
        scenes = plan.get("scenes") or [{} for _ in range(4)]
        paragraphs = [
            (
                f"{protagonist}推开会议室的门时，墙上的投影忽然闪了一下。原本的议程被一张转账记录取代，红色数字压在她的名字旁边。众人的手机同时震动，议论声还没成形，{opponent}已经伸手去拔数据线。她先一步按住接口：‘谁删掉原件，谁就承认看过它。’空气在这一刻彻底改变。"
            )
        ]
        for index, scene in enumerate(scenes, start=1):
            location = scene.get("location", f"线索地点{index}")
            goal = scene.get(
                "protagonist_goal",
                scene.get("protagonistGoal", "查清证据来源"),
            )
            conflict = scene.get(
                "visible_conflict",
                scene.get("visibleConflict", "对手试图阻止调查"),
            )
            revealed = scene.get(
                "information_revealed",
                scene.get("informationRevealed", "线索指向新的利益关系"),
            )
            extra = (
                "她没有用一句判断替代证据，而是逐项核对时间、签名与门禁记录。"
                if revised
                else "她压住立刻质问的冲动，把每个动作记在心里。"
            )
            paragraphs.append(
                f"在{location}，{protagonist}要{goal}。{conflict}。她看见对方手指停在删除键上，便将备份发送给在场三人，让任何一次销毁都留下痕迹。{extra}{revealed}，先前看似无关的沉默因此有了具体代价。"
            )
            paragraphs.append(
                f"第{index}次交锋中，{opponent}把声音压得很低，要求她立刻"
                f"离开。{protagonist}没有后退，只把记录的生成时间念了出来。"
                "门外脚步突然停住，有人显然听见了不该听见的名字。她改变"
                "原计划，故意放出一条不完整的信息，等待真正害怕的人先行动。"
            )
        ending = str(
            plan.get("ending_hook")
            or plan.get("endingHook")
            or "记录最后的收款人姓名，竟属于她最信任的人"
        )
        paragraphs.append(
            f"电梯门合拢前，备份文件终于解密。{protagonist}盯着最后一行，指尖停在屏幕上——{ending}。与此同时，身后那部本应关机的手机，亮起了正在通话的红点。"
        )
        content = "\n\n".join(paragraphs)
        filler = (
            f"\n\n{protagonist}重新排列证据，把亲眼所见、他人转述和仍待"
            "核验的部分分开。每一处时间差都对应一个人的行动，她不允许愤怒"
            "替自己补完缺失的因果。走廊尽头传来门锁声，提醒她留给真相的"
            "时间正在减少。"
        )
        while len(content) < target * 0.9:
            content += filler
        return content[: int(target * 1.08)]

    def _chapter_review(self, payload: dict[str, Any]) -> ChapterReview:
        revision = int(payload.get("revision_count", 0))
        if revision == 0:
            scores = (15, 16, 15, 11, 11, 8)
        else:
            scores = (18, 18, 17, 13, 12, 8)
        names = (
            ("outline_completion", 20),
            ("continuity", 20),
            ("conflict_progression", 20),
            ("emotion_and_visuals", 15),
            ("hooks", 15),
            ("language_quality", 10),
        )
        dimensions = {
            name: ReviewDimension(
                score=score,
                max_score=maximum,  # type: ignore[arg-type]
                evidence=["正文包含对应的具体行动、阻力或信息变化"],
                problems=(["局部因果或画面仍可增强"] if revision == 0 else []),
                suggestions=["用可观察动作替代概括，并提前呈现行动代价"],
            )
            for (name, maximum), score in zip(names, scores, strict=True)
        }
        return ChapterReview(
            **dimensions,  # type: ignore[arg-type]
            fatal_problems=[],
            rewrite_instructions=(
                ["保留场景顺序，补足第二场阻力和证据核验动作"]
                if revision == 0
                else ["压缩少量重复情绪表达"]
            ),
            should_rewrite=revision == 0,
        )

    def _chapter_summary(self, payload: dict[str, Any]) -> ChapterSummary:
        chapter_no = int(payload.get("chapter_no", 1))
        return ChapterSummary(
            chapter_no=chapter_no,
            summary="主角在公开冲突中保全异常转账记录，通过时间与门禁证据锁定新的利益关联，并在章末发现可信之人可能牵涉其中。",
            main_events=[
                "异常转账记录在会议现场曝光",
                "主角完成证据备份并用核验行动阻止销毁",
                "解密记录指向意外关联人",
            ],
            character_changes=["主角从被动防守转为主动设置验证陷阱"],
            new_facts=[
                {
                    "factKey": f"chapter_{chapter_no}_transfer_record",
                    "factType": "EVIDENCE",
                    "subject": "异常转账记录",
                    "predicate": "已被备份",
                    "value": "主角和两名见证人持有副本",
                    "visibility": "PUBLIC",
                    "sourceChapter": chapter_no,
                    "locked": False,
                }
            ],
            opened_threads=[
                {
                    "threadKey": f"chapter_{chapter_no}_recipient",
                    "description": "意外收款人与核心事件的真实关系",
                    "introducedChapter": chapter_no,
                    "status": "OPEN",
                    "knownClues": ["解密后的收款人姓名"],
                }
            ],
            resolved_threads=[],
            ending_hook="本应关机的手机正在向未知对象通话",
        )

    def _memory_update(self, payload: dict[str, Any]) -> MemoryUpdate:
        chapter_no = int(payload.get("chapter_no", 1))
        characters = payload.get("characters") or []
        protagonist = next(
            (str(item["name"]) for item in characters if item.get("role") == "主角"),
            "林晚",
        )
        opponent = next(
            (str(item["name"]) for item in characters if item.get("role") == "反派"),
            "顾承泽",
        )
        facts = payload.get("canon_facts") or payload.get("canonFacts") or []
        locked = {
            str(item.get("factKey") or item.get("fact_key"))
            for item in facts
            if isinstance(item, dict) and item.get("locked")
        }
        warnings = (
            [f"锁定事实保持不变：{key}" for key in sorted(locked)] if locked else []
        )
        return MemoryUpdate(
            new_facts=[
                {
                    "factKey": f"chapter_{chapter_no}_evidence_backup",
                    "factType": "EVIDENCE",
                    "subject": "异常转账记录",
                    "predicate": "持有状态",
                    "value": "存在多个可核验副本",
                    "visibility": "PUBLIC",
                    "sourceChapter": chapter_no,
                    "locked": False,
                }
            ],
            changed_relationships=[
                {
                    "characterA": protagonist,
                    "characterB": opponent,
                    "relation": "公开对抗",
                    "trust": 0,
                    "conflict": 90,
                    "updatedAtChapter": chapter_no,
                }
            ],
            opened_threads=[
                {
                    "threadKey": f"chapter_{chapter_no}_recipient",
                    "description": "调查意外收款人的真实立场",
                    "introducedChapter": chapter_no,
                    "status": "OPEN",
                }
            ],
            updated_threads=[],
            resolved_threads=[],
            new_foreshadowing=[
                {
                    "foreshadowKey": f"chapter_{chapter_no}_phone_call",
                    "setup": "关机手机出现通话红点",
                    "setupChapter": chapter_no,
                    "status": "SETUP",
                }
            ],
            paid_off_foreshadowing=[],
            character_state_changes=[
                {
                    "character": protagonist,
                    "field": "evidenceCopies",
                    "newValue": "掌握异常转账记录的多个副本",
                    "updatedAtChapter": chapter_no,
                }
            ],
            continuity_warnings=warnings,
        )

    def _rewrite_proposal(self, payload: dict[str, Any]) -> RewriteProposal:
        original = str(payload["selected_text"])
        action = str(payload.get("action", "CUSTOM"))
        if action == "COMPRESS":
            replacement = original.replace("非常", "").replace("开始", "")
        elif action == "EXPAND_DETAIL":
            replacement = (
                original + " 她听见纸页擦过桌面的细响，看到对方拇指在签名处停了半秒。"
            )
        else:
            replacement = (
                original + " 对方没有退让，反而伸手切断出口，让这次冲突产生了现实代价。"
            )
        return RewriteProposal(
            chapter_version_id=int(payload["chapter_version_id"]),
            original_text=original,
            replacement_text=replacement,
            reason=f"按{action}要求在选中范围内强化可见行动",
            selected_text_hash=str(payload["selected_text_hash"]),
        )

    def _final_review(self, payload: dict[str, Any]) -> FinalStoryReport:
        chapters = list(payload.get("chapters") or [])
        chapter_numbers = [
            int(item.get("chapterNo", item.get("chapter_no", 0))) for item in chapters
        ]
        contents = [str(item.get("content", "")) for item in chapters]
        all_text = "\n".join(contents)
        repeated = []
        if len(contents) >= 3 and len(set(contents)) < len(contents):
            repeated.append("存在完全重复的章节正文，建议检查版本快照并删除重复内容。")
        content_score = min(100, 70 + min(20, len(chapters) * 3) - len(repeated) * 10)
        hit_score = min(100, 72 + min(15, len(chapters) * 2))
        drama_score = min(100, 68 + min(20, len(chapters) * 2))
        issues = []
        if repeated:
            issues.append(
                {
                    "issueType": "REPETITION",
                    "severity": "HIGH",
                    "title": "章节正文重复",
                    "description": repeated[0],
                    "evidence": [
                        {
                            "chapterNo": chapter_numbers[index],
                            "description": "章节正文与其他章节完全一致",
                            "excerpt": contents[index][:200],
                        }
                        for index in range(min(2, len(contents)))
                    ],
                    "suggestedFix": "恢复对应章节的批准版本，并重新检查导出快照。",
                    "affectedChapters": chapter_numbers[:2],
                }
            )
        if not all_text.strip():
            issues.append(
                {
                    "issueType": "LANGUAGE",
                    "severity": "CRITICAL",
                    "title": "正文为空",
                    "description": "批准章节没有可供终审的正文。",
                    "evidence": [
                        {"chapterNo": chapter_numbers[0], "description": "正文为空"}
                    ],
                    "suggestedFix": "回到章节工作台完成正文并批准后重新终审。",
                    "affectedChapters": [chapter_numbers[0]],
                }
            )
        total = round(content_score * 0.4 + hit_score * 0.4 + drama_score * 0.2)
        level = (
            "S"
            if total >= 90
            else "A"
            if total >= 80
            else "B"
            if total >= 70
            else "C"
            if total >= 60
            else "D"
        )
        return FinalStoryReport.model_validate(
            {
                "contentQuality": {
                    "score": content_score,
                    "summary": "章节已形成可供全书检查的正文链路。",
                    "strengths": ["章节顺序清晰", "正文版本可追溯"],
                    "weaknesses": ["仍需人工确认跨章节人物和时间线"],
                },
                "hitPotential": {
                    "score": hit_score,
                    "summary": "开篇冲突和连续阅读动力具备基础。",
                    "strengths": ["冲突可视化", "具备情绪推进空间"],
                    "weaknesses": ["商业判断不等同于收益保证"],
                },
                "shortDramaAdaptation": {
                    "score": drama_score,
                    "summary": "当前文本可以继续拆解为短剧场景。",
                    "strengths": ["场景边界明确", "章末有继续阅读动力"],
                    "weaknesses": ["需要人工评估拍摄成本"],
                },
                "criticalIssues": [
                    item for item in issues if item["severity"] == "CRITICAL"
                ],
                "normalIssues": [
                    item for item in issues if item["severity"] != "CRITICAL"
                ],
                "unresolvedThreads": ["终审后请人工确认所有开放剧情线是否有结局"],
                "unresolvedForeshadowing": ["请检查早期证据与结尾回收是否一一对应"],
                "strongestChapters": chapter_numbers[-2:]
                if len(chapter_numbers) > 1
                else chapter_numbers,
                "weakestChapters": chapter_numbers[:1],
                "suggestedTitles": [str(payload.get("storyTitle") or "未命名故事")],
                "suggestedTags": [str(payload.get("genre") or "故事"), "全书终审"],
                "revisionOrder": [
                    "先处理CRITICAL/HIGH问题",
                    "再核对人物和伏笔状态",
                    "最后压缩重复表达",
                ],
                "total": total,
                "level": level,
                "disclaimer": (
                    "综合分仅表示系统按当前文本和规则得出的内容评估，不代表真实收益保证。"
                ),
            }
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
            raise WorkflowModelError(f"model returned invalid {purpose} JSON") from exc

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

    async def stream_text(
        self,
        *,
        system_prompt: str,
        payload: dict[str, Any],
        purpose: str,
    ) -> AsyncIterator[TextDelta]:
        """Consume the OpenAI-compatible SSE chat-completions protocol.

        Ollama exposes the same endpoint under ``/v1/chat/completions``. The
        API key may therefore be a harmless server-side placeholder.
        """

        body = {
            "model": self.model_name,
            "temperature": self.temperature,
            "stream": True,
            "stream_options": {"include_usage": True},
            "messages": [
                {"role": "system", "content": system_prompt},
                {
                    "role": "user",
                    "content": json.dumps(payload, ensure_ascii=False),
                },
            ],
        }
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        url = f"{self.base_url}/chat/completions"
        usage: dict[str, Any] = {}
        returned_model = self.model_name
        try:
            if self._client is not None:
                async with self._client.stream(
                    "POST",
                    url,
                    headers=headers,
                    json=body,
                    timeout=self.timeout_seconds,
                ) as response:
                    async for delta in self._read_text_stream(response):
                        if delta.done:
                            usage = {
                                "prompt_tokens": delta.input_tokens,
                                "completion_tokens": delta.output_tokens,
                            }
                            returned_model = delta.model_name
                        else:
                            yield delta
            else:
                async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                    async with client.stream(
                        "POST",
                        url,
                        headers=headers,
                        json=body,
                    ) as response:
                        async for delta in self._read_text_stream(response):
                            if delta.done:
                                usage = {
                                    "prompt_tokens": delta.input_tokens,
                                    "completion_tokens": delta.output_tokens,
                                }
                                returned_model = delta.model_name
                            else:
                                yield delta
        except (httpx.HTTPError, KeyError, TypeError, ValueError) as exc:
            raise WorkflowModelError(
                f"model returned invalid {purpose} text stream"
            ) from exc
        yield TextDelta(
            text="",
            model_name=returned_model,
            input_tokens=_non_negative_int(usage.get("prompt_tokens")),
            output_tokens=_non_negative_int(usage.get("completion_tokens")),
            done=True,
        )

    async def _read_text_stream(
        self,
        response: httpx.Response,
    ) -> AsyncIterator[TextDelta]:
        response.raise_for_status()
        model_name = self.model_name
        usage: dict[str, Any] = {}
        async for line in response.aiter_lines():
            line = line.strip()
            if not line or line.startswith(":"):
                continue
            if line == "data: [DONE]":
                break
            if not line.startswith("data:"):
                continue
            payload = json.loads(line[5:].strip())
            returned_model = payload.get("model")
            if isinstance(returned_model, str):
                model_name = returned_model
            raw_usage = payload.get("usage")
            if isinstance(raw_usage, dict):
                usage = raw_usage
            choices = payload.get("choices") or []
            if not choices:
                continue
            content = choices[0].get("delta", {}).get("content")
            if isinstance(content, str) and content:
                yield TextDelta(text=content, model_name=model_name)
        yield TextDelta(
            text="",
            model_name=model_name,
            input_tokens=_non_negative_int(usage.get("prompt_tokens")),
            output_tokens=_non_negative_int(usage.get("completion_tokens")),
            done=True,
        )


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

    async def stream_text(
        self,
        *,
        system_prompt: str,
        payload: dict[str, Any],
        purpose: str,
    ) -> AsyncIterator[TextDelta]:
        emitted = False
        try:
            async for delta in self.primary.stream_text(
                system_prompt=system_prompt,
                payload=payload,
                purpose=purpose,
            ):
                emitted = emitted or bool(delta.text)
                yield delta
            return
        except WorkflowModelError:
            # Never splice a fallback draft after remote prose was already
            # emitted; doing so would create corrupt mixed-model content.
            if emitted or self.fallback is None:
                raise
        async for delta in self.fallback.stream_text(
            system_prompt=system_prompt,
            payload=payload,
            purpose=purpose,
        ):
            yield delta


def get_creative_model(settings: Settings | None = None) -> StructuredModel:
    return _build_model(settings or Settings.from_env(), temperature=0.7, review=False)


def get_review_model(settings: Settings | None = None) -> StructuredModel:
    return _build_model(settings or Settings.from_env(), temperature=0.1, review=True)


def get_creative_text_model(settings: Settings | None = None) -> TextModel:
    return _build_model(settings or Settings.from_env(), temperature=0.7, review=False)


def _build_model(
    settings: Settings,
    *,
    temperature: float,
    review: bool,
) -> StructuredModel:
    local = LocalStructuredModel()
    provider = settings.model_provider
    use_remote = provider in {"openai-compatible", "ollama"} or (
        provider == "auto" and bool(settings.openai_api_key)
    )
    if not use_remote:
        return local
    model = settings.openai_review_model if review else settings.openai_creative_model
    if provider == "ollama":
        model = settings.ollama_model
    remote = OpenAICompatibleStructuredModel(
        api_key=settings.openai_api_key or "ollama",
        base_url=(
            settings.ollama_base_url
            if provider == "ollama"
            else settings.openai_base_url
        ),
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
