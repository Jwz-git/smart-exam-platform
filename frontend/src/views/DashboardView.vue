<script setup lang="ts">
/**
 * 教师首页工作台：题库与考试的整体数量、快捷入口、最近考试。
 *
 * 原型侧栏里有「首页」这一项但没画内容，这里按最小可用做：只展示已有接口能提供的数据，
 * 不引入新的统计接口，避免为一个非 MVP 页面增加后端工作量。
 */
import { onMounted, ref } from 'vue'
import { api, type Exam, type Paper } from '../api'
import { token, user } from '../session'

/** 点击快捷入口时通知外层切换视图。 */
const emit = defineEmits<{ go: [key: string] }>()

/** 题目总数，取分页响应里的 total。 */
const questionTotal = ref(0)
/** 知识点数量。 */
const pointTotal = ref(0)
const papers = ref<Paper[]>([])
const exams = ref<Exam[]>([])
const error = ref('')
const loading = ref(true)

/**
 * 并发拉取四类数据。
 *
 * 题目只取 `size: 1`：这里只需要 total 字段，没必要把整页题目传下来。
 * 四个请求互不依赖，用 Promise.all 并发，首屏等待时间取决于最慢的一个而不是四者之和。
 */
onMounted(async () => {
  try {
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
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    loading.value = false
  }
})

/** 快捷入口，指向三个已实现的主要功能。 */
const shortcuts = [
  { key: 'questions', label: '新增题目', hint: '维护单选、多选、判断、简答和编程题' },
  { key: 'papers', label: '手动组卷', hint: '从题库选题并设置每题分值' },
  { key: 'exams', label: '发布考试', hint: '基于已发布试卷设置开放时间' },
]
</script>

<template>
  <section class="content">
    <div class="page-head"><h2>首页</h2></div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p class="muted">{{ user?.displayName }}，欢迎回来。当前工作台展示题库与考试的整体情况。</p>

    <!-- 数量统计。加载完成前显示占位符「—」而不是 0，避免让人误以为题库是空的 -->
    <div class="stat-row">
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
      <div class="card card-pad stat">
        <span class="muted">考试（已发布）</span>
        <b>{{ loading ? '—' : exams.length }}<small class="muted"> / {{ exams.filter(e => e.status === 'PUBLISHED').length }}</small></b>
      </div>
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

    <!-- 最近考试只取前 5 条，完整列表在「考试管理」页 -->
    <div class="card">
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
            <td class="muted">{{ new Date(exam.startAt).toLocaleString('zh-CN', { hour12: false }) }}</td>
            <td :class="exam.status === 'PUBLISHED' ? 'state-on' : 'muted'">
              {{ exam.status === 'PUBLISHED' ? '已发布' : '草稿' }}
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
