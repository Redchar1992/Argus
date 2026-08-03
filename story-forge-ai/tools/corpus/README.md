# GitHub 小说测试语料导入

`import_github_corpus.py` 使用已登录的 `gh` CLI，从指定私有仓库和固定
commit 拉取 Markdown/TXT 文件，在本地完成编码清洗与章节拆分，并生成带内容
哈希的 manifest。

```bash
gh auth status
python tools/corpus/import_github_corpus.py \
  --config tools/corpus/corpus-config.example.json \
  --output /tmp/story-forge-corpus \
  --manifest /tmp/story-forge-corpus/manifest.json
```

注意：

- config 中应显式列出首批样本，避免误拉取整个账号的作品。
- 输出目录包含原文，只应放在本地或私有存储，不提交到应用仓库。
- manifest 会记录仓库、解析后的 commit SHA、源文件路径、内容 SHA-256、章节数和字数。
- 没有章节标题的短篇会被保留为单章 fixture；识别到 `第 N 章`、`Chapter N`、序章、楔子或尾声时才拆章。

## 异常 fixture 与远程 smoke test

```bash
ai-service/.venv/bin/pytest -q tools/corpus/test_edge_fixtures.py
```

`fixtures/edge-cases/` 只包含合成的无标题、空章、重复标题和前置简介样本；超长
章节与 GB18030 在测试中动态生成。

远程模型 smoke test 默认跳过，只有显式确认且配置了 HTTPS OpenAI-compatible
服务才会发起一次不含原文的 JSON Schema 请求：

```bash
STORY_FORGE_REMOTE_SMOKE_CONFIRM=I_UNDERSTAND \
ai-service/.venv/bin/python tools/corpus/run_remote_smoke.py \
  --ai-service-root ai-service \
  --confirm-remote \
  --report /tmp/story-forge-remote-smoke.json
```

脚本不会发送导入语料，仅使用合成 topic；缺少确认、密钥或 HTTPS endpoint 时返回
`SKIPPED`，不会失败退出。

## 100+ 章性能基准

使用合成文本运行 120 章基准，不读取作者原文：

```bash
ai-service/.venv/bin/python tools/corpus/run_long_novel_benchmark.py \
  --ai-service-root ai-service \
  --chapter-count 120 \
  --report /tmp/story-forge-long-novel.json
```

基准覆盖章节解析、40,000 字符上下文预算、240 节点小说大纲和 120 章终审 schema，
并将结果写为不包含正文的结构化报告。

章节生成闭环基准：

```bash
ai-service/.venv/bin/python tools/corpus/run_chapter_workflow_benchmark.py \
  --ai-service-root ai-service \
  --chapter-count 120 \
  --report /tmp/story-forge-chapter-workflow.json
```

该基准逐章执行 `PLAN → GENERATE → APPROVE`，验证每章双节点、章节审核、正文摘要和
长期记忆更新；仅使用本地模板和合成数据。
