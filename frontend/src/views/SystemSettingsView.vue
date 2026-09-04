<script setup lang="ts">
/**
 * 系统设置页：上半部分是管理员可修改的运行参数，下半部分是只读的运行信息。
 *
 * 这一页原先是纯只读的，理由很实在：配置全部来自环境变量，做成表单只会造成
 * 「界面改完但进程没变」的显示与生效不一致。现在开放编辑，靠的是把这条界限划清楚：
 *
 * - **可编辑的只有「每次用到时都会重新读」的参数**（及格线、令牌有效期、导入行数上限、
 *   自动组卷抽题上限、错题重练每组题数、导入是否跳过重复题干）。读取路径就是生效路径，
 *   因此不存在改完不生效；默认值仍来自环境变量，数据库里只保存被改过的那几项，
 *   界面上如实标出每一项是「默认值」还是「已覆盖」。
 * - **不可编辑的仍然只读**：数据库连接与 JWT 密钥改了会让系统整体不可用；AI 密钥绝不能出现在
 *   浏览器里，AI 地址与协议一旦可编辑，「后端向哪个地址发请求」就变成了一个界面输入框；
 *   自动交卷扫描间隔和 AI 读超时在启动时就固化进了调度触发器与 HTTP 客户端，
 *   界面上改了也不会生效——这恰好是本页原先担心的那种不一致，所以它们不进白名单。
 *
 * 权限：教师能看但改不了（写接口限定管理员），这也是本项目三层权限的一个典型例子。
 *
 * 安全约束：响应里不含任何密钥或连接串，AI 密钥只以「已配置 / 未配置」这一个状态体现。
 */
import { computed, onMounted, ref } from 'vue'
import { api, type SettingItem, type SystemSettings } from '../api'
import { formatWhen } from '../format'
import { isAdmin, token, user } from '../session'

