<script setup lang="ts">
/**
 * 考试管理页：基于已发布试卷创建考试草稿，确认后发布。
 *
 * 「先草稿再发布」这一步不是多余的：考试一旦发布，学生就能在开放时间内看到并作答，
 * 而已有答卷的考试不允许撤回。留一个草稿态是给教师核对试卷和时间的机会。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { api, examStatusLabels, type Exam, type Paper } from '../api'
import { token } from '../session'

const papers = ref<Paper[]>([])
const exams = ref<Exam[]>([])
const error = ref('')
const notice = ref('')
const busy = ref(false)

/**
 * 把 Date 转成 `datetime-local` 输入框需要的 "YYYY-MM-DDTHH:mm" 格式。
 *
 * 不能直接用 `toISOString().slice(0,16)`：那是 UTC 时间，在东八区会比本机时间少 8 小时，
 * 用户看到的默认值就会不对。所以先减掉时区偏移再截取，提交时再转回带时区的 ISO 串。
 */
function localValue(date: Date) {
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

/** 创建表单。时间字段用本地时间字符串，提交时才转成 UTC。 */
const form = reactive({
  name: '',
  paperId: '',
  startAt: localValue(new Date()),
  endAt: localValue(new Date(Date.now() + 2 * 60 * 60 * 1000)),
})

/** 只有已发布的试卷能用于创建考试，草稿试卷不出现在下拉框里。 */
const publishedPapers = computed(() => papers.value.filter((paper) => paper.status === 'PUBLISHED'))
/** 同时刷新试卷和考试列表：创建考试需要试卷下拉框，两者总是一起用。 */
async function load() {
  error.value = ''
  try {
    papers.value = await api.papers(token.value)
    exams.value = await api.exams(token.value)
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

onMounted(load)

/**
 * 创建考试草稿。
 *
 * 三条前端校验与后端一致：必须选已发布试卷、结束时间晚于开始时间、结束时间晚于当前时间。
 * 最后一条容易忽略——把结束时间填成过去，会创建出一场谁都答不了的考试。
 */
async function create() {
  error.value = ''
  notice.value = ''
  if (!form.name.trim()) { error.value = '请填写考试名称'; return }
  if (!form.paperId) { error.value = '请选择一份已发布试卷'; return }
  const start = new Date(form.startAt)
  const end = new Date(form.endAt)
  if (!(end > start)) { error.value = '结束时间必须晚于开始时间'; return }
  if (end <= new Date()) { error.value = '结束时间必须晚于当前时间'; return }
  busy.value = true
  try {
    await api.createExam(token.value, {
      name: form.name.trim(),
      paperId: Number(form.paperId),
      startAt: start.toISOString(),
      endAt: end.toISOString(),
    })
    notice.value = '考试草稿已创建，确认信息后再发布。'
    form.name = ''
    await load()
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}

/** 发布考试。二次确认，因为发布后学生立刻可见且不能撤回。 */
async function publish(exam: Exam) {
  if (!window.confirm(`发布后学生即可在开放时间内答题，确认发布「${exam.name}」吗？`)) return
  error.value = ''
  notice.value = ''
  try {
    await api.publishExam(token.value, exam.id)
    notice.value = `考试「${exam.name}」已发布。`
    await load()
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/** 把后端返回的 UTC 时间转成本地时间展示。固定 24 小时制，避免上午/下午引起误读。 */
const when = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
</script>

<template>
  <section class="content">
    <div class="page-head"><h2>考试管理</h2></div>

    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert ok" role="status">{{ notice }}</p>

    <!-- 创建表单。字段横排成一行，与题库页的筛选卡保持同一种视觉节奏 -->
    <div class="card card-pad filter-bar">
      <label>考试名称 <input v-model="form.name" style="min-width: 200px" placeholder="Java 在线考试" /></label>
      <label>已发布试卷
        <select v-model="form.paperId" style="min-width: 200px">
          <option value="" disabled>请选择</option>
          <option v-for="paper in publishedPapers" :key="paper.id" :value="String(paper.id)">
            {{ paper.name }}（{{ paper.totalScore }} 分 / {{ paper.durationMinutes }} 分钟）
          </option>
        </select>
      </label>
      <label>开放时间 <input v-model="form.startAt" type="datetime-local" style="width: auto" /></label>
      <label>结束时间 <input v-model="form.endAt" type="datetime-local" style="width: auto" /></label>
      <button class="btn btn-primary" type="button" :disabled="busy" @click="create">创建考试草稿</button>
    </div>
    <!-- 没有已发布试卷时给出明确的下一步指引，而不是让下拉框空着 -->
    <p v-if="!publishedPapers.length" class="muted">还没有已发布试卷，请先到「组卷管理」保存并发布一份试卷。</p>

    <!-- 考试列表。开放时间列同时显示起止，便于核对是否与计划一致 -->
    <div class="card">
      <div class="panel-title"><h3>考试列表</h3><span class="spacer">{{ exams.length }} 场</span></div>
      <table class="table">
        <thead>
          <tr>
            <th style="width: 60px">编号</th>
            <th>考试名称</th>
            <th style="width: 170px">试卷</th>
            <th style="width: 70px" class="num">总分</th>
            <th style="width: 320px">开放时间</th>
            <th style="width: 90px">状态</th>
            <th style="width: 80px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="exam in exams" :key="exam.id">
            <td class="muted">{{ exam.id }}</td>
            <td class="cell-strong">{{ exam.name }}</td>
            <td>{{ exam.paperName }}</td>
            <td class="num">{{ exam.totalScore }}</td>
            <!-- 起止时间放同一格，教师核对时不用左右扫两列 -->
            <td class="muted">{{ when(exam.startAt) }} — {{ when(exam.endAt) }}</td>
            <td :class="exam.status === 'PUBLISHED' ? 'state-on' : 'muted'">
              {{ examStatusLabels[exam.status] ?? exam.status }}
            </td>
            <!-- 只有草稿有「发布」操作；已发布的考试不提供撤回，因为可能已经有学生答卷 -->
            <td class="ops">
              <button v-if="exam.status === 'DRAFT'" class="btn-link" type="button" @click="publish(exam)">发布</button>
              <span v-else class="muted">—</span>
            </td>
          </tr>
          <tr v-if="!exams.length"><td colspan="7"><p class="empty">暂无考试</p></td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
