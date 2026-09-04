<script setup lang="ts">
/**
 * 学生端：考试列表 + 单题作答页，对应原型第 4 张图。
 *
 * 三个关键设计：
 *
 * 1. 答案随时同步到服务端。切题时和每 30 秒各调一次保存接口。这不是为了「自动保存」这个
 *    功能本身，而是为了让服务端的超时自动交卷有真实答案可判——早期版本只在点「交卷」时
 *    才提交答案，学生一旦超时未交，后端拿到的是一张空白卷，直接判 0 分。
 *
 * 2. 倒计时终点取「考试结束时间」和「开始作答 + 试卷时长」中更早的一个。只看考试结束时间
 *    会让试卷时长形同虚设（3 小时的考试窗口里 30 分钟的卷子能答 3 小时）。
 *    注意服务端目前只按考试结束时间自动交卷，试卷时长的服务端约束仍是待办项。
 *
 * 3. 一次只显示一题，配右侧题号导航。这是原型的要求，也便于展示「已答 n/N」和
 *    未答/已答/当前三种状态。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import SubmissionGradeModal from './SubmissionGradeModal.vue'
import WrongQuestionBook from './WrongQuestionBook.vue'
import {
  api, isChoice, typeLabels,
  type Exam, type MyResult, type PaperQuestion, type Submission, type SubmissionDetail,
} from '../api'
import { formatWhen } from '../format'
import { token, user } from '../session'

/**
 * 一道题的作答值，形态随题型变化：
 * 选择题是选项键数组、判断题是布尔值、简答题和编程题是字符串、未作答是 null。
 */
type AnswerValue = string[] | boolean | string | null

/**
 * 学生端三个页签：考试列表、我的成绩、错题本。学生没有侧栏，用页签切换成本最低。
 *
 * 错题本单独抽成组件而不是写在这里：它自带「清单 / 重练 / 结果」三种模式，
 * 塞进这个已经很长的文件只会让答题主流程更难读。
 */
const tab = ref<'exams' | 'results' | 'wrong'>('exams')
/** 可参加的考试列表。 */
const exams = ref<Exam[]>([])
/** 本人已公布的成绩列表。未公布的考试不会出现在这里。 */
const myResults = ref<MyResult[]>([])
/** 正在查看的本人答卷详情；为 null 表示弹窗关闭。 */
const resultDetail = ref<SubmissionDetail | null>(null)
/** 当前答卷；为 null 表示还在考试列表页。 */
const submission = ref<Submission | null>(null)
/** 当前作答的考试，倒计时需要它的结束时间和试卷时长。 */
const activeExam = ref<Exam | null>(null)
/** 作答内容，键是试卷题目 ID。 */
const answers = ref<Record<number, AnswerValue>>({})
/** 当前题目在题目数组里的下标。 */
const index = ref(0)
/** 每秒刷新的当前时间戳，倒计时依赖它触发重算。 */
const now = ref(Date.now())
const error = ref('')
const result = ref('')
const busy = ref(false)
const submitting = ref(false)

// 秒级时钟。组件卸载时必须清掉，否则离开页面后定时器仍在跑。
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => window.clearInterval(timer))

/** 交卷前的本地草稿键：刷新页面后先用它恢复界面，后端答案由 saveAnswers 负责。 */
const draftKey = (submissionId: number) => `examDraft:${user.value?.id ?? 0}:${submissionId}`

/** 当前答卷的题目列表。 */
const questions = computed<PaperQuestion[]>(() => submission.value?.questions ?? [])
/** 当前正在作答的题目。 */
const current = computed(() => questions.value[index.value] ?? null)

/** 已作答题数，用于顶栏的「已答 n/N」。 */
const answeredCount = computed(() =>
  questions.value.filter((question) => isAnswered(answers.value[question.id])).length)

/**
 * 判断一道题是否算作已答。
 *
 * 三种题型的「空」形态不同：选择题是空数组、文本题是空白字符串、未作答是 null。
 * 判断题只要选了就算已答，因此 false 也是有效作答，不能用 `if (!value)` 一刀切。
 */
