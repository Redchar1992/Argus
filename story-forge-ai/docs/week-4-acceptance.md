# 第四周验收清单

## 已交付

- 全书终审只读取 `APPROVED` 章节版本，输出人物、时间线、剧情线、伏笔、节奏和商业问题。
- 报告包含内容完成度、爆款潜力、短剧适配度、综合分/等级、章节证据和修改建议。
- 报告问题可以跳转到对应章节工作台；报告版本可以再次查看。
- 正式版本 `story_release` 保存章节版本 ID、报告 ID、字数和内容哈希，锁定后不会覆盖旧版本。
- 支持 TXT、Markdown、DOCX、JSON 四种导出；下载令牌有效期由配置控制，默认 15 分钟。
- Prompt 支持 DRAFT / TESTING / PUBLISHED / RETIRED 版本流转，已发布版本不可直接修改，可通过回滚影响新任务。
- 记录模型调用的 Token、模型、Prompt 版本、耗时和估算成本；估算值明确标记为 `ESTIMATED`。
- AI 积分支持欢迎额度、幂等结算、冻结/释放流水；失败不会留下重复结算流水。
- 支持用户查看积分余额、消费记录和提交内测反馈。

## 关键接口

```text
POST /api/stories/{storyId}/final-reviews
GET  /api/stories/{storyId}/final-reviews/latest
POST /api/stories/{storyId}/releases
POST /api/stories/{storyId}/exports
GET  /api/exports/{exportId}
GET  /api/exports/{exportId}/download?token=...
GET  /api/me/ai-wallet
GET  /api/me/ai-wallet/logs
POST /api/stories/{storyId}/feedback
```

## 本周不扩大范围

当前导出实现把文件写入后端配置的本地目录，并以命名卷保证 Compose 重启后仍在；生产环境应将同一对象路径适配到 MinIO/S3，并配合 `deploy/backup.sh` 做 MySQL 和 Checkpointer 恢复演练。没有继续运行耗时的全量安全审查。

## 内测任务

邀请 10～20 名作者完成：注册 → 创建故事 → 生成选题 → 人物/大纲 → 至少三章 → 全书终审 → 锁定正式版本 → 导出 DOCX → 提交反馈。重点记录任务成功率、三章完成率、报告查看率、导出成功率、单篇成本和重复使用意愿。
