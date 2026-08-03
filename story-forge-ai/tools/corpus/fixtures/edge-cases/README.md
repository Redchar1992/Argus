# 异常章节 fixture

这些是专门用于解析器回归的合成样本，不来自作者的 GitHub 原文：

- `headingless-short.txt`：无章节标题短篇
- `empty-chapter.md`：空章与正常章节相邻
- `duplicate-chapter.md`：重复章节标题
- `missing-heading-prefix.md`：第一章前存在简介
- 超长章节和 GB18030 编码在测试中动态生成，避免把大文件或二进制文本提交到仓库
