# 第二周验收清单

第二周只扩展第一周已经选中的故事方案，不生成正文。目标链路为：

```text
选中方案
  → 生成人物卡
  → 生成 20 节点大纲
  → 五维评分
  → 低于 80 分时最多自动修订两次
  → 人工批准或提出修改意见
  → 保存正式版本并可再次查看
```

## 一键验收

```bash
# AI Service：Schema、LangGraph 暂停/恢复、自动修订与 Redis 幂等
cd story-forge-ai/ai-service
pytest
ruff check .

# Backend：JWT 归属、任务状态、产物版本和审核命令
cd ../backend
mvn -B verify

# Frontend：轮询、审核交互与生产构建
cd ../frontend
npm test -- --run
npm run build
```

## 结构化内容

- [ ] 每次生成 3～6 个人物，至少包含主角和核心反派。
- [ ] 人物姓名不重复，每个人物都有公开身份、秘密、欲望和人物弧光。
- [ ] 大纲恰好包含 20 个节点，编号严格为 1～20。
- [ ] 前 3 个节点建立核心冲突，至少 4 个节点标记为有效反转。
- [ ] 第 20 个节点有明确结局和情绪释放。
- [ ] 五个评分维度都在 0～20，`total` 由应用代码求和。
- [ ] 低于 80 分会修订，但自动修订次数不超过 2。

## 状态机与人工审核

- [ ] 首次执行使用稳定的 `threadId`，进入 `REVIEW_REQUIRED` 后图已暂停。
- [ ] 使用同一个 `threadId` 提交批准后，任务进入 `SUCCESS`。
- [ ] 提交修改意见后恢复原线程，生成新大纲版本并再次进入审核。
- [ ] 页面刷新后可通过任务 ID 恢复进度或审核内容。
- [ ] 前端仅每 2 秒轮询，不使用 WebSocket。

## Redis Streams 与幂等

- [ ] 请求写入 `story:workflow:requests`，事件写入 `story:workflow:events`。
- [ ] Worker 使用消费者组读取，处理完成并发布结果后才执行 `XACK`。
- [ ] 超时的 pending 消息可以通过 `XAUTOCLAIM` 被重新领取。
- [ ] 重复处理同一个 `idempotencyKey` 不创建重复任务或产物版本。
- [ ] 失败任务保存 `errorCode` 和可定位的 `errorMessage`。

## 数据留痕

- [ ] `CHARACTER`、`OUTLINE`、`SCORE`、`WORKFLOW_FINAL` 都保存为 `story_artifact`。
- [ ] 新修订创建更高 `versionNo`，不会覆盖旧版本。
- [ ] 每个产物记录模型名和 Prompt 版本。
- [ ] 任务保留当前节点、尝试次数、线程 ID 和幂等键。
- [ ] 用户只能读取和审核自己故事下的任务。

## 固定回归题材

至少覆盖以下 10 组输入：都市婚姻复仇、真假千金家庭冲突、职场女性逆袭、
民俗悬疑老宅、时间循环救援、校园秘密调查、亲情误解与真相、遗产争夺、
医疗悬疑、轻科幻身份替换。

## 明确不验收

- 正文和长文本生成
- 长期记忆或知识库检索
- 多模型路由
- WebSocket
- 支付、额度和自动投稿
