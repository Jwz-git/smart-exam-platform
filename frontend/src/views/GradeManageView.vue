<script setup lang="ts">
/**
 * 成绩管理页（教师）：阅卷 → 汇总 → 公布 → 排名，四件事放在同一页。
 *
 * 不拆成多个页面是有意的：教师的实际动作是「批完这场考试的卷子，看一眼分布，然后公布」，
 * 中间任何一步都需要看到另外两步的状态——批到一半时要知道还剩几份，公布前要看平均分是否异常。
 * 拆页会让这条动线反复来回跳。
 *
 * 页面只做展示和调用，所有规则都在后端：能不能公布、分数上界、公布后能否改分，
 * 都由后端返回错误决定，前端的按钮禁用只是提前告知，不构成保证。
 */
import { computed, onMounted, ref, watch } from 'vue'
import SubmissionGradeModal from './SubmissionGradeModal.vue'
import {
  api, examStatusLabels, submissionStatusLabels,
  type Exam, type ExamResults, type GradingBoard, type GradingItem, type SubmissionDetail,
} from '../api'
import { formatScore, formatWhen } from '../format'
import { token } from '../session'

const exams = ref<Exam[]>([])
/** 当前选中的考试 ID，用字符串以便直接绑定 select。 */
const examId = ref('')
const board = ref<GradingBoard | null>(null)
const results = ref<ExamResults | null>(null)
/** 正在查看的答卷详情；为 null 表示弹窗关闭。 */
const detail = ref<SubmissionDetail | null>(null)
const loading = ref(false)
const error = ref('')
const notice = ref('')

/** 只有已发布或已公布成绩的考试才可能有答卷，草稿考试不进下拉框。 */
const gradableExams = computed(() => exams.value.filter((exam) => exam.status !== 'DRAFT'))

/** 成绩是否已公布。公布后评分冻结，界面同步收起评分框和公布按钮。 */
const published = computed(() => board.value?.resultsPublished ?? false)

/**
 * 是否满足公布条件：有答卷、无人在作答、主观题已批完、尚未公布。
 *
 * 与后端 `GradingService#publishResults` 的四条校验一一对应。前端算一遍只是为了
 * 把按钮置灰并说明原因，后端仍会独立校验。
 */
const canPublish = computed(() => {
  const current = board.value
  if (!current || published.value) return false
  return current.submissionCount > 0 && current.inProgressCount === 0 && current.pendingCount === 0
})

/** 不能公布时的具体原因，直接显示给教师，避免只给一个灰按钮让人猜。 */
const publishBlockReason = computed(() => {
  const current = board.value
  if (!current || published.value) return ''
  if (!current.submissionCount) return '还没有学生交卷，暂时无法公布成绩。'
  if (current.inProgressCount > 0) return `还有 ${current.inProgressCount} 人正在作答，等全部交卷后才能公布。`
  if (current.pendingCount > 0) return `还有 ${current.pendingCount} 道主观题未评分，批完后才能公布。`
  return ''
})

