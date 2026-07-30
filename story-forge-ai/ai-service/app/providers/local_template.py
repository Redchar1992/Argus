"""Deterministic zero-key topic provider for local MVP demonstrations."""

from __future__ import annotations

from app.models import ProviderResult, ProviderTopic, TopicGenerateRequest


class LocalTemplateProvider:
    """Create useful, repeatable examples without pretending to be an LLM."""

    model_name = "local-template"

    async def generate(self, request: TopicGenerateRequest) -> ProviderResult:
        genre = request.genre
        audience = request.audience
        key = request.keywords[0] if request.keywords else genre
        extra_tags = request.keywords[:3]

        seeds = [
            (
                f"离婚当天，我继承了{key}帝国",
                "签字现场遭到羞辱，下一秒失踪多年的继承人身份公开。",
                f"一位长期被轻视的主角在离婚现场失去一切，却因一份遗嘱成为行业掌权人，并在旧关系与新责任之间完成反击，面向{audience}提供逆袭爽感。",
                ["离婚", "继承", "逆袭"],
            ),
            (
                f"全城封杀后，仇人求我拯救{key}",
                "主角被当众封杀，同一晚对手的核心项目只有主角能救。",
                f"被栽赃离场的天才握有唯一解决方案，在拒绝与救赎之间设局查清真相，让施害者逐层暴露，形成适合{audience}追看的连续反转。",
                ["复仇", "职场", "反转"],
            ),
            (
                f"替妹妹嫁入豪门后，我成了{key}掌舵人",
                "婚礼前被迫替嫁，洞房夜却发现新郎也在隐藏身份。",
                f"两个被家族当作棋子的人表面互相试探，实际联手夺回选择权；每次身份揭露都改变权力关系，兼具情感拉扯与{genre}节奏。",
                ["替嫁", "豪门", "双强"],
            ),
            (
                f"直播审判前任，我却曝光了{key}真相",
                "前任带着伪造证据直播控诉，主角必须在十分钟内自证。",
                "舆论最低谷中，主角通过三段关键证据反转直播审判，并发现幕后操盘者来自最信任的人，最终夺回名誉与事业。",
                ["直播", "舆论", "证据反杀"],
            ),
            (
                f"失忆三年后，我认出了{key}凶手",
                "庆功宴上记忆突然复苏，身边爱人竟出现在案发片段里。",
                "主角一边假装仍然失忆，一边用碎片线索调查旧案；看似危险的爱人其实在暗中保护，真正的背叛者逐渐逼近。",
                ["悬念", "失忆", "情感反转"],
            ),
            (
                f"被赶出家门后，我的{key}账号爆红了",
                "家宴上被断绝关系，转身发布的第一条视频意外揭开家族秘密。",
                "主角靠真实创作重新生活，爆红账号却牵出被调换的人生；亲情、利益和公众目光不断碰撞，最终由主角定义自己的价值。",
                ["成长", "新媒体", "身世"],
            ),
            (
                f"我给死对头当助理，第一天就接管{key}",
                "入职即被设计背锅，主角却用隐藏技能救下公司。",
                "主角为查清旧案潜入对手公司，从针锋相对到发现双方都被同一幕后势力利用，在合作破局中产生高密度情感张力。",
                ["职场", "死对头", "强强联手"],
            ),
            (
                f"婚礼倒计时，我收到未来的{key}警告",
                "婚礼前七天，主角收到一段来自未来、记录背叛现场的视频。",
                "每到午夜都会出现一段未来片段，主角必须在婚礼前判断谁在说谎；最终发现发送警告的正是另一个时间线的自己。",
                ["倒计时", "未来信息", "悬疑情感"],
            ),
            (
                f"假千金离开后，{key}家族崩盘了",
                "真千金回归当天，主角主动离开，家族业务却接连失控。",
                "所有人以为主角依附家族，实则多年危机都由她解决；离开后她建立新事业，也揭开当年身份错换并非意外。",
                ["真假千金", "事业逆袭", "家族秘密"],
            ),
            (
                f"重回签约那天，我拒绝了{key}顶流",
                "带着失败记忆重来，主角第一件事就是撕掉改变命运的合约。",
                f"主角利用有限先机避开旧陷阱，却发现关键人物也保留前世记忆；两人从争夺机会转为联手改写结局，满足{audience}的成长期待。",
                ["重启人生", "娱乐圈", "命运反转"],
            ),
        ]

        topics = [
            ProviderTopic(
                title=title,
                hook=hook,
                summary=summary,
                tags=list(dict.fromkeys([genre, *extra_tags, *tags])),
            )
            for title, hook, summary, tags in seeds
        ]
        return ProviderResult(topics=topics, model=self.model_name)
