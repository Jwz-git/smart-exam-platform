<script setup lang="ts">
/**
 * 首页工作台。按角色分成两套内容：
 *
 * 教师看题库与考试的整体数量、快捷入口和最近考试；管理员看账号构成和最近创建的账号。
 *
 * 分角色不只是排版差异。早期版本对两种角色都请求题库、知识点、试卷、考试四个教师接口，
 * 管理员一登录就会收到四个 403，并在首页顶部看到一条对他毫无意义的错误提示——
 * 「界面只请求当前角色有权访问的接口」本身就是权限设计的一部分。
 */
import { computed, onMounted, ref } from 'vue'
import { api, examStatusLabels, roleLabels, type AdminUser, type Exam, type Paper } from '../api'
import { isAdmin, token, user } from '../session'

/** 点击快捷入口时通知外层切换视图。 */
const emit = defineEmits<{ go: [key: string] }>()

/** 题目总数，取分页响应里的 total。 */
const questionTotal = ref(0)
/** 知识点数量。 */
const pointTotal = ref(0)
const papers = ref<Paper[]>([])
const exams = ref<Exam[]>([])
/** 管理员视图的数据：账号总数与用于分类计数的一页账号。 */
const userTotal = ref(0)
const users = ref<AdminUser[]>([])
const error = ref('')
const loading = ref(true)

/**
 * 按角色拉取数据。
 *
 * 教师路径并发拉取四类数据：题目只取 `size: 1`，因为这里只需要 total 字段，
 * 没必要把整页题目传下来；四个请求互不依赖，用 Promise.all 并发，
 * 首屏等待时间取决于最慢的一个而不是四者之和。
 *
 * 管理员路径只请求 `/v1/users`，这是管理员唯一有权访问的业务列表接口。
 * 取 `size: 100` 是为了在前端按角色和状态分类计数：演示环境账号数远小于 100，
 * 真要支撑大规模用户应由后端提供聚合接口，而不是把整表拉到浏览器里数。
 */
onMounted(async () => {
  try {
    if (isAdmin.value) {
      const page = await api.users(token.value, { page: 1, size: 100 })
      userTotal.value = page.total
      users.value = page.items
    } else {
      const [questions, points, paperList, examList] = await Promise.all([
        api.questions(token.value, { page: 1, size: 1 }),
        api.points(token.value),
        api.papers(token.value),
        api.exams(token.value),
      ])
      questionTotal.value = questions.total
      pointTotal.value = points.length
      papers.value = paperList
      exams.value = examList
    }
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    loading.value = false
  }
})

/** 管理员视图的四张统计卡：总数、教师数、学生数、已停用数。 */
const userStats = computed(() => [
  { label: '账号总数', value: userTotal.value },
  { label: '教师', value: users.value.filter(u => u.role === 'TEACHER').length },
  { label: '学生', value: users.value.filter(u => u.role === 'STUDENT').length },
  { label: '已停用', value: users.value.filter(u => u.status === 'DISABLED').length },
])

/** 快捷入口按角色给出，指向的都是当前角色真正能打开的页面。 */
const shortcuts = computed(() => (isAdmin.value
  ? [
      { key: 'users', label: '用户管理', hint: '查询、新增、启用或停用账号' },
      { key: 'settings', label: '系统设置', hint: '核对当前生效的运行参数' },
    ]
  : [
      { key: 'questions', label: '新增题目', hint: '维护单选、多选、判断、简答和编程题' },
      { key: 'papers', label: '手动组卷', hint: '从题库选题并设置每题分值' },
      { key: 'exams', label: '发布考试', hint: '基于已发布试卷设置开放时间' },
    ]))

