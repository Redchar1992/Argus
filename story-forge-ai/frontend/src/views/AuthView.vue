<script setup lang="ts">
import { ArrowRight, Check, Lock, MagicStick, User } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import BrandMark from '@/components/BrandMark.vue'
import { useAuthStore } from '@/stores/auth'
import { getErrorMessage } from '@/utils/error'

type AuthMode = 'login' | 'register'

interface AuthForm {
  username: string
  password: string
  confirmPassword: string
  privacyAccepted: boolean
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const formRef = ref<FormInstance>()
const mode = ref<AuthMode>(route.query.mode === 'register' ? 'register' : 'login')
const form = reactive<AuthForm>({
  username: '',
  password: '',
  confirmPassword: '',
  privacyAccepted: false,
})

const isRegister = computed(() => mode.value === 'register')

const rules: FormRules<AuthForm> = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名需为 3–32 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码需为 6–64 个字符', trigger: 'blur' },
  ],
  confirmPassword: [
    {
      validator: (_rule, value, callback) => {
        if (!isRegister.value) return callback()
        if (!value) return callback(new Error('请再次输入密码'))
        if (value !== form.password) return callback(new Error('两次输入的密码不一致'))
        callback()
      },
      trigger: 'blur',
    },
  ],
  privacyAccepted: [
    {
      validator: (_rule, value, callback) => {
        if (value === true) return callback()
        callback(new Error('请阅读并同意隐私说明'))
      },
      trigger: 'change',
    },
  ],
}

