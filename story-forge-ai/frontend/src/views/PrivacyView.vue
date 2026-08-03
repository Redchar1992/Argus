<script setup lang="ts">
const privacyVersion = 'pilot-2026-08-01'
</script>

<template>
  <main class="privacy-page">
    <article>
      <router-link class="back-link" to="/login">← 返回登录</router-link>
      <span class="kicker">STORY FORGE PILOT</span>
      <h1>内测隐私说明</h1>
      <p class="version">版本：{{ privacyVersion }}</p>

      <section>
        <h2>我们收集什么</h2>
        <p>内测期间会保存账号、故事创作方向、AI 生成结果、人工修改版本、任务状态、模型用量、导出记录和主动提交的反馈。</p>
      </section>
      <section>
        <h2>为什么收集</h2>
        <p>数据只用于提供创作、恢复历史版本、计算内测漏斗、排查故障、控制成本，以及改进本产品的 Prompt 和工作流。</p>
      </section>
      <section>
        <h2>作品与模型训练</h2>
        <p>作品默认保持私有，不会公开展示，也不会自动加入公共训练集。只有在排查用户主动报告的问题时，获授权的内测管理员才会查看必要内容。</p>
      </section>
      <section>
        <h2>第三方模型</h2>
        <p>启用云端模型时，完成生成所需的最小文本会发送给服务器配置的模型供应商；API 密钥只保存在服务端，不会发送到浏览器。</p>
      </section>
      <section>
        <h2>保存、导出与删除</h2>
        <p>正式版本和导出文件会保存在内测服务器。需要删除账号或作品时，请通过邀请你参加内测的渠道联系管理员；管理员会确认身份并执行删除。</p>
      </section>
      <section>
        <h2>重要说明</h2>
        <p>商业评分是针对当前文本的辅助判断，不构成收益承诺、投稿保证或法律意见。请勿上传无权处理的作品或敏感个人信息。</p>
      </section>
    </article>
  </main>
</template>

<style scoped>
.privacy-page {
  min-height: 100vh;
  padding: 48px 20px;
  color: #27233f;
  background: #f6f4ef;
}

article {
  max-width: 780px;
  margin: 0 auto;
  padding: clamp(28px, 6vw, 64px);
  border: 1px solid rgba(43, 36, 78, 0.1);
  border-radius: 24px;
  background: #fff;
  box-shadow: 0 24px 70px rgba(36, 29, 70, 0.08);
}

.back-link {
  color: #6656c7;
  font-weight: 700;
  text-decoration: none;
}

.kicker {
  display: block;
  margin-top: 40px;
  color: #7868d7;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 2px;
}

h1 {
  margin: 12px 0 8px;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: clamp(32px, 7vw, 52px);
  font-weight: 500;
}

.version {
  margin-bottom: 42px;
  color: #817c92;
  font-size: 13px;
}

section + section {
  margin-top: 30px;
}

h2 {
  margin: 0 0 10px;
  font-size: 18px;
}

section p {
  margin: 0;
  color: #5d586d;
  line-height: 1.85;
}
</style>
