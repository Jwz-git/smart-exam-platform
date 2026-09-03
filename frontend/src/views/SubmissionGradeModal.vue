<script setup lang="ts">
/**
 * 答卷详情弹窗，同时承担教师阅卷和学生成绩回看两个角色。
 *
 * 两者共用一个组件是因为要展示的内容几乎相同——逐题的题干、作答、标准答案、解析和得分。
 * 差别只有两点，都由 props 控制：
 * - `gradable` 为真时，主观题下方显示评分输入框（教师阅卷）；
 * - 后端已经按角色裁剪过字段，学生在成绩公布前拿到的 `standardAnswer` 和 `score` 就是 null，
 *   模板里用 `v-if` 判空即可，不需要前端再判断一次「该不该显示」。
 *
 * 也就是说，即使把 `gradable` 误传成 true，学生也拿不到别人的答卷或未公布的分数——
 * 真正的隔离在后端。
 */
import { reactive, ref, watch } from 'vue'
import { typeLabels, type AnswerDetail, type SubmissionDetail } from '../api'
import { formatAnswer, formatScore, formatWhen } from '../format'

/**
 * @property detail   要展示的答卷详情
 * @property gradable 是否允许评分。教师阅卷传 true；成绩已公布后应传 false，因为后端会冻结评分
 */
const props = defineProps<{ detail: SubmissionDetail; gradable: boolean }>()

/** 评分动作交给父组件执行：它持有令牌，也负责刷新列表。 */
const emit = defineEmits<{ close: []; score: [answerId: number, score: number, comment: string | null] }>()

/**
 * 每道主观题的评分草稿，键是答案记录 ID。分开存放，教师可以先填完几题再逐一提交。
 *
 * `score` 声明为 `number | string`：Vue 的 v-model 在 `type="number"` 的输入框上会自动把值
 * 转成数字，但清空输入框时又会得到空字符串，两种形态都必须容纳。
 */
const drafts = reactive<Record<number, { score: number | string; comment: string }>>({})
const error = ref('')

/**
 * 详情变化时重建评分草稿。
 *
 * 用后端返回的现有得分和评语做初值：教师改分时不用重新输入，看到的就是当前已保存的值。
 * 未评分的题目留空而不是填 0——空框表示「还没打分」，0 表示「打了 0 分」。
 */
watch(() => props.detail, (detail) => {
  error.value = ''
  detail.answers.filter((answer) => answer.subjective).forEach((answer) => {
    drafts[answer.id] = {
      score: answer.gradedAt && answer.score !== null ? String(answer.score) : '',
      comment: answer.gradingComment ?? '',
    }
  })
}, { immediate: true, deep: false })

/** 提交一道主观题的评分。前端只挡住空值、负数和超过满分，其余交给后端校验。 */
function submitScore(answer: AnswerDetail) {
  error.value = ''
  const draft = drafts[answer.id]
  const raw = draft === undefined ? '' : String(draft.score).trim()
  if (!draft || raw === '') { error.value = '请先填写得分'; return }
  const score = Number(raw)
  if (Number.isNaN(score) || score < 0) { error.value = '得分必须是不小于 0 的数字'; return }
  if (score > answer.maxScore) { error.value = `得分不能超过该题满分 ${answer.maxScore}`; return }
  emit('score', answer.id, score, draft.comment.trim() || null)
}

/** 客观题是否判对：得分等于满分即为答对。用于给学生的回看页标注对错。 */
const isCorrect = (answer: AnswerDetail) => answer.score !== null && answer.score >= answer.maxScore
</script>

