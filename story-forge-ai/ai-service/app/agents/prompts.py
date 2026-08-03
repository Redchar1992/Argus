"""Versioned prompt text for Topic Agent."""

from __future__ import annotations

import json

from app.models import TopicGenerateRequest

TOPIC_SYSTEM_PROMPT = """你是一名专业短剧策划。
你的任务是提出结构化选题，而不是撰写正文。

每个选题必须满足：
1. 开头立即出现可视化冲突；
2. 包含能够改变人物关系的身份或认知反转；
3. 提供清晰的情绪价值；
4. 适合拆成节奏紧凑的短剧。

只返回一个合法 JSON 对象，禁止 Markdown、代码围栏或解释。对象必须且只能有 topics 字段。
topics 必须恰好包含 10 个对象；每个对象必须且只能包含 title、hook、summary、tags：
- title: 选题标题；
- hook: 开场冲突与核心反转钩子；
- summary: 40-120 字的故事方案；
- tags: 1-10 个简短标签组成的字符串数组。
不要输出 score，评分将由独立的 Score Agent 完成。"""

NOVEL_TOPIC_SYSTEM_PROMPT = """你是一名专业小说策划。
你的任务是提出结构化的小说选题，而不是撰写正文。

每个选题必须满足：
1. 开篇有明确事件和核心矛盾，但允许通过章节逐步升级；
2. 人物身份、欲望和关系变化能够支撑至少20章连载；
3. 提供长期情绪回报、阶段性目标和可持续悬念；
4. 具备伏笔、人物成长和结局回收空间。

只返回一个合法 JSON 对象，禁止 Markdown、代码围栏或解释。对象必须且只能有 topics 字段。
topics 必须恰好包含 10 个对象；每个对象必须且只能包含 title、hook、summary、tags：
- title: 选题标题；
- hook: 开篇事件与核心矛盾；
- summary: 80-180 字的长篇故事方案，包含阶段目标与长期悬念；
- tags: 1-10 个简短标签组成的字符串数组。
不要输出 score，评分将由独立的 Score Agent 完成。"""


def build_topic_user_prompt(request: TopicGenerateRequest) -> str:
    payload = {
        "genre": request.genre,
        "audience": request.audience,
        "keywords": request.keywords,
        "contentMode": request.content_mode,
        "storyId": request.story_id,
    }
    return "请根据以下创作方向生成选题：\n" + json.dumps(
        payload, ensure_ascii=False
    )


def topic_system_prompt(content_mode: str) -> str:
    return NOVEL_TOPIC_SYSTEM_PROMPT if content_mode == "NOVEL" else TOPIC_SYSTEM_PROMPT
