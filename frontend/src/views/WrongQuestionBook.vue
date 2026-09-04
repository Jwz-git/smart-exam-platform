<script setup lang="ts">
/**
 * 学生错题本与错题重练。
 *
 * 三个刻意的设计决定：
 *
 * 1. **只有已公布成绩的考试才会出现在这里。** 这是后端的约束，前端如实说明——
 *    否则学生会以为「刚考完的卷子怎么没有错题」是个 bug。
 * 2. **复习和重练是两种模式，拿到的数据也不同。** 复习模式带标准答案、解析和教师评语；
 *    重练模式的接口<b>不下发标准答案</b>，提交后才返回对错与正确答案。
 *    否则「重练」就退化成抄一遍答案。
 * 3. **重练不改动成绩。** 练多少次都只写练习记录，答卷分数、名次一个字都不动，
 *    界面上也把这句话写出来，免得学生担心「练错了会不会扣分」。
 *
 * 主观题（简答、编程）只能复习：没有确定性判分规则，给不出对错。
 */
import { computed, onMounted, ref } from 'vue'
import {
  api, isChoice, typeLabels,
  type PracticeQuestion, type PracticeResult, type PracticeSet, type WrongBook,
} from '../api'
import { formatAnswer, formatWhen } from '../format'
import { token } from '../session'

/** 一道题的作答值：选择题是选项键数组、判断题是布尔值、未作答是 null。 */
type AnswerValue = string[] | boolean | null

/** 三种模式：错题清单、正在重练、本次结果。 */
const mode = ref<'book' | 'practice' | 'result'>('book')
const book = ref<WrongBook | null>(null)
const practice = ref<PracticeSet | null>(null)
const result = ref<PracticeResult | null>(null)
/** 重练时的作答，键是试卷题目 ID。 */
const answers = ref<Record<number, AnswerValue>>({})
/** 展开了解析的行，键是答案记录 ID。 */
const expanded = ref<number[]>([])
const error = ref('')
const busy = ref(false)

/** 只练还没练对的题；关掉之后已掌握的题也会进练习集。 */
const onlyUnmastered = ref(true)

/** 有可重练的客观题才允许开始重练，避免点了之后只得到一句错误提示。 */
const canPractice = computed(() => (book.value?.objectiveCount ?? 0) > 0)

