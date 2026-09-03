<script setup lang="ts">
/**
 * 统计分析页（教师）：上半部分是题库与教学活动的整体概况，下半部分是单场考试的成绩分布与逐题正确率。
 *
 * 两部分放同一页是因为教师看统计时的问题是连着的：「我的题库覆盖够不够」和「这场考得怎么样、
 * 哪道题最难」经常一起看，而且后者的结论往往直接回到前者——某道题正确率过低就该回题库改题。
 *
 * 页面里所有数字都由后端算好后下发，前端只做展示和条形长度换算。整体统计（平均分、最高分、
 * 及格率）与成绩管理页同源，都来自 `GradingService#results`，不会出现两页数字不一致。
 */
import { computed, onMounted, ref, watch } from 'vue'
import BarChart from '../components/BarChart.vue'
import {
  api, examStatusLabels, typeLabels,
  type Exam, type ExamAnalysis, type GroupCount, type StatsOverview,
} from '../api'
import { formatScore } from '../format'
import { token } from '../session'

const overview = ref<StatsOverview | null>(null)
const exams = ref<Exam[]>([])
/** 当前选中的考试 ID，用字符串以便直接绑定 select。 */
const examId = ref('')
const analysis = ref<ExamAnalysis | null>(null)
const loading = ref(false)
const error = ref('')

/** 草稿考试不可能有答卷，不进下拉框。 */
const analysableExams = computed(() => exams.value.filter((exam) => exam.status !== 'DRAFT'))

/** 把后端的分组计数转成条形图需要的形状，并顺手带上占总数的百分比。 */
function toBars(groups: GroupCount[], total: number) {
  return groups.map((group) => ({
    label: group.label,
    value: group.count,
    note: total > 0 ? `${Math.round((group.count / total) * 1000) / 10}%` : undefined,
  }))
}

/** 题型分布。 */
const typeBars = computed(() => toBars(overview.value?.bank.byType ?? [], overview.value?.bank.total ?? 0))
/** 难度分布。 */
const difficultyBars = computed(() =>
  toBars(overview.value?.bank.byDifficulty ?? [], overview.value?.bank.total ?? 0))
/** 知识点分布。只显示前 10 个，再多的话图会长得看不完，完整列表在题库页按知识点筛选即可。 */
const pointBars = computed(() =>
  toBars((overview.value?.bank.byKnowledgePoint ?? []).slice(0, 10), overview.value?.bank.total ?? 0))

/** 成绩分布。条形右侧显示人数与占比，占比的分母是已评完的答卷数。 */
const scoreBars = computed(() =>
  (analysis.value?.distribution ?? []).map((bucket) => ({
    label: bucket.label,
    value: bucket.count,
    note: `${bucket.ratio}%`,
  })))

/** 逐题统计按题号升序，与试卷题序一致。 */
const questions = computed(() => analysis.value?.questions ?? [])

async function loadOverview() {
  loading.value = true
  error.value = ''
  try {
    const [data, examList] = await Promise.all([api.statsOverview(token.value), api.exams(token.value)])
    overview.value = data
    exams.value = examList
    const first = analysableExams.value[0]
    if (!examId.value && first) examId.value = String(first.id)
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    loading.value = false
  }
}

/** 拉取单场考试的分析。没选考试时清空，避免残留上一场的数字。 */
async function loadAnalysis() {
  if (!examId.value) { analysis.value = null; return }
  error.value = ''
  try {
    analysis.value = await api.examAnalysis(token.value, Number(examId.value))
  } catch (reason) {
    error.value = (reason as Error).message
    analysis.value = null
  }
}

onMounted(async () => {
  await loadOverview()
  await loadAnalysis()
})
watch(examId, () => void loadAnalysis())

/** 刷新两部分数据。统计是只读的，重复点击没有副作用。 */
async function refresh() {
  await loadOverview()
  await loadAnalysis()
}

/** 正确率、得分率这类百分比字段：null 表示不适用（主观题没有正确率），显示破折号而不是 0%。 */
function formatPercent(value: number | null) {
  return value === null || value === undefined ? '—' : `${value}%`
}
</script>

