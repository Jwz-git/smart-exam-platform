<script setup lang="ts">
/**
 * 系统设置页（教师与管理员）：展示当前进程真正生效的运行参数。
 *
 * 这一页刻意是**只读**的。系统的可配置项（数据库、JWT、AI 服务商、自动交卷间隔）全部来自后端
 * 环境变量，做成可编辑表单只会造成两种坏结果：界面上改完但进程没重启，显示值与生效值不一致；
 * 或者把「改数据库连接」这种能让系统整体不可用的操作放进了浏览器。
 *
 * 反过来，把真实生效的值显示出来是有价值的——演示现场最常被问的几个问题
 *（时区是不是 UTC、令牌多久过期、及格线怎么定的、AI 到底配没配）都能在这一页直接指给对方看。
 *
 * 安全约束：响应里不含任何密钥或连接串，AI 密钥只以「已配置 / 未配置」这一个状态体现。
 */
import { onMounted, ref } from 'vue'
import { api, type SystemSettings } from '../api'
import { formatWhen } from '../format'
import { token, user } from '../session'

const settings = ref<SystemSettings | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    settings.value = await api.systemSettings(token.value)
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 自动交卷间隔以毫秒下发，展示成秒更好读。 */
function seconds(ms: number) {
  return `${Math.round(ms / 100) / 10} 秒`
}
</script>

<template>
  <section class="content">
    <div class="page-head">
      <h2>系统设置</h2>
      <span class="spacer" />
      <button class="btn" type="button" :disabled="loading" @click="load">刷新</button>
    </div>

    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p class="muted">
      本页展示后端进程当前生效的运行参数，只读。所有可配置项都来自后端环境变量（见仓库 <code>.env.example</code>），
      修改后需要重启后端才会生效——因此这里不提供编辑入口，避免显示值与实际生效值不一致。
    </p>

    <template v-if="settings">
      <!-- 运行环境 -->
      <div class="card">
        <div class="panel-title"><h3>运行环境</h3></div>
        <dl class="info-list">
          <div><dt>服务名</dt><dd>{{ settings.runtime.service }}</dd></div>
          <div><dt>Spring Boot</dt><dd>{{ settings.runtime.springBootVersion }}</dd></div>
          <div><dt>Java 运行时</dt><dd>{{ settings.runtime.javaVersion }}</dd></div>
          <div><dt>服务端时区</dt><dd>{{ settings.runtime.serverTimeZone }}</dd></div>
          <div><dt>服务端时间</dt><dd>{{ formatWhen(settings.runtime.serverTime) }}</dd></div>
          <div><dt>当前登录</dt><dd>{{ user?.displayName }}（{{ user?.username }}）</dd></div>
        </dl>
      </div>

      <!-- 数据库 -->
      <div class="card">
        <div class="panel-title"><h3>数据库</h3><span class="spacer">不展示连接地址与账号密码</span></div>
        <dl class="info-list">
          <div><dt>产品</dt><dd>{{ settings.database.product }}</dd></div>
          <div><dt>版本</dt><dd>{{ settings.database.version }}</dd></div>
          <div>
            <dt>迁移版本</dt>
            <dd>{{ settings.database.schemaVersion ? `Flyway v${settings.database.schemaVersion}` : '未使用 Flyway（手写 schema）' }}</dd>
          </div>
          <div><dt>会话时区</dt><dd>{{ settings.database.sessionTimeZone }}</dd></div>
        </dl>
        <p class="muted card-pad" style="padding-top: 0">
          连接串强制会话时区为 UTC，答卷开始时间由后端显式写入而不用数据库默认值——否则时间会按本地时区写入、
          按 UTC 读出，产生固定 8 小时偏移。
        </p>
      </div>

      <!-- 安全 -->
      <div class="card">
        <div class="panel-title"><h3>认证与安全</h3></div>
        <dl class="info-list">
          <div><dt>令牌类型</dt><dd>{{ settings.security.tokenType }}</dd></div>
          <div><dt>有效期</dt><dd>{{ settings.security.accessTokenMinutes }} 分钟</dd></div>
          <div><dt>密码存储</dt><dd>{{ settings.security.passwordAlgorithm }}（自带随机盐）</dd></div>
          <div>
            <dt>令牌吊销</dt>
            <dd :class="settings.security.tokenRevocable ? 'state-on' : 'muted'">
              {{ settings.security.tokenRevocable ? '支持' : '不维护吊销名单' }}
            </dd>
          </div>
        </dl>
        <p class="muted card-pad" style="padding-top: 0">
          无状态 JWT 无法吊销，因此每次鉴权都回查一次用户表：账号被停用后，此前签发的令牌在下一次请求就会被拒绝。
        </p>
      </div>

      <!-- 考试与评分规则 -->
      <div class="card">
        <div class="panel-title"><h3>考试与评分规则</h3></div>
        <dl class="info-list">
          <div><dt>自动交卷扫描</dt><dd>每 {{ seconds(settings.exam.autoSubmitIntervalMs) }}（服务端，不依赖浏览器）</dd></div>
          <div><dt>及格线</dt><dd>试卷总分的 {{ settings.exam.passRatioPercent }}%</dd></div>
          <div><dt>排名规则</dt><dd>{{ settings.exam.rankingRule }}</dd></div>
          <div><dt>部分分</dt><dd>{{ settings.exam.partialCreditRule }}</dd></div>
        </dl>
      </div>

      <!-- AI 配置状态 -->
      <div class="card">
        <div class="panel-title">
          <h3>AI 辅助出题</h3>
          <span class="spacer" :class="settings.ai.configured ? 'state-on' : 'state-off'">
            {{ settings.ai.configured ? '已配置' : '未配置' }}
          </span>
        </div>
        <dl class="info-list">
          <div><dt>协议</dt><dd>{{ settings.ai.protocol }}</dd></div>
          <div><dt>服务地址</dt><dd>{{ settings.ai.baseUrl || '—' }}</dd></div>
          <div><dt>模型</dt><dd>{{ settings.ai.model || '—' }}</dd></div>
          <div><dt>超时</dt><dd>{{ settings.ai.timeoutSeconds }} 秒</dd></div>
          <div><dt>最大 token</dt><dd>{{ settings.ai.maxTokens }}</dd></div>
          <div><dt>密钥</dt><dd class="muted">只存于后端环境变量，任何接口都不下发</dd></div>
        </dl>
        <p class="muted card-pad" style="padding-top: 0">
          {{ settings.ai.configured
            ? '换服务商（DeepSeek、通义、Kimi、本地 Ollama、Anthropic）只改四个环境变量，不改代码。'
            : '未配置密钥时 AI 出题会返回可读错误，手工出题与考试主流程不受影响。' }}
        </p>
      </div>
    </template>
    <p v-else-if="!loading && !error" class="empty">读取系统信息失败</p>
  </section>
</template>

<style scoped>
/* 两列定义列表：标签固定宽度、值自适应，比表格更适合「名称—值」这种成对信息 */
.info-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 10px 24px; padding: 16px; margin: 0; }
.info-list > div { display: grid; grid-template-columns: 110px 1fr; align-items: baseline; gap: 12px; }
.info-list dt { color: var(--muted); font-size: 13px; }
.info-list dd { margin: 0; word-break: break-all; }
</style>