function switchMode(nextMode: AuthMode) {
  mode.value = nextMode
  form.confirmPassword = ''
  form.privacyAccepted = false
  formRef.value?.clearValidate()
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const credentials = {
    username: form.username.trim(),
    password: form.password,
  }

  try {
    if (isRegister.value) {
      await authStore.register({ ...credentials, privacyAccepted: true })
      ElMessage.success('账号创建成功，正在进入故事工坊')
    } else {
      await authStore.login({ ...credentials, privacyAccepted: true })
    }
    const redirect =
      typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
        ? route.query.redirect
        : '/'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(
      getErrorMessage(error, isRegister.value ? '注册失败，请稍后重试。' : '登录失败，请重试。'),
    )
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-story">
      <div class="story-glow glow-one" />
      <div class="story-glow glow-two" />
      <BrandMark inverse />

      <div class="hero-copy">
        <span class="hero-kicker">
          <el-icon><MagicStick /></el-icon>
          AI STORY PLANNING
        </span>
        <h1>把一个念头，<br />锻造成<em>值得开拍</em>的故事。</h1>
        <p>输入题材、受众和情绪方向，几分钟内收获 10 个结构化短剧选题。</p>
      </div>

      <div class="flow-card">
        <div class="flow-line">
          <span class="flow-step active">01</span>
          <i />
          <span class="flow-step">02</span>
          <i />
          <span class="flow-step">03</span>
        </div>
        <div class="flow-labels">
          <span>输入方向</span>
          <span>AI 策划</span>
          <span>保存方案</span>
        </div>
      </div>

      <blockquote>
        “好故事不是凭空出现的，它始于一个足够清晰的方向。”
        <small>STORY FORGE · 创作原则 01</small>
      </blockquote>
    </section>

    <section class="auth-panel">
      <div class="auth-card">
        <div class="mobile-logo">
          <BrandMark />
        </div>
        <header>
          <span>{{ isRegister ? 'START YOUR STORY' : 'WELCOME BACK' }}</span>
          <h2>{{ isRegister ? '创建创作者账号' : '继续你的故事' }}</h2>
          <p>
            {{ isRegister ? '只需一个账号，即可保存每次生成的灵感。' : '登录后查看作品，或开始一轮新的策划。' }}
          </p>
        </header>

        <div class="mode-tabs" role="tablist" aria-label="登录或注册">
          <button
            type="button"
            :class="{ active: mode === 'login' }"
            role="tab"
            :aria-selected="mode === 'login'"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            :class="{ active: mode === 'register' }"
            role="tab"
            :aria-selected="mode === 'register'"
            @click="switchMode('register')"
          >
            注册
          </button>
        </div>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              :prefix-icon="User"
              autocomplete="username"
              placeholder="输入你的用户名"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              :prefix-icon="Lock"
              :autocomplete="isRegister ? 'new-password' : 'current-password'"
              placeholder="至少 6 个字符"
              show-password
              type="password"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-form-item v-if="isRegister" label="确认密码" prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              :prefix-icon="Check"
              autocomplete="new-password"
              placeholder="再次输入密码"
              show-password
              type="password"
              @keyup.enter="submit"
            />
          </el-form-item>

          <el-form-item prop="privacyAccepted" class="privacy-consent">
            <el-checkbox v-model="form.privacyAccepted">
              我已阅读并同意
              <router-link to="/privacy" target="_blank" rel="noopener noreferrer">
                《内测隐私说明》
              </router-link>
            </el-checkbox>
          </el-form-item>

          <el-button
            class="submit-button"
            type="primary"
            native-type="submit"
            :loading="authStore.submitting"
          >
            {{ isRegister ? '创建账号并进入' : '登录故事工坊' }}
            <el-icon v-if="!authStore.submitting"><ArrowRight /></el-icon>
          </el-button>
        </el-form>

        <p class="switch-hint">
          {{ isRegister ? '已经有账号？' : '第一次来到故事工坊？' }}
          <button type="button" @click="switchMode(isRegister ? 'login' : 'register')">
            {{ isRegister ? '直接登录' : '免费创建账号' }}
          </button>
        </p>
      </div>

      <footer>© 2026 STORY FORGE AI · 为故事创作者而生</footer>
    </section>
  </main>
</template>

<style scoped>
.auth-page {
  display: grid;
  min-height: 100vh;
  grid-template-columns: minmax(390px, 0.95fr) minmax(520px, 1.05fr);
  background: #f8f7f5;
}

.auth-story {
  position: relative;
  display: flex;
  overflow: hidden;
  flex-direction: column;
  padding: clamp(32px, 5vw, 68px);
  color: #fff;
  background:
    linear-gradient(rgba(24, 20, 54, 0.22), rgba(15, 13, 34, 0.82)),
    radial-gradient(circle at 73% 24%, rgba(113, 91, 240, 0.38), transparent 30%),
    linear-gradient(145deg, #26204e 0%, #191630 56%, #111020 100%);
}

.auth-story::before {
  position: absolute;
  right: -45px;
  bottom: -110px;
  width: 480px;
  height: 480px;
  border: 1px solid rgba(255, 255, 255, 0.045);
  border-radius: 50%;
  box-shadow:
    0 0 0 55px rgba(255, 255, 255, 0.022),
    0 0 0 120px rgba(255, 255, 255, 0.012);
  content: '';
}

.story-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(1px);
  pointer-events: none;
}

.glow-one {
  top: 12%;
  left: -10%;
  width: 260px;
  height: 260px;
  background: radial-gradient(circle, rgba(100, 76, 226, 0.18), transparent 70%);
}

.glow-two {
  right: 12%;
  bottom: 28%;
  width: 180px;
  height: 180px;
  background: radial-gradient(circle, rgba(233, 146, 97, 0.11), transparent 70%);
}

.hero-copy {
  position: relative;
  z-index: 1;
  max-width: 590px;
  margin: auto 0 0;
}

.hero-kicker {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #c4bceb;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 2.5px;
}

.hero-copy h1 {
  margin: 22px 0 19px;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: clamp(36px, 4.2vw, 59px);
  font-weight: 500;
  line-height: 1.28;
  letter-spacing: 1px;
}

.hero-copy h1 em {
  position: relative;
  color: #ffca8c;
  font-style: normal;
}

.hero-copy h1 em::after {
  position: absolute;
  right: 0;
  bottom: -5px;
  left: 0;
  height: 2px;
  background: linear-gradient(90deg, #ffca8c, transparent);
  content: '';
}

.hero-copy > p {
  max-width: 455px;
  margin: 0;
  color: #a9a3c0;
  font-size: 14px;
  line-height: 1.9;
}

.flow-card {
  position: relative;
  z-index: 1;
  width: min(100%, 470px);
  margin-top: 48px;
  padding: 20px 24px 17px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.045);
  backdrop-filter: blur(10px);
}

.flow-line {
  display: flex;
  align-items: center;
}

.flow-step {
  display: grid;
  width: 27px;
  height: 27px;
  flex: 0 0 27px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 50%;
  color: #928ca9;
  font-family: Georgia, serif;
  font-size: 9px;
}

.flow-step.active {
  border-color: #ffcc8e;
  color: #241f43;
  background: #ffcc8e;
  box-shadow: 0 0 0 5px rgba(255, 204, 142, 0.08);
}

.flow-line i {
  height: 1px;
  flex: 1;
  background: linear-gradient(90deg, rgba(255, 204, 142, 0.5), rgba(255, 255, 255, 0.1));
}

.flow-labels {
  display: flex;
  justify-content: space-between;
  margin-top: 9px;
  color: #918ba7;
  font-size: 9px;
  letter-spacing: 0.8px;
}

blockquote {
  position: relative;
  z-index: 1;
  display: grid;
  max-width: 470px;
  gap: 8px;
  margin: 42px 0 0;
  color: #c9c5d5;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 13px;
  line-height: 1.7;
}

blockquote small {
  color: #746f8c;
  font-family: Inter, sans-serif;
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 1.5px;
}

.auth-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding: 42px clamp(30px, 7vw, 110px) 24px;
}

.auth-card {
  width: min(100%, 425px);
  margin: auto 0;
}

.mobile-logo {
  display: none;
  margin-bottom: 38px;
}

.auth-card header > span {
  color: var(--sf-accent);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 2.5px;
}

.auth-card h2 {
  margin: 9px 0;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 32px;
  font-weight: 650;
}

.auth-card header p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 12px;
  line-height: 1.7;
}