const settings = ref<SystemSettings | null>(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const saving = ref(false)
/**
 * 表单草稿，键是设置项的 key。与后端下发的当前值分开存，才能算出「哪几项被改了」。
 *
 * 值声明成 `string | number`：Vue 的 v-model 在 `type="number"` 的输入框上会自动把值转成数字，
 * 因此这里存的可能是数字。比较和提交都统一走 draftText() 转成字符串，
 * 否则「改成 50 又改回 60」会因为 `60 !== '60'` 一直被算成有改动。
 */
const drafts = ref<Record<string, string | number>>({})

async function load() {
  loading.value = true
  error.value = ''
  try {
    apply(await api.systemSettings(token.value))
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    loading.value = false
  }
}

/** 草稿值的字符串形态。接口只接受字符串，界面比较也用它，避免数字与字符串混着比。 */
function draftText(key: string) {
  const value = drafts.value[key]
  return value === undefined || value === null ? '' : String(value)
}

/** 用后端返回的视图刷新页面，并把草稿重置成当前生效值。 */
function apply(view: SystemSettings) {
  settings.value = view
  drafts.value = Object.fromEntries(view.editable.map((item) => [item.key, item.value]))
}

onMounted(load)

/** 草稿与当前生效值不同的项。为空时保存按钮禁用，避免提交一个什么都没改的请求。 */
const changed = computed(() =>
  (settings.value?.editable ?? []).filter((item) => draftText(item.key) !== item.value))

/**
 * 保存改动。
 *
 * 只提交真正变了的键：后端按「值与当前相同则不算修改」统计条数，
 * 全量提交会让「已更新 N 项」这个数字失去意义。
 */
async function save() {
  error.value = ''
  notice.value = ''
  saving.value = true
  try {
    const values = Object.fromEntries(changed.value.map((item) => [item.key, draftText(item.key)]))
    const outcome = await api.updateSettings(token.value, values)
    apply(outcome.settings)
    notice.value = `已更新 ${outcome.changed} 项设置，立即生效，无需重启。`
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    saving.value = false
  }
}

/**
 * 把一项恢复默认。
 *
 * 传空串而不是传默认值：恢复默认的含义是「删掉数据库里的覆盖行、以后跟着环境变量走」，
 * 写回一个当时的默认值会让这一项永远停在旧默认值上。
 */
async function restore(item: SettingItem) {
  error.value = ''
  notice.value = ''
  saving.value = true
  try {
    const outcome = await api.updateSettings(token.value, { [item.key]: '' })
    apply(outcome.settings)
    notice.value = `「${item.label}」已恢复默认值 ${item.defaultValue}${item.unit}。`
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    saving.value = false
  }
}

/** 放弃未保存的改动。 */
function discard() {
  if (settings.value) apply(settings.value)
  notice.value = ''
}

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
    <p v-if="notice" class="alert ok" role="status">{{ notice }}</p>
    <p class="muted">
      上半部分的参数可由<b>管理员</b>直接修改，保存后<b>立即生效、无需重启</b>：它们在每次用到时都会重新读取。
      下半部分只读——数据库连接、JWT 与 AI 密钥不能出现在浏览器里，而自动交卷间隔与 AI 超时在启动时
      就固化进了调度器和 HTTP 客户端，界面上改了也不会生效，因此不开放编辑。
    </p>

    <template v-if="settings">
      <!-- 可编辑参数。默认值来自环境变量，数据库里只保存被改过的项，因此每一行都标出来源 -->
      <div class="card">
        <div class="panel-title">
          <h3>可修改的运行参数</h3>
          <span class="spacer">
            {{ isAdmin ? '改完保存即生效；恢复默认会删掉覆盖值，之后跟随环境变量' : '只有管理员可以修改，教师为只读' }}
          </span>
        </div>
        <table class="table">
          <thead>
            <tr>
              <th style="width: 150px">参数</th>
              <th style="width: 160px">当前值</th>
              <th style="width: 108px">来源</th>
              <th>说明</th>
              <th v-if="isAdmin" style="width: 92px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in settings.editable" :key="item.key">
              <td>
                <p class="cell-strong">{{ item.label }}</p>
                <p class="muted">{{ item.group }}</p>
              </td>
              <td>
                <!-- 布尔项用下拉而不是复选框：下拉里「开启 / 关闭」是明确的两个词，
                     复选框在只读状态下很难表达「当前是关」 -->
                <select v-if="item.type === 'BOOLEAN'" v-model="drafts[item.key]" :disabled="!isAdmin"
                        :aria-label="item.label">
                  <option value="true">开启</option>
                  <option value="false">关闭</option>
                </select>
                <span v-else-if="!isAdmin">{{ item.value }}{{ item.unit }}</span>
                <span v-else style="display: flex; align-items: center; gap: 6px">
                  <input v-model="drafts[item.key]" class="control" type="number"
                         :min="item.min ?? undefined" :max="item.max ?? undefined"
                         :step="item.type === 'DECIMAL' ? 0.1 : 1"
                         :aria-label="item.label" style="width: 96px" />
                  <span class="muted">{{ item.unit }}</span>
                </span>
              </td>
              <td>
                <span :class="item.overridden ? 'state-on' : 'muted'">
                  {{ item.overridden ? '已覆盖' : '默认值' }}
                </span>
                <p class="muted">默认 {{ item.defaultValue }}{{ item.unit }}</p>
              </td>
              <td class="muted">
                {{ item.description }}
                <p v-if="item.overridden && item.updatedBy" class="muted">
                  {{ item.updatedBy }} 于 {{ formatWhen(item.updatedAt) }} 修改
                </p>
              </td>
              <td v-if="isAdmin" class="ops">
                <button class="btn-link" type="button" :disabled="!item.overridden || saving"
                        @click="restore(item)">恢复默认</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="isAdmin" class="filter-bar card-pad" style="border-top: 1px solid var(--line)">
          <button class="btn btn-primary" type="button" :disabled="saving || !changed.length" @click="save">
            {{ saving ? '保存中…' : `保存改动${changed.length ? ` ${changed.length} 项` : ''}` }}
          </button>
          <button class="btn" type="button" :disabled="saving || !changed.length" @click="discard">放弃改动</button>
          <span class="muted">超出范围的值会被后端拒绝；一次提交里有一项不合法则整批都不生效。</span>
        </div>
      </div>

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
