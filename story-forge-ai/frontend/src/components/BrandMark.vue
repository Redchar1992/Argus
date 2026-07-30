<script setup lang="ts">
withDefaults(
  defineProps<{
    compact?: boolean
    inverse?: boolean
  }>(),
  {
    compact: false,
    inverse: false,
  },
)
</script>

<template>
  <div class="brand-mark" :class="{ compact, inverse }" aria-label="Story Forge AI">
    <span class="brand-symbol" aria-hidden="true">
      <span class="spark spark-one" />
      <span class="spark spark-two" />
      <span class="page-line" />
    </span>
    <span v-if="!compact" class="brand-copy">
      <strong>Story Forge</strong>
      <small>AI 故事工坊</small>
    </span>
  </div>
</template>

<style scoped>
.brand-mark {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  color: var(--sf-ink-strong);
}

.brand-symbol {
  position: relative;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  overflow: hidden;
  border-radius: 12px;
  background: linear-gradient(145deg, #7567ff 0%, #5140d8 58%, #392994 100%);
  box-shadow: 0 9px 24px rgba(83, 65, 213, 0.28);
}

.brand-symbol::before {
  position: absolute;
  top: 10px;
  left: 10px;
  width: 15px;
  height: 18px;
  border: 1.5px solid rgba(255, 255, 255, 0.9);
  border-radius: 3px 6px 6px 3px;
  content: '';
  transform: skewY(-7deg);
}

.page-line {
  position: absolute;
  top: 16px;
  left: 14px;
  width: 8px;
  height: 1px;
  background: rgba(255, 255, 255, 0.8);
  box-shadow: 0 4px 0 rgba(255, 255, 255, 0.6);
}

.spark {
  position: absolute;
  border-radius: 999px;
  background: #ffcc80;
}

.spark-one {
  top: 7px;
  right: 7px;
  width: 4px;
  height: 4px;
  box-shadow: 0 0 8px #ffcc80;
}

.spark-two {
  right: 6px;
  bottom: 8px;
  width: 2px;
  height: 2px;
}

.brand-copy {
  display: grid;
  gap: 1px;
  line-height: 1;
}

.brand-copy strong {
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 19px;
  letter-spacing: -0.25px;
}

.brand-copy small {
  color: var(--sf-ink-muted);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 2.1px;
  text-transform: uppercase;
}

.inverse,
.inverse .brand-copy small {
  color: #fff;
}

.inverse .brand-copy small {
  opacity: 0.62;
}

.compact .brand-symbol {
  width: 36px;
  height: 36px;
  flex-basis: 36px;
}
</style>
