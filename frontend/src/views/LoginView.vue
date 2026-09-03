<script setup lang="ts">
/**
 * 登录页，对应原型第 1 张图：左侧蓝色插画区 + 右侧表单卡片。
 *
 * 两点安全考虑：
 * 1. 账号和密码都不预填。演示密码写在提示文案里会被截图带走，这里只提示三个演示账号名。
 * 2. 密码框默认 type=password，由用户主动点眼睛图标切换，不做「记住密码」。
 */
import { ref } from 'vue'
import { login, expiredNotice } from '../session'

const username = ref('')
const password = ref('')
/** 密码是否明文显示，对应输入框右侧的眼睛按钮。 */
const showPassword = ref(false)
const error = ref('')
/** 提交中标记，防止连续点击发出多次登录请求。 */
const busy = ref(false)

/** 提交登录。失败时把后端返回的可读信息显示在按钮上方，输入内容保留不清空。 */
async function submit() {
  error.value = ''
  busy.value = true
  try {
    await login(username.value.trim(), password.value)
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-frame">
      <!-- 左侧插画区。原创 SVG 示意图，不引用外部素材，避免课程作业出现版权问题 -->
      <div class="login-art">
        <!-- 原创示意插画：显示器上的题库看板、书本与植物，呼应原型左侧配图。 -->
        <svg width="300" height="240" viewBox="0 0 300 240" fill="none" aria-hidden="true">
          <circle cx="52" cy="42" r="17" stroke="#9dc2ff" stroke-width="2" opacity=".7" />
          <path d="M36 96h20M46 86v20" stroke="#9dc2ff" stroke-width="2" opacity=".7" stroke-linecap="round" />
          <path d="M250 54h22M261 43v22" stroke="#9dc2ff" stroke-width="2" opacity=".7" stroke-linecap="round" />
          <circle cx="258" cy="128" r="12" stroke="#9dc2ff" stroke-width="2" opacity=".6" />
          <rect x="82" y="62" width="140" height="94" rx="7" fill="#f6f9ff" />
          <rect x="94" y="76" width="52" height="8" rx="4" fill="#c7dcff" />
          <rect x="94" y="92" width="116" height="7" rx="3.5" fill="#e2ecff" />
          <rect x="94" y="105" width="96" height="7" rx="3.5" fill="#e2ecff" />
          <rect x="94" y="124" width="26" height="20" rx="3" fill="#7aa9f7" />
          <rect x="128" y="116" width="26" height="28" rx="3" fill="#4f8cf0" />
          <rect x="162" y="122" width="26" height="22" rx="3" fill="#a8c9fb" />
          <path d="M138 156v14M116 176h44" stroke="#dbe8ff" stroke-width="5" stroke-linecap="round" />
          <rect x="196" y="150" width="62" height="9" rx="3" fill="#ffd48a" />
          <rect x="200" y="140" width="54" height="9" rx="3" fill="#ffe6bb" />
          <rect x="204" y="130" width="46" height="9" rx="3" fill="#ffd48a" />
          <circle cx="222" cy="118" r="15" fill="#5fd08a" />
          <path d="m215 118 5 5 9-10" stroke="#fff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M56 176c0-12 8-20 18-20-2 12-8 19-18 20M56 176c0-10-6-17-14-17 1 10 6 16 14 17" fill="#7ddba6" />
          <path d="M56 176v-14" stroke="#4bb37c" stroke-width="2.4" stroke-linecap="round" />
          <path d="M44 176h26l-3 18H47z" fill="#ffb877" />
        </svg>
      </div>

      <!-- 右侧表单。用 form + submit 而不是给按钮绑 click，这样回车键也能提交 -->
      <form class="login-form" @submit.prevent="submit">
        <h1>智能在线题库与组卷系统</h1>

        <!-- 令牌过期被动退出时的解释文案，与主动登录失败区分开，用 role=status 而非 alert -->
        <p v-if="expiredNotice" class="alert error" role="status">{{ expiredNotice }}</p>

        <!-- 账号输入框。图标绝对定位在框内左侧，输入框用 padding-left 让出位置 -->
        <label class="field">
          <span>账号</span>
          <span class="input-icon">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M19 20v-1a5 5 0 0 0-5-5h-4a5 5 0 0 0-5 5v1" />
              <circle cx="12" cy="7" r="4" />
            </svg>
            <input v-model="username" placeholder="请输入账号" autocomplete="username" required />
          </span>
        </label>

        <label class="field">
          <span>密码</span>
          <span class="input-icon">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                 stroke-linecap="round" stroke-linejoin="round">
              <rect x="4" y="10" width="16" height="11" rx="2" />
              <path d="M8 10V7a4 4 0 0 1 8 0v3" />
            </svg>
            <input
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="请输入密码"
              autocomplete="current-password"
              required
            />
            <!-- 明确写 type=button：表单内的 button 默认是 submit，不写会导致点眼睛就提交登录 -->
            <button
              class="suffix"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :aria-pressed="showPassword"
              @click="showPassword = !showPassword"
            >
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
                   stroke-linecap="round" stroke-linejoin="round">
                <path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12" />
                <circle cx="12" cy="12" r="2.8" />
                <path v-if="!showPassword" d="m4 20 16-16" />
              </svg>
            </button>
          </span>
        </label>

        <!-- 登录失败提示。role=alert 让读屏器立即播报 -->
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>

        <button class="btn btn-primary" type="submit" :disabled="busy">{{ busy ? '登录中…' : '登 录' }}</button>
        <p class="login-hint">课程演示账号：admin / teacher / student</p>
      </form>
    </div>
  </main>
</template>