function isAnswered(value: AnswerValue | undefined) {
  if (value === null || value === undefined) return false
  if (Array.isArray(value)) return value.length > 0
  if (typeof value === 'string') return value.trim().length > 0
  return true
}

/** 答题截止时间取“考试结束时间”和“开始作答 + 试卷时长”里更早的一个。 */
const deadline = computed(() => {
  if (!submission.value || !activeExam.value) return 0
  const examEnd = new Date(activeExam.value.endAt).getTime()
  const byDuration = new Date(submission.value.startedAt).getTime() + activeExam.value.durationMinutes * 60000
  return Math.min(examEnd, byDuration)
})
/** 剩余秒数，最小为 0，避免时间到后出现负数。 */
const remainingSeconds = computed(() => Math.max(0, Math.floor((deadline.value - now.value) / 1000)))
/** 剩余时间的 mm:ss 文本。分钟不截断到 60，超过一小时会显示成 90:00 这样，与原型一致。 */
const remainingText = computed(() => {
  const total = remainingSeconds.value
  const minutes = Math.floor(total / 60)
  return `${String(minutes).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
})

/** 按题型把连续题目分组，生成“一、单选题（本大题共 N 小题，共 M 分）”这样的标题。 */
const groups = computed(() => {
  const numerals = ['一', '二', '三', '四', '五', '六', '七', '八']
  const ranges: { from: number; to: number }[] = []
  questions.value.forEach((question, position) => {
    const last = ranges[ranges.length - 1]
    const previous = last ? questions.value[last.to] : undefined
    if (last && previous && previous.type === question.type) last.to = position
    else ranges.push({ from: position, to: position })
  })
  return ranges.flatMap((range, order) => {
    const items = questions.value.slice(range.from, range.to + 1)
    const head = items[0]
    if (!head) return []
    const sum = items.reduce((total, item) => total + item.score, 0)
    const uniform = items.every((item) => item.score === head.score)
    const per = uniform ? `每小题 ${head.score} 分，` : ''
    return [{
      ...range,
      label: `${numerals[order] ?? order + 1}、${typeLabels[head.type]}（本大题共 ${items.length} 小题，${per}共 ${Math.round(sum * 10) / 10} 分）`,
    }]
  })
})
const currentGroup = computed(() =>
  groups.value.find((group) => index.value >= group.from && index.value <= group.to) ?? null)

/**
 * 考试在列表里显示的状态。
 *
 * 结合考试时间窗口和本人答卷状态判断：已交卷 / 未开始 / 已结束 / 继续作答 / 可参加。
 * 顺序有讲究——已交卷要优先于时间判断，否则交完卷还会显示「可参加」。
 */
const statusLabel = (exam: Exam) => {
  if (exam.submissionStatus === 'SUBMITTED' || exam.submissionStatus === 'GRADED') return '已交卷'
  const start = new Date(exam.startAt).getTime()
  const end = new Date(exam.endAt).getTime()
  if (Date.now() < start) return '未开始'
  if (Date.now() >= end) return '已结束'
  return exam.submissionStatus === 'IN_PROGRESS' ? '继续作答' : '可参加'
}
/** 只有可参加和继续作答两种状态允许进入。按钮禁用只是体验，后端仍会校验时间和状态。 */
const canEnter = (exam: Exam) => ['可参加', '继续作答'].includes(statusLabel(exam))
const when = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })

/** 拉取已发布考试列表，附带本人答卷状态。 */
async function loadExams() {
  error.value = ''
  try {
    exams.value = await api.exams(token.value)
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/** 拉取本人已公布的成绩。未公布的考试后端不会返回，前端因此不需要再过滤一遍。 */
async function loadResults() {
  error.value = ''
  try {
    myResults.value = await api.myResults(token.value)
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/** 打开本人答卷详情。教师评语和标准答案都在这里查看。 */
async function openResult(item: MyResult) {
  error.value = ''
  try {
    resultDetail.value = await api.myResult(token.value, item.submissionId)
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

onMounted(loadExams)
// 切到「我的成绩」再拉数据：留在考试列表的学生不必为一个没看的页签付一次请求。
watch(tab, (current) => { if (current === 'results') void loadResults() })

/**
 * 进入考试。
 *
 * 开始作答接口是幂等的：已有未交卷答卷时返回原答卷，倒计时起点也保持不变，
 * 所以「进入考试」和「继续作答」可以走同一条路径。
 */
async function enter(exam: Exam) {
  error.value = ''
  result.value = ''
  busy.value = true
  try {
    const started = await api.startSubmission(token.value, exam.id)
    submission.value = started
    activeExam.value = exam
    index.value = 0
    answers.value = restoreAnswers(started)
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}

/**
 * 恢复作答：服务端已保存的答案优先，本地草稿只用来补服务端没有的题目。
 *
 * 顺序不能反。服务端那一份才是超时自动交卷实际判分的依据，如果让可能过期的本地草稿
 * 覆盖它，接下来的一次 syncAnswers 就会把旧答案写回服务端，造成真正的答案丢失。
 * 反过来最多损失最近一次同步之后的输入，也就是 30 秒以内——这正是自动暂存本来的粒度。
 *
 * 本地草稿仍然有用：断网时输入的答案服务端根本没收到，那些题目靠草稿补回来。
 */
function restoreAnswers(target: Submission): Record<number, AnswerValue> {
  const restored: Record<number, AnswerValue> = {}
  target.questions.forEach((question) => { restored[question.id] = isChoice(question.type) ? [] : null })
  let draft: Record<number, AnswerValue> = {}
  try {
    draft = (JSON.parse(localStorage.getItem(draftKey(target.id)) ?? 'null') ?? {}) as Record<number, AnswerValue>
  } catch {
    /* 草稿损坏时忽略，不阻塞答题。 */
  }
  const fromServer = new Set<number>()
  target.savedAnswers?.forEach((saved) => {
    if (!(saved.paperQuestionId in restored)) return
    const value = saved.answerContent as AnswerValue
    if (!isAnswered(value)) return
    restored[saved.paperQuestionId] = value
    fromServer.add(saved.paperQuestionId)
  })
  Object.entries(draft).forEach(([key, value]) => {
    const id = Number(key)
    if (id in restored && !fromServer.has(id) && isAnswered(value)) restored[id] = value
  })
  return restored
}

/** 把当前答案写入本地草稿。每次作答变化都调用，成本很低。 */
function persistDraft() {
  if (submission.value) localStorage.setItem(draftKey(submission.value.id), JSON.stringify(answers.value))
}

/** 选择题作答。单选覆盖为单元素数组，多选则切换该键的选中状态。 */
function pick(question: PaperQuestion, key: string) {
  if (question.type === 'MULTIPLE_CHOICE') {
    const picked = Array.isArray(answers.value[question.id]) ? (answers.value[question.id] as string[]) : []
    answers.value[question.id] = picked.includes(key) ? picked.filter((item) => item !== key) : [...picked, key]
  } else {
    answers.value[question.id] = [key]
  }
  persistDraft()
}

/** 判断题作答。 */
function setBoolean(question: PaperQuestion, value: boolean) {
  answers.value[question.id] = value
  persistDraft()
}

/** 某个选项是否被选中，用于渲染选中态样式和 checked 属性。 */
function isPicked(question: PaperQuestion, key: string) {
  const value = answers.value[question.id]
  return Array.isArray(value) && value.includes(key)
}

/** 把当前答案同步到服务端，让超时自动交卷也能拿到真实作答。失败不打断答题。 */
async function syncAnswers() {
  if (!submission.value) return
  persistDraft()
  const payload = questions.value.map((question) => ({
    paperQuestionId: question.id,
    answerContent: isAnswered(answers.value[question.id]) ? answers.value[question.id] : null,
  }))
  try {
    await api.saveAnswers(token.value, submission.value.id, payload)
  } catch (reason) {
    error.value = `答案暂存失败（${(reason as Error).message}），请检查网络后再试。`
  }
}

/** 跳到指定题目，顺便把答案同步到服务端。切题是最自然的保存时机。 */
async function goTo(target: number) {
  if (target < 0 || target >= questions.value.length) return
  index.value = target
  await syncAnswers()
}

/**
 * 交卷。
 *
 * @param auto true 表示倒计时归零后的自动交卷，此时不弹确认框——用户已经没有时间了，
 *             再等一次确认只会让答卷卡在未提交状态。
 *
 * 交卷前先同步一次答案，确保最后几秒的修改不丢；成功后清掉本地草稿，
 * 免得下次进入同一场考试时又把旧答案恢复出来。
 */
async function submitExam(auto = false) {
  if (!submission.value || submitting.value) return
  const unanswered = questions.value.length - answeredCount.value
  if (!auto) {
    const tip = unanswered ? `还有 ${unanswered} 题未作答，` : ''
    if (!window.confirm(`${tip}交卷后不能修改答案，确认交卷吗？`)) return
  }
  submitting.value = true
  try {
    await syncAnswers()
    const outcome = await api.submit(token.value, submission.value.id)
    localStorage.removeItem(draftKey(submission.value.id))
    result.value = auto
      ? `考试时间已到，系统已自动交卷。客观题得分 ${outcome.objectiveScore} 分，简答题和编程题等待教师评阅。`
      : `交卷成功。客观题得分 ${outcome.objectiveScore} 分，简答题和编程题等待教师评阅。`
    submission.value = null
    activeExam.value = null
    await loadExams()
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    submitting.value = false
  }
}

/**
 * 是否「正在作答且已到交卷时间」。
 *
 * 之所以合成这一个布尔量再去监听，而不是直接监听 remainingSeconds：
 * 学生可能在时间已经过去之后才重新进入考试，那时 remainingSeconds 从一开始就是 0，
 * 监听它不会收到任何变化，页面会停在 00:00 却什么都不做。而 submission 从 null 变成
 * 具体答卷时，这个布尔量会由 false 翻成 true，两种情形都能触发。
 *
 * 未进入考试时 deadline 为 0，因此必须带上 submission 判断，否则考试列表页会被误判为超时。
 */
const timeIsUp = computed(() => submission.value !== null && remainingSeconds.value === 0)

/** 到点立即自动交卷，不依赖后端 30 秒轮询，也避免答案停留在浏览器里。 */
watch(timeIsUp, (up) => {
  if (up) void submitExam(true)
}, { immediate: true })

/** 每 30 秒自动暂存一次，异常退出时最多丢失半分钟的作答。 */
const autoSave = window.setInterval(() => { if (submission.value) void syncAnswers() }, 30000)
onUnmounted(() => window.clearInterval(autoSave))
</script>

<template>
  <!-- 未进入考试：考试列表。学生只有答题一件事，因此不套侧栏布局，与原型第 4 张图一致 -->
  <div v-if="!submission" class="layout" style="grid-template-columns: 1fr">
    <div class="main">
      <header class="topbar">
        <p class="crumbs"><b>学生考试中心</b></p>
        <div class="account">
          <span class="avatar" aria-hidden="true">{{ user?.displayName.slice(0, 1) }}</span>
          <span class="muted">{{ user?.displayName }} · 学生</span>
          <slot name="logout" />
        </div>
      </header>
      <section class="content">
        <!-- 交卷结果提示放在这里而不是弹窗：学生交完卷会回到列表，正好能看到得分说明 -->
        <div class="page-head">
          <h2>{{ tab === 'exams' ? '可参加的考试' : tab === 'results' ? '我的成绩' : '我的错题本' }}</h2>
          <span class="spacer" />
          <!-- 两个页签用按钮实现，选中态复用主按钮样式，避免为学生端再引入一套导航组件 -->
          <button class="btn" :class="{ 'btn-primary': tab === 'exams' }" type="button" @click="tab = 'exams'">考试列表</button>
          <button class="btn" :class="{ 'btn-primary': tab === 'results' }" type="button" @click="tab = 'results'">我的成绩</button>
          <button class="btn" :class="{ 'btn-primary': tab === 'wrong' }" type="button" @click="tab = 'wrong'">错题本</button>
        </div>
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>
        <p v-if="result" class="alert ok" role="status">{{ result }}</p>

        <div v-if="tab === 'exams'" class="card">
          <table class="table">
            <thead>
              <tr>
                <th>考试名称</th>
                <th style="width: 160px">试卷</th>
                <th style="width: 90px" class="num">总分</th>
                <th style="width: 90px" class="num">时长</th>
                <th style="width: 320px">开放时间</th>
                <th style="width: 90px">状态</th>
                <th style="width: 110px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="exam in exams" :key="exam.id">
                <td class="cell-strong">{{ exam.name }}</td>
                <td>{{ exam.paperName }}</td>
                <td class="num">{{ exam.totalScore }}</td>
                <td class="num">{{ exam.durationMinutes }} 分钟</td>
                <td class="muted">{{ when(exam.startAt) }} — {{ when(exam.endAt) }}</td>
                <td :class="canEnter(exam) ? 'state-on' : 'muted'">{{ statusLabel(exam) }}</td>
                <td>
                  <button class="btn btn-primary" type="button" :disabled="!canEnter(exam) || busy" @click="enter(exam)">
                    {{ statusLabel(exam) === '继续作答' ? '继续作答' : '进入考试' }}
                  </button>
                </td>
              </tr>
              <tr v-if="!exams.length"><td colspan="7"><p class="empty">暂无已发布考试</p></td></tr>
            </tbody>
          </table>
        </div>

        <!-- 我的成绩。只有教师公布成绩后才会出现在这里，公布前列表为空 -->
        <div v-else-if="tab === 'results'" class="card">
          <div class="panel-title">
            <h3>已公布成绩</h3>
            <span class="spacer">只显示本人成绩与名次</span>
          </div>
          <table class="table">
            <thead>
              <tr>
                <th>考试名称</th>
                <th style="width: 180px">交卷时间</th>
                <th style="width: 90px" class="num">客观题</th>
                <th style="width: 90px" class="num">主观题</th>
                <th style="width: 110px" class="num">总分</th>
                <th style="width: 110px" class="num">名次</th>
                <th style="width: 110px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in myResults" :key="item.submissionId">
                <td class="cell-strong">{{ item.examName }}</td>
                <td class="muted">{{ formatWhen(item.submittedAt) }}</td>
                <td class="num">{{ item.objectiveScore }}</td>
                <td class="num">{{ item.subjectiveScore }}</td>
                <td class="num cell-strong">{{ item.totalScore }} / {{ item.paperTotalScore }}</td>
                <!-- 只给本人名次和总人数，拿不到其他学生的姓名和分数 -->
                <td class="num">第 {{ item.rank }} 名 / {{ item.totalCount }} 人</td>
                <td class="ops">
                  <button class="btn-link" type="button" @click="openResult(item)">查看答卷</button>
                </td>
              </tr>
              <tr v-if="!myResults.length">
                <td colspan="7"><p class="empty">还没有已公布的成绩</p></td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 错题本与错题重练。只收录已公布成绩的考试，重练不改动任何成绩 -->
        <WrongQuestionBook v-else />
      </section>
    </div>

    <!-- 本人答卷回看。gradable=false：学生永远不能评分，后端也只会返回本人答卷 -->
    <SubmissionGradeModal
      v-if="resultDetail"
      :detail="resultDetail"
      :gradable="false"
      @close="resultDetail = null"
    />
  </div>

  <!-- 答题页 -->
  <div v-else class="exam-page">
    <!-- 顶栏：考试名 + 剩余时间 + 已答进度。最后一分钟倒计时变红，提醒尽快交卷 -->
    <div class="exam-bar">
      <h2>{{ activeExam?.name }}</h2>
      <span class="clock" :class="{ urgent: remainingSeconds <= 60 }">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
             stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <circle cx="12" cy="12" r="9" /><path d="M12 7v5l3.5 2" />
        </svg>
        剩余时间 <b>{{ remainingText }}</b>
      </span>
      <span class="muted">已答 <b class="cell-strong">{{ answeredCount }}/{{ questions.length }}</b></span>
    </div>

    <p v-if="error" class="alert error" role="alert" style="margin: 12px 22px 0">{{ error }}</p>

    <div class="exam-body">
      <div class="card">
        <!-- 大题标题，如「一、单选题（本大题共 2 小题，每小题 2 分，共 4 分）」 -->
        <p v-if="currentGroup" class="section-title">{{ currentGroup.label }}</p>
        <div v-if="current" class="question-area">
          <p class="stem">{{ index + 1 }}. {{ current.stem }}（{{ current.score }} 分）</p>

          <!-- 选择题。用 :checked + @change 而不是 v-model：作答值需要按题型分别处理
               （单选覆盖、多选切换），v-model 的双向绑定在这里反而要写更多转换代码 -->
          <template v-if="isChoice(current.type)">
            <label v-for="option in current.options ?? []" :key="option.key" class="choice"
                   :class="{ picked: isPicked(current, option.key) }">
              <input
                :type="current.type === 'MULTIPLE_CHOICE' ? 'checkbox' : 'radio'"
                :name="`q-${current.id}`"
                :checked="isPicked(current, option.key)"
                @change="pick(current, option.key)"
              />
              <span>{{ option.key }}. {{ option.content }}</span>
            </label>
          </template>

          <!-- 判断题。两个选项固定为正确/错误，不来自题目数据 -->
          <template v-else-if="current.type === 'TRUE_FALSE'">
            <label class="choice" :class="{ picked: answers[current.id] === true }">
              <input type="radio" :name="`q-${current.id}`" :checked="answers[current.id] === true"
                     @change="setBoolean(current, true)" />
              <span>正确</span>
            </label>
            <label class="choice" :class="{ picked: answers[current.id] === false }">
              <input type="radio" :name="`q-${current.id}`" :checked="answers[current.id] === false"
                     @change="setBoolean(current, false)" />
              <span>错误</span>
            </label>
          </template>

          <!-- 简答题与编程题。编程题给不同的占位文案，提示需要写代码和思路 -->
          <textarea
            v-else
            :value="typeof answers[current.id] === 'string' ? (answers[current.id] as string) : ''"
            :placeholder="current.type === 'PROGRAMMING' ? '请写出代码与思路' : '请输入答案'"
            style="min-height: 180px"
            @input="answers[current.id] = ($event.target as HTMLTextAreaElement).value; persistDraft()"
          />
        </div>
        <!-- 上一题/下一题。切题时会把答案同步到服务端 -->
        <div class="question-nav-foot">
          <button class="btn" type="button" :disabled="index === 0" @click="goTo(index - 1)">上一题</button>
          <button class="btn btn-primary" type="button" :disabled="index >= questions.length - 1"
                  @click="goTo(index + 1)">下一题</button>
        </div>
      </div>

      <!-- 右侧：题号导航 + 交卷按钮 -->
      <aside class="exam-side">
        <div class="card">
          <div class="panel-title"><h3>题号导航</h3></div>
          <!-- 图例。三种状态用颜色区分，与下面的题号方块一致 -->
          <div class="nav-legend">
            <span><i class="swatch" />未答</span>
            <span><i class="swatch done" />已答</span>
            <span><i class="swatch current" />当前</span>
          </div>
          <!-- 题号方块。aria-label 带上是否已作答，让读屏器用户也能掌握答题进度 -->
          <div class="nav-grid">
            <button
              v-for="(question, position) in questions"
              :key="question.id"
              type="button"
              :class="{ done: isAnswered(answers[question.id]), current: position === index }"
              :aria-label="`第 ${position + 1} 题${isAnswered(answers[question.id]) ? '，已作答' : '，未作答'}`"
              @click="goTo(position)"
            >{{ position + 1 }}</button>
          </div>
        </div>
        <!-- 交卷按钮。红色 + 大尺寸，符合原型；点击后会提示未答题数并二次确认 -->
        <button class="btn btn-danger btn-lg" type="button" :disabled="submitting" @click="submitExam(false)">
          {{ submitting ? '提交中…' : '交卷' }}
        </button>
      </aside>
    </div>
  </div>
</template>
