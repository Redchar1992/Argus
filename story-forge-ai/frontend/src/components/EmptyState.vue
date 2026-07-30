<script setup lang="ts">
import { MagicStick, Plus } from '@element-plus/icons-vue'

withDefaults(
  defineProps<{
    title?: string
    description?: string
    actionLabel?: string
    compact?: boolean
  }>(),
  {
    title: '还没有故事',
    description: '输入一个创作方向，让 AI 策划为你生成第一组故事方案。',
    actionLabel: '新建故事',
    compact: false,
  },
)

defineEmits<{
  action: []
}>()
</script>

<template>
  <section class="empty-state" :class="{ compact }">
    <div class="empty-visual" aria-hidden="true">
      <span class="orbit orbit-one" />
      <span class="orbit orbit-two" />
      <el-icon><MagicStick /></el-icon>
    </div>
    <h2>{{ title }}</h2>
    <p>{{ description }}</p>
    <el-button type="primary" size="large" :icon="Plus" @click="$emit('action')">
      {{ actionLabel }}
    </el-button>
  </section>
</template>

<style scoped>
.empty-state {
  display: grid;
  min-height: 360px;
  place-items: center;
  align-content: center;
  padding: 48px 24px;
  border: 1px dashed #dcd6e7;
  border-radius: 24px;
  text-align: center;
  background:
    radial-gradient(circle at center, rgba(105, 87, 220, 0.06), transparent 31%),
    rgba(255, 255, 255, 0.68);
}

.empty-state.compact {
  min-height: 290px;
}

.empty-visual {
  position: relative;
  display: grid;
  width: 90px;
  height: 90px;
  margin-bottom: 20px;
  place-items: center;
  border-radius: 50%;
  color: var(--sf-primary);
  background: #fff;
  box-shadow: 0 16px 35px rgba(68, 49, 153, 0.12);
}

.empty-visual > .el-icon {
  font-size: 33px;
}

.orbit {
  position: absolute;
  border-radius: 50%;
  background: var(--sf-accent);
}

.orbit-one {
  top: 3px;
  right: 8px;
  width: 8px;
  height: 8px;
  box-shadow: 0 0 0 5px #fff0e9;
}

.orbit-two {
  bottom: 12px;
  left: 2px;
  width: 5px;
  height: 5px;
  background: #f2b25f;
}

h2 {
  margin: 0;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 23px;
}

p {
  max-width: 440px;
  margin: 10px auto 23px;
  color: var(--sf-ink-muted);
  font-size: 13px;
  line-height: 1.8;
}

.el-button {
  min-width: 138px;
  border-radius: 12px;
}
</style>