/** 拉取教师本人的考试列表，并默认选中最近一场可阅卷的考试。 */
async function loadExams() {
  error.value = ''
  try {
    exams.value = await api.exams(token.value)
    const first = gradableExams.value[0]
    if (!examId.value && first) examId.value = String(first.id)
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/** 同时刷新阅卷面板和成绩统计：两者总是一起看，分开请求只是接口边界不同。 */
async function loadBoard() {
  if (!examId.value) { board.value = null; results.value = null; return }
  loading.value = true
  error.value = ''
  try {
    const id = Number(examId.value)
    board.value = await api.gradingBoard(token.value, id)
    results.value = await api.examResults(token.value, id)
  } catch (reason) {
    error.value = (reason as Error).message
    board.value = null
    results.value = null
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadExams()
  await loadBoard()
})
// 切换考试就重新取数；这里可以直接监听，因为下拉框的变化就是用户的明确意图。
watch(examId, () => void loadBoard())

/** 打开答卷详情。仍在作答的答卷也允许查看，教师可以据此判断是否需要等待。 */
async function openDetail(item: GradingItem) {
  error.value = ''
  try {
    detail.value = await api.submissionDetail(token.value, item.submissionId)
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/**
 * 保存一道主观题的评分。
 *
 * 后端返回重算后的整份答卷，直接替换弹窗数据，教师立刻看到新的总分；
 * 同时刷新列表，让「待批数量」和排名跟着更新。
 */
async function score(answerId: number, value: number, comment: string | null) {
  error.value = ''
  notice.value = ''
  try {
    detail.value = await api.scoreAnswer(token.value, answerId, value, comment)
    notice.value = `已保存评分，当前总分 ${formatScore(detail.value.totalScore)} 分。`
    await loadBoard()
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/** 公布成绩。二次确认，因为公布会同时关闭考试并冻结评分，且没有撤回接口。 */
async function publish() {
  const current = board.value
  if (!current) return
  if (!window.confirm(`公布后学生可以查看本人成绩和答卷，考试将关闭且不能再改分，确认公布「${current.examName}」的成绩吗？`)) return
  error.value = ''
  notice.value = ''
  try {
    results.value = await api.publishResults(token.value, current.examId)
    notice.value = '成绩已公布，学生现在可以查看本人成绩与答卷。'
    await loadExams()
    await loadBoard()
  } catch (reason) {
    error.value = (reason as Error).message
  }
}
</script>

<template>
  <section class="content">
    <div class="page-head">
      <h2>成绩管理</h2>
      <span class="spacer" />
      <button class="btn" type="button" :disabled="loading" @click="loadBoard">刷新</button>
      <button class="btn btn-primary" type="button" :disabled="!canPublish" @click="publish">
        {{ published ? '已公布成绩' : '公布成绩' }}
      </button>
    </div>

    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert ok" role="status">{{ notice }}</p>

    <!-- 考试选择器。草稿考试不出现在这里，因为它不可能有答卷 -->
    <div class="card card-pad filter-bar">
      <label>考试
        <select v-model="examId" style="min-width: 260px">
          <option value="" disabled>请选择考试</option>
          <option v-for="exam in gradableExams" :key="exam.id" :value="String(exam.id)">
            {{ exam.name }}（{{ examStatusLabels[exam.status] ?? exam.status }} · {{ exam.totalScore }} 分）
          </option>
        </select>
      </label>
      <span v-if="publishBlockReason" class="muted">{{ publishBlockReason }}</span>
    </div>
    <p v-if="!gradableExams.length" class="muted">还没有已发布的考试，请先到「考试管理」创建并发布一场考试。</p>

    <template v-if="board">
      <!-- 统计卡。已评卷数只统计批完的答卷，未批完的不进平均分，否则会被半成品拉低 -->
      <div class="card card-pad">
        <div class="stat-row">
          <div class="stat"><span>答卷数</span><b>{{ board.submissionCount }}</b></div>
          <div class="stat"><span>作答中</span><b>{{ board.inProgressCount }}</b></div>
          <div class="stat"><span>待批主观题</span><b>{{ board.pendingCount }}</b></div>
          <div class="stat"><span>已评卷数</span><b>{{ results?.gradedCount ?? 0 }}</b></div>
          <div class="stat"><span>平均分</span><b class="cell-strong">{{ formatScore(results?.averageScore ?? null) }}</b></div>
          <div class="stat"><span>最高分</span><b>{{ formatScore(results?.highestScore ?? null) }}</b></div>
          <div class="stat"><span>最低分</span><b>{{ formatScore(results?.lowestScore ?? null) }}</b></div>
          <div class="stat"><span>及格率</span>
            <b>{{ results?.passRate === null || results?.passRate === undefined ? '—' : `${results.passRate}%` }}</b>
          </div>
        </div>
        <p class="muted" style="margin-top: 10px">
          试卷满分 {{ board.paperTotalScore }} 分，及格线按 60% 计算；
          公布时间：{{ formatWhen(results?.resultsPublishedAt ?? null) }}
        </p>
      </div>

      <!-- 阅卷面板。「待批」列为 0 表示这份卷子已批完 -->
      <div class="card">
        <div class="panel-title"><h3>阅卷面板</h3><span class="spacer">{{ board.items.length }} 份答卷</span></div>
        <table class="table">
          <thead>
            <tr>
              <th style="width: 120px">学生</th>
              <th style="width: 80px">状态</th>
              <th style="width: 180px">交卷时间</th>
              <th style="width: 80px" class="num">客观题</th>
              <th style="width: 80px" class="num">主观题</th>
              <th style="width: 80px" class="num">总分</th>
              <th style="width: 100px" class="num">待批 / 主观题</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in board.items" :key="item.submissionId">
              <td class="cell-strong">{{ item.studentName }}</td>
              <td :class="item.status === 'IN_PROGRESS' ? 'state-off' : 'state-on'">
                {{ submissionStatusLabels[item.status] ?? item.status }}
              </td>
              <td class="muted">{{ formatWhen(item.submittedAt) }}</td>
              <td class="num">{{ item.objectiveScore }}</td>
              <td class="num">{{ item.subjectiveScore }}</td>
              <td class="num cell-strong">{{ item.totalScore }}</td>
              <!-- 待批数量用颜色区分：还有待批标红，批完转绿 -->
              <td class="num" :class="item.pendingCount ? 'state-off' : 'state-on'">
                {{ item.pendingCount }} / {{ item.subjectiveCount }}
              </td>
              <td class="ops">
                <button class="btn-link" type="button" @click="openDetail(item)">
                  {{ item.pendingCount && !published ? '阅卷' : '查看' }}
                </button>
              </td>
            </tr>
            <tr v-if="!board.items.length"><td colspan="8"><p class="empty">还没有学生答卷</p></td></tr>
          </tbody>
        </table>
      </div>

      <!-- 完整排名。同分并列且占用名次，例如 1、2、2、4 -->
      <div class="card">
        <div class="panel-title">
          <h3>成绩排名</h3>
          <span class="spacer">同分并列，名次形如 1、2、2、4</span>
        </div>
        <table class="table">
          <thead>
            <tr>
              <th style="width: 70px" class="num">名次</th>
              <th>学生</th>
              <th style="width: 100px" class="num">客观题</th>
              <th style="width: 100px" class="num">主观题</th>
              <th style="width: 100px" class="num">总分</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in results?.rankings ?? []" :key="item.submissionId">
              <td class="num cell-strong">{{ item.rank }}</td>
              <td>{{ item.studentName }}</td>
              <td class="num">{{ item.objectiveScore }}</td>
              <td class="num">{{ item.subjectiveScore }}</td>
              <td class="num cell-strong">{{ item.totalScore }}</td>
            </tr>
            <tr v-if="!(results?.rankings ?? []).length">
              <td colspan="5"><p class="empty">还没有已评完的答卷，排名为空</p></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 答卷详情弹窗。成绩公布后传 gradable=false，与后端「公布即冻结评分」保持一致 -->
    <SubmissionGradeModal
      v-if="detail"
      :detail="detail"
      :gradable="!published"
      @close="detail = null"
      @score="score"
    />
  </section>
</template>