.mode-tabs {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  margin: 32px 0 25px;
  padding: 4px;
  border-radius: 12px;
  background: #eeecef;
}

.mode-tabs button {
  height: 40px;
  border: 0;
  border-radius: 9px;
  color: #8a8593;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
}

.mode-tabs button.active {
  color: var(--sf-ink-strong);
  background: #fff;
  box-shadow: 0 5px 15px rgba(39, 31, 72, 0.08);
}

:deep(.el-form-item) {
  margin-bottom: 20px;
}

:deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 7px;
  color: #454052;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.2;
}

:deep(.el-input__wrapper) {
  min-height: 48px;
  padding-inline: 14px;
  border-radius: 11px;
  background: #fff;
}

.submit-button {
  width: 100%;
  height: 50px;
  margin-top: 7px;
  border-radius: 11px;
  box-shadow: 0 12px 25px rgba(82, 62, 194, 0.2);
}

.submit-button .el-icon {
  margin-left: 6px;
}

.switch-hint {
  margin: 24px 0 0;
  color: var(--sf-ink-muted);
  font-size: 11px;
  text-align: center;
}

.switch-hint button {
  padding: 0 3px;
  border: 0;
  color: var(--sf-primary);
  background: transparent;
  cursor: pointer;
  font-weight: 700;
}

.auth-panel footer {
  color: #aaa6b1;
  font-size: 8px;
  letter-spacing: 1.2px;
}

@media (max-width: 900px) {
  .auth-page {
    grid-template-columns: 1fr;
  }

  .auth-story {
    display: none;
  }

  .auth-panel {
    min-height: 100vh;
    padding: 32px 24px 20px;
    background:
      radial-gradient(circle at 90% 4%, rgba(106, 84, 220, 0.09), transparent 28%),
      #f8f7f5;
  }

  .mobile-logo {
    display: block;
  }
}

@media (max-width: 480px) {
  .auth-panel {
    justify-content: flex-start;
  }

  .auth-card {
    margin-top: 14px;
  }

  .auth-card h2 {
    font-size: 28px;
  }
}
</style>
