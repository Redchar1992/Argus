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