/** 拉取错题本。 */
async function load() {
  error.value = ''
  busy.value = true
  try {
    book.value = await api.wrongBook(token.value)
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(load)

/** 展开或收起某一行的标准答案与解析。 */
function toggle(answerId: number) {
  expanded.value = expanded.value.includes(answerId)
    ? expanded.value.filter((id) => id !== answerId)
    : [...expanded.value, answerId]
}

/** 开始一组重练。练习集由后端挑选（练得少的优先），前端不自己决定练哪些。 */
async function startPractice() {
  error.value = ''
  busy.value = true
  try {
    const set = await api.practiceSet(token.value, { onlyUnmastered: onlyUnmastered.value })
    practice.value = set
    answers.value = Object.fromEntries(set.questions.map((question) => [
      question.paperQuestionId, isChoice(question.type) ? [] : null,
    ]))
    result.value = null
    mode.value = 'practice'
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}

/** 选择题作答：单选覆盖，多选切换。与答题页的处理保持一致。 */
function pick(question: PracticeQuestion, key: string) {
  if (question.type === 'MULTIPLE_CHOICE') {
    const picked = Array.isArray(answers.value[question.paperQuestionId])
      ? (answers.value[question.paperQuestionId] as string[])
      : []
    answers.value[question.paperQuestionId] = picked.includes(key)
      ? picked.filter((item) => item !== key)
      : [...picked, key]
  } else {
    answers.value[question.paperQuestionId] = [key]
  }
}

/** 判断题作答。false 也是有效作答，因此不能用真假判断是否已答。 */
function setBoolean(question: PracticeQuestion, value: boolean) {
  answers.value[question.paperQuestionId] = value
}

/** 某个选项是否被选中。 */
function isPicked(question: PracticeQuestion, key: string) {
  const value = answers.value[question.paperQuestionId]
  return Array.isArray(value) && value.includes(key)
}

/** 提交重练。判分在服务端完成，复用交卷时的同一套客观题比较规则。 */
async function submit() {
  if (!practice.value) return
  error.value = ''
  busy.value = true
  try {
    result.value = await api.submitPractice(token.value, practice.value.questions.map((question) => ({
      paperQuestionId: question.paperQuestionId,
      answerContent: answers.value[question.paperQuestionId] ?? null,
    })))
    mode.value = 'result'
    // 练完立刻刷新错题本：练习次数和「已掌握」标记都会变。
    await load()
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}

/** 回到错题清单。 */
function backToBook() {
  mode.value = 'book'
  practice.value = null
  result.value = null
}

/**
 * 列表里作答的简短形态。
 *
 * 简答题和编程题的作答动辄十几行，直接塞进表格的一格会把行高撑到半屏（走查发现）。
 * 列表里只给开头一段，完整内容在展开的详情里显示。
 */
function brief(item: WrongBook['items'][number]) {
  const text = formatAnswer(item.myAnswer, item.options)
  return text.length > 36 ? `${text.slice(0, 36)}…` : text
}

/**
 * 作答文本的配色。
 *
 * 只有客观题答错才标红：主观题拿了 8/10 也在错题本里，把它标成红色等于告诉学生「这答案是错的」，
 * 而它其实只是没拿满分。未作答用灰色。
 */
function answerClass(item: WrongBook['items'][number]) {
  if (item.blank) return 'muted'
  return item.subjective ? '' : 'state-off'
}

/** 错题的状态标签：主观题只能复习，客观题分已掌握与未掌握。 */
function statusLabel(item: WrongBook['items'][number]) {
  if (item.subjective) return '仅复习'
  return item.mastered ? '已掌握' : '未掌握'
}
function statusClass(item: WrongBook['items'][number]) {
  if (item.subjective) return 'muted'
  return item.mastered ? 'state-on' : 'state-off'
}
</script>

<template>
  <div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>

    <!-- 模式一：错题清单 -->
    <template v-if="mode === 'book'">
      <div class="card" style="margin-bottom: 14px">
        <div class="panel-title">
          <h3>错题概况</h3>
          <span class="spacer">只收录已公布成绩的考试；重练不会改动任何成绩</span>
        </div>
        <div class="stat-row card-pad">
          <div class="stat"><span>错题总数</span><b class="cell-strong">{{ book?.total ?? 0 }}</b></div>
          <div class="stat"><span>可重练（客观题）</span><b>{{ book?.objectiveCount ?? 0 }}</b></div>
          <div class="stat"><span>仅复习（主观题）</span><b class="muted">{{ book?.subjectiveCount ?? 0 }}</b></div>
          <div class="stat"><span>已掌握</span><b class="state-on">{{ book?.masteredCount ?? 0 }}</b></div>
        </div>
        <div class="filter-bar card-pad" style="padding-top: 0">
          <button class="btn btn-primary" type="button" :disabled="busy || !canPractice" @click="startPractice">
            开始重练{{ book ? ` ${Math.min(book.practiceBatchSize, book.objectiveCount)} 道` : '' }}
          </button>
          <label>
            <input v-model="onlyUnmastered" type="checkbox" aria-label="只练还没练对的题" />
            只练还没练对的题
          </label>
          <button class="btn" type="button" :disabled="busy" @click="load">刷新</button>
          <span v-if="!canPractice" class="muted">暂无可自动判分的客观错题；主观题请对照下方参考答案复习。</span>
        </div>
      </div>

      <div class="card">
        <table class="table">
          <thead>
            <tr>
              <th>考试与题目</th>
              <th style="width: 76px">题型</th>
              <th style="width: 92px" class="num">得分</th>
              <th style="width: 140px">我的作答</th>
              <th style="width: 82px" class="num">练习次数</th>
              <th style="width: 76px">状态</th>
              <th style="width: 76px">操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in book?.items ?? []" :key="item.answerId">
              <tr>
                <td>
                  <p class="cell-strong">{{ item.displayOrder }}. {{ item.stem }}</p>
                  <p class="muted">{{ item.examName }} · {{ formatWhen(item.submittedAt) }}</p>
                </td>
                <td>{{ typeLabels[item.type] }}</td>
                <td class="num">
                  <span :class="item.score > 0 ? 'muted' : 'state-off'">{{ item.score }}</span>
                  <span class="muted"> / {{ item.maxScore }}</span>
                </td>
                <!-- 「未作答」和「答错」要能分开：前者是漏题或时间不够，后者是真的不会 -->
                <td :class="answerClass(item)">{{ item.blank ? '未作答' : brief(item) }}</td>
                <td class="num">{{ item.practiceCount }}</td>
                <td :class="statusClass(item)">{{ statusLabel(item) }}</td>
                <td class="ops">
                  <button class="btn-link" type="button" @click="toggle(item.answerId)">
                    {{ expanded.includes(item.answerId) ? '收起' : '解析' }}
                  </button>
                </td>
              </tr>
              <!-- 展开行：标准答案、解析和教师评语。成绩已公布，这些内容本人可见 -->
              <tr v-if="expanded.includes(item.answerId)">
                <td colspan="7" style="background: #fafbfd">
                  <!-- 完整作答只在这里显示：列表里那一格放不下十几行的简答题 -->
                  <p style="white-space: pre-wrap">
                    <b>我的作答：</b>{{ item.blank ? '未作答' : formatAnswer(item.myAnswer, item.options) }}
                  </p>
                  <p><b>参考答案：</b>{{ formatAnswer(item.standardAnswer, item.options) }}</p>
                  <p v-if="item.explanation" class="muted"><b>解析：</b>{{ item.explanation }}</p>
                  <p v-if="item.gradingComment" class="muted"><b>教师评语：</b>{{ item.gradingComment }}</p>
                  <p v-if="item.lastPracticedAt" class="muted">
                    最近重练：{{ formatWhen(item.lastPracticedAt) }}（{{ item.lastCorrect ? '答对' : '答错' }}）
                  </p>
                </td>
              </tr>
            </template>
            <tr v-if="!book?.items.length">
              <td colspan="7">
                <p class="empty">还没有错题。成绩公布后，未得满分的题目会自动出现在这里。</p>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 模式二：正在重练。刻意不显示标准答案，提交后才给 -->
    <template v-else-if="mode === 'practice' && practice">
      <div class="card">
        <div class="panel-title">
          <h3>错题重练</h3>
          <span class="spacer">共 {{ practice.size }} 题 · 提交后即时判分 · 不影响原有成绩</span>
        </div>
        <div class="card-pad" style="display: grid; gap: 18px">
          <div v-for="(question, index) in practice.questions" :key="question.paperQuestionId">
            <p class="cell-strong">
              {{ index + 1 }}. {{ question.stem }}
              <span class="muted">（{{ typeLabels[question.type] }} · {{ question.examName }}
                · 已练 {{ question.practiceCount }} 次）</span>
            </p>
            <template v-if="isChoice(question.type)">
              <label v-for="option in question.options ?? []" :key="option.key" class="choice"
                     :class="{ picked: isPicked(question, option.key) }">
                <input
                  :type="question.type === 'MULTIPLE_CHOICE' ? 'checkbox' : 'radio'"
                  :name="`practice-${question.paperQuestionId}`"
                  :checked="isPicked(question, option.key)"
                  @change="pick(question, option.key)"
                />
                <span>{{ option.key }}. {{ option.content }}</span>
              </label>
            </template>
            <template v-else>
              <label class="choice" :class="{ picked: answers[question.paperQuestionId] === true }">
                <input type="radio" :name="`practice-${question.paperQuestionId}`"
                       :checked="answers[question.paperQuestionId] === true"
                       @change="setBoolean(question, true)" />
                <span>正确</span>
              </label>
              <label class="choice" :class="{ picked: answers[question.paperQuestionId] === false }">
                <input type="radio" :name="`practice-${question.paperQuestionId}`"
                       :checked="answers[question.paperQuestionId] === false"
                       @change="setBoolean(question, false)" />
                <span>错误</span>
              </label>
            </template>
          </div>
        </div>
        <div class="filter-bar card-pad" style="border-top: 1px solid var(--line)">
          <button class="btn btn-primary" type="button" :disabled="busy" @click="submit">
            {{ busy ? '提交中…' : '提交并查看结果' }}
          </button>
          <button class="btn" type="button" :disabled="busy" @click="backToBook">返回错题本</button>
        </div>
      </div>
    </template>

    <!-- 模式三：本次结果。到这一步才给出正确答案与解析 -->
    <template v-else-if="mode === 'result' && result">
      <div class="card">
        <div class="panel-title">
          <h3>本次重练结果</h3>
          <span class="spacer">判分规则与考试完全相同；本次练习不计入成绩</span>
        </div>
        <div class="stat-row card-pad">
          <div class="stat"><span>题数</span><b>{{ result.total }}</b></div>
          <div class="stat"><span>答对</span><b class="state-on">{{ result.correctCount }}</b></div>
          <div class="stat"><span>正确率</span><b class="cell-strong">{{ result.accuracy ?? '—' }}%</b></div>
        </div>
        <table class="table">
          <thead>
            <tr>
              <th style="width: 44px">题号</th>
              <th>题目</th>
              <th style="width: 64px">结果</th>
              <th style="width: 150px">我的作答</th>
              <th style="width: 150px">参考答案</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(answer, index) in result.answers" :key="answer.paperQuestionId">
              <td class="muted">{{ index + 1 }}</td>
              <td>
                <p class="cell-strong">{{ answer.stem }}</p>
                <p v-if="answer.explanation" class="muted">解析：{{ answer.explanation }}</p>
              </td>
              <td :class="answer.correct ? 'state-on' : 'state-off'">{{ answer.correct ? '答对' : '答错' }}</td>
              <td>{{ formatAnswer(answer.myAnswer) }}</td>
              <td class="cell-strong">{{ formatAnswer(answer.standardAnswer) }}</td>
            </tr>
          </tbody>
        </table>
        <div class="filter-bar card-pad" style="border-top: 1px solid var(--line)">
          <button class="btn btn-primary" type="button" :disabled="busy || !canPractice" @click="startPractice">
            再练一组
          </button>
          <button class="btn" type="button" @click="backToBook">返回错题本</button>
        </div>
      </div>
    </template>
  </div>
</template>