<template>
  <section class="content">
    <div class="page-head">
      <h2>统计分析</h2>
      <span class="spacer" />
      <button class="btn" type="button" :disabled="loading" @click="refresh">刷新</button>
    </div>

    <p v-if="error" class="alert error" role="alert">{{ error }}</p>

    <!-- 题库概况。停用题目仍计入总数，但不能加入新试卷 -->
    <div class="card card-pad">
      <div class="stat-row">
        <div class="stat"><span class="muted">题目总数</span><b>{{ overview?.bank.total ?? '—' }}</b></div>
        <div class="stat"><span class="muted">启用中</span><b class="state-on">{{ overview?.bank.active ?? '—' }}</b></div>
        <div class="stat"><span class="muted">已停用</span><b class="muted">{{ overview?.bank.disabled ?? '—' }}</b></div>
        <div class="stat"><span class="muted">知识点</span><b>{{ overview?.bank.knowledgePointCount ?? '—' }}</b></div>
        <div class="stat"><span class="muted">试卷（已发布）</span>
          <b>{{ overview?.activity.paperCount ?? '—' }}<small class="muted"> / {{ overview?.activity.publishedPaperCount ?? 0 }}</small></b>
        </div>
        <div class="stat"><span class="muted">考试（已发布）</span>
          <b>{{ overview?.activity.examCount ?? '—' }}<small class="muted"> / {{ overview?.activity.publishedExamCount ?? 0 }}</small></b>
        </div>
        <div class="stat"><span class="muted">答卷（作答中）</span>
          <b>{{ overview?.activity.submissionCount ?? '—' }}<small class="muted"> / {{ overview?.activity.inProgressCount ?? 0 }}</small></b>
        </div>
        <div class="stat"><span class="muted">待批主观题</span>
          <b :class="overview?.activity.pendingSubjectiveCount ? 'state-off' : 'state-on'">
            {{ overview?.activity.pendingSubjectiveCount ?? '—' }}
          </b>
        </div>
      </div>
      <p class="muted" style="margin-top: 10px">
        统计范围为当前教师自己创建的题目、试卷与考试；已公布成绩的考试 {{ overview?.activity.resultsPublishedExamCount ?? 0 }} 场。
      </p>
    </div>

    <!-- 三张分布图。计数为 0 的分组也会显示，用于看出题型或知识点的覆盖缺口 -->
    <div class="grid-2">
      <div class="card">
        <div class="panel-title"><h3>题型分布</h3><span class="spacer">含计数为 0 的题型</span></div>
        <BarChart :items="typeBars" empty-text="题库还没有题目" />
      </div>
      <div class="card">
        <div class="panel-title"><h3>难度分布</h3></div>
        <BarChart :items="difficultyBars" empty-text="题库还没有题目" />
      </div>
    </div>
    <div class="card">
      <div class="panel-title"><h3>知识点分布</h3><span class="spacer">最多显示 10 个</span></div>
      <BarChart :items="pointBars" empty-text="还没有知识点" />
    </div>

    <!-- 单场考试分析 -->
    <div class="card card-pad filter-bar">
      <label>考试
        <select v-model="examId" style="min-width: 260px">
          <option value="" disabled>请选择考试</option>
          <option v-for="exam in analysableExams" :key="exam.id" :value="String(exam.id)">
            {{ exam.name }}（{{ examStatusLabels[exam.status] ?? exam.status }} · {{ exam.totalScore }} 分）
          </option>
        </select>
      </label>
      <span v-if="analysis" class="muted">
        及格线 {{ formatScore(analysis.passScore) }} 分（试卷满分 {{ analysis.paperTotalScore }} × 60%）
      </span>
    </div>
    <p v-if="!analysableExams.length" class="muted">还没有已发布的考试，发布一场考试并有学生交卷后这里才有数据。</p>

    <template v-if="analysis">
      <div class="card card-pad">
        <div class="stat-row">
          <div class="stat"><span class="muted">答卷数</span><b>{{ analysis.submissionCount }}</b></div>
          <div class="stat"><span class="muted">已评完</span><b>{{ analysis.gradedCount }}</b></div>
          <div class="stat"><span class="muted">平均分</span><b class="cell-strong">{{ formatScore(analysis.averageScore) }}</b></div>
          <div class="stat"><span class="muted">最高分</span><b>{{ formatScore(analysis.highestScore) }}</b></div>
          <div class="stat"><span class="muted">最低分</span><b>{{ formatScore(analysis.lowestScore) }}</b></div>
          <div class="stat"><span class="muted">及格率</span><b>{{ formatPercent(analysis.passRate) }}</b></div>
        </div>
        <p class="muted" style="margin-top: 10px">
          只有「已交卷且主观题全部评完」的答卷才进统计，否则未批完的卷子会把平均分拉低成一个随阅卷进度变化的数字。
        </p>
      </div>

      <div class="card">
        <div class="panel-title">
          <h3>成绩分布</h3>
          <span class="spacer">按占试卷满分的百分比分段，共 {{ analysis.gradedCount }} 份已评完答卷</span>
        </div>
        <BarChart :items="scoreBars" empty-text="还没有已评完的答卷" />
      </div>

      <!-- 逐题正确率。客观题看正确率，主观题看得分率 -->
      <div class="card">
        <div class="panel-title">
          <h3>逐题作答分析</h3>
          <span class="spacer">主观题没有正确率，改看得分率</span>
        </div>
        <table class="table">
          <thead>
            <tr>
              <th style="width: 56px" class="num">题号</th>
              <th style="width: 90px">题型</th>
              <th>题干</th>
              <th style="width: 70px" class="num">满分</th>
              <th style="width: 90px" class="num">作答 / 留空</th>
              <th style="width: 90px" class="num">正确率</th>
              <th style="width: 90px" class="num">平均分</th>
              <th style="width: 80px" class="num">得分率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in questions" :key="item.paperQuestionId">
              <td class="num">{{ item.displayOrder }}</td>
              <td>{{ typeLabels[item.type] ?? item.type }}</td>
              <td class="cell-strong">
                {{ item.stem.length > 40 ? `${item.stem.slice(0, 40)}…` : item.stem }}
                <small v-if="item.ungradedCount" class="state-off"> · {{ item.ungradedCount }} 份未评</small>
              </td>
              <td class="num">{{ item.maxScore }}</td>
              <td class="num">{{ item.answeredCount }} / {{ item.blankCount }}</td>
              <!-- 正确率低于 60% 标红：这类题往往是题目本身有问题，值得回题库复核 -->
              <td class="num" :class="item.correctRate !== null && item.correctRate < 60 ? 'state-off' : ''">
                {{ formatPercent(item.correctRate) }}
              </td>
              <td class="num">{{ formatScore(item.averageScore) }}</td>
              <td class="num">{{ formatPercent(item.scoreRate) }}</td>
            </tr>
            <tr v-if="!questions.length"><td colspan="8"><p class="empty">这场考试还没有已交卷的答卷</p></td></tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<style scoped>
/* 两张分布图并排；窄屏时自动堆叠 */
.grid-2 { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; }
</style>