/** 时间列统一格式，`null` 显示破折号而不是 `Invalid Date`。 */
const formatTime = (value: string | null) =>
  (value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—')
</script>

<template>
  <section class="content">
    <div class="page-head"><h2>首页</h2></div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p class="muted">
      {{ user?.displayName }}，欢迎回来。当前工作台展示{{ isAdmin ? '账号构成与最近创建的账号' : '题库与考试的整体情况' }}。
    </p>

    <!-- 数量统计。加载完成前显示占位符「—」而不是 0，避免让人误以为数据是空的 -->
    <div class="stat-row">
      <template v-if="isAdmin">
        <div v-for="item in userStats" :key="item.label" class="card card-pad stat">
          <span class="muted">{{ item.label }}</span><b>{{ loading ? '—' : item.value }}</b>
        </div>
      </template>
      <template v-else>
        <div class="card card-pad stat">
          <span class="muted">题目总数</span><b>{{ loading ? '—' : questionTotal }}</b>
        </div>
        <div class="card card-pad stat">
          <span class="muted">知识点</span><b>{{ loading ? '—' : pointTotal }}</b>
        </div>
        <!-- 试卷和考试都显示「总数 / 已发布数」：草稿数量同样是教师关心的信息 -->
        <div class="card card-pad stat">
          <span class="muted">试卷（已发布）</span>
          <b>{{ loading ? '—' : papers.length }}<small class="muted"> / {{ papers.filter(p => p.status === 'PUBLISHED').length }}</small></b>
        </div>
        <!-- 考试统计的分母是「已发布及以后」而不是「状态恰好等于 PUBLISHED」：
             成绩公布后考试会流转到 RESULTS_PUBLISHED，用等号判断会把它算成未发布 -->
        <div class="card card-pad stat">
          <span class="muted">考试（已发布）</span>
          <b>{{ loading ? '—' : exams.length }}<small class="muted"> / {{ exams.filter(e => e.status !== 'DRAFT').length }}</small></b>
        </div>
      </template>
    </div>

    <!-- 快捷入口，点击后由 App.vue 切换视图 -->
    <div class="card">
      <div class="panel-title"><h3>快捷入口</h3></div>
      <div class="shortcut-row">
        <button v-for="item in shortcuts" :key="item.key" class="shortcut" type="button" @click="emit('go', item.key)">
          <b>{{ item.label }}</b>
          <span class="muted">{{ item.hint }}</span>
        </button>
      </div>
    </div>

    <!-- 管理员看最近创建的账号，教师看最近考试；都只取前 5 条，完整列表在各自的管理页 -->
    <div v-if="isAdmin" class="card">
      <div class="panel-title"><h3>最近创建的账号</h3></div>
      <table class="table">
        <thead>
          <tr>
            <th>账号</th>
            <th>姓名</th>
            <th style="width: 90px">角色</th>
            <th style="width: 200px">创建时间</th>
            <th style="width: 90px">状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in users.slice(0, 5)" :key="item.id">
            <td class="cell-strong">{{ item.username }}</td>
            <td>{{ item.displayName }}</td>
            <td>{{ roleLabels[item.role] }}</td>
            <td class="muted">{{ formatTime(item.createdAt) }}</td>
            <td :class="item.status === 'ACTIVE' ? 'state-on' : 'muted'">
              {{ item.status === 'ACTIVE' ? '启用' : '停用' }}
            </td>
          </tr>
          <tr v-if="!users.length"><td colspan="5"><p class="empty">还没有账号</p></td></tr>
        </tbody>
      </table>
    </div>
    <div v-else class="card">
      <div class="panel-title"><h3>最近考试</h3></div>
      <table class="table">
        <thead>
          <tr>
            <th>考试名称</th>
            <th style="width: 170px">试卷</th>
            <th style="width: 200px">开始时间</th>
            <th style="width: 90px">状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="exam in exams.slice(0, 5)" :key="exam.id">
            <td class="cell-strong">{{ exam.name }}</td>
            <td>{{ exam.paperName }}</td>
            <td class="muted">{{ formatTime(exam.startAt) }}</td>
            <!-- 状态走统一的中文映射表，不用「等于 PUBLISHED 否则草稿」的两分法：
                 考试还有 RESULTS_PUBLISHED 等状态，两分法会把它们全显示成「草稿」 -->
            <td :class="exam.status === 'DRAFT' ? 'muted' : 'state-on'">
              {{ examStatusLabels[exam.status] ?? exam.status }}
            </td>
          </tr>
          <tr v-if="!exams.length"><td colspan="4"><p class="empty">还没有考试</p></td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
/* 首页专属样式，只在本组件内生效，不污染全局主题 */
.stat-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; }
.stat { display: grid; gap: 6px; }
.stat b { font-size: 26px; font-weight: 600; }
.stat small { font-size: 14px; font-weight: 400; }
.shortcut-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 14px; padding: 16px; }
.shortcut {
  display: grid;
  gap: 4px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  text-align: left;
  cursor: pointer;
}
.shortcut:hover { border-color: var(--brand); background: var(--brand-soft); }
.shortcut b { color: var(--brand); }
</style>