<template>
  <div class="modal-mask" role="dialog" aria-modal="true" aria-label="答卷详情" @click.self="emit('close')">
    <div class="modal modal-wide">
      <header>
        <span>{{ props.detail.examName }} · {{ props.detail.studentName }}</span>
        <button class="btn-link" type="button" style="margin-left: auto" @click="emit('close')">关闭</button>
      </header>

      <div class="body">
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>

        <!-- 分数概览。成绩公布前学生拿到的三个分数都是 null，这里会显示为破折号 -->
        <div class="stat-row">
          <div class="stat"><span>客观题</span><b>{{ formatScore(props.detail.objectiveScore) }}</b></div>
          <div class="stat"><span>主观题</span><b>{{ formatScore(props.detail.subjectiveScore) }}</b></div>
          <div class="stat"><span>总分</span><b class="cell-strong">{{ formatScore(props.detail.totalScore) }}</b></div>
          <div class="stat"><span>试卷满分</span><b>{{ props.detail.paperTotalScore }}</b></div>
          <div class="stat"><span>名次</span><b>{{ formatScore(props.detail.rank) }}</b></div>
          <div class="stat"><span>交卷时间</span><b>{{ formatWhen(props.detail.submittedAt) }}</b></div>
        </div>
        <p v-if="!props.detail.resultsPublished" class="muted">
          成绩尚未公布，学生端此时看不到分数、标准答案和评语。
        </p>

        <!-- 逐题明细。每题一张卡片，顺序与试卷一致 -->
        <div v-for="answer in props.detail.answers" :key="answer.paperQuestionId" class="card card-pad answer-card">
          <p class="stem">
            {{ answer.displayOrder }}. {{ answer.stem }}
            <span class="muted">（{{ typeLabels[answer.type] }} · {{ answer.maxScore }} 分）</span>
          </p>

          <!-- 选项列表只在选择题下出现，便于对照学生选了哪个 -->
          <ul v-if="answer.options && answer.options.length" class="option-list">
            <li v-for="option in answer.options" :key="option.key">{{ option.key }}. {{ option.content }}</li>
          </ul>

          <p class="answer-line"><span class="label">学生作答</span>
            <b>{{ formatAnswer(answer.answerContent, answer.options) }}</b>
          </p>
          <!-- 标准答案与解析在成绩公布前对学生为 null，因此用 v-if 而不是显示空值 -->
          <p v-if="answer.standardAnswer !== null && answer.standardAnswer !== undefined" class="answer-line">
            <span class="label">标准答案</span><b>{{ formatAnswer(answer.standardAnswer, answer.options) }}</b>
          </p>
          <p v-if="answer.explanation" class="answer-line"><span class="label">解析</span>
            <span class="muted">{{ answer.explanation }}</span>
          </p>

          <!-- 客观题：只显示系统判分结果，教师也不能改，避免绕过确定性判分规则 -->
          <p v-if="!answer.subjective && answer.score !== null" class="answer-line">
            <span class="label">得分</span>
            <b :class="isCorrect(answer) ? 'state-on' : 'state-off'">{{ answer.score }} / {{ answer.maxScore }}</b>
            <span class="muted">系统自动判分</span>
          </p>

          <!-- 主观题：教师阅卷时显示评分框，其余场景只显示已评的分数和评语 -->
          <template v-if="answer.subjective">
            <div v-if="props.gradable && drafts[answer.id]" class="grade-box">
              <label class="field" style="width: 130px">
                <span>得分（≤ {{ answer.maxScore }}）</span>
                <input v-model="drafts[answer.id]!.score" type="number" min="0" :max="answer.maxScore" step="0.1" />
              </label>
              <label class="field" style="flex: 1">
                <span>评语（选填）</span>
                <input v-model="drafts[answer.id]!.comment" maxlength="1000" placeholder="给学生的反馈" />
              </label>
              <button class="btn btn-primary" type="button" @click="submitScore(answer)">保存评分</button>
              <span v-if="answer.gradedAt" class="state-on">已评 {{ answer.score }} 分</span>
              <span v-else class="state-off">待评分</span>
            </div>
            <template v-else>
              <p class="answer-line"><span class="label">得分</span>
                <b>{{ formatScore(answer.score) }}<template v-if="answer.score !== null"> / {{ answer.maxScore }}</template></b>
              </p>
              <p v-if="answer.gradingComment" class="answer-line"><span class="label">教师评语</span>
                <span>{{ answer.gradingComment }}</span>
              </p>
            </template>
          </template>
        </div>
      </div>

      <footer>
        <button class="btn" type="button" @click="emit('close')">关闭</button>
      </footer>
    </div>
  </div>
</template>
