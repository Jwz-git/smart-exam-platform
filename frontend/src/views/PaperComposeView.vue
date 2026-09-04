<script setup lang="ts">
/**
 * 手动组卷页，对应原型第 3 张图：题库候选 ⇄ 试卷内容的穿梭式布局。
 *
 * 两个刻意的设计决定：
 *
 * 1. 总分是算出来的，不是填出来的。原型和设计文档都要求「总分由题目实际分值实时汇总，
 *    不允许用户单独填写一个不一致的总分」，而后端也要求分值合计严格等于总分。
 *    早期版本让用户填总分再按题数平均分配，结果 40 分 3 道题会得到 13.333…，
 *    既超过一位小数又凑不回 40，接口必然报错。现在改成逐题填分、总分只读显示。
 *
 * 2. 候选列表只取启用题目（status: 'ACTIVE'）。停用题目本来就不允许加入新试卷，
 *    与其让用户选完再被后端拒绝，不如一开始就不展示。
 *
 * 3. 自动组卷只把抽中的题目填进右侧的「试卷内容」，保存仍走同一个「保存草稿」按钮。
 *    自动组卷因此不是第二条写入路径，分值校验、题目归属和启用状态的校验只有一处实现。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import AutoComposeModal from './AutoComposeModal.vue'
import PaperExportModal from './PaperExportModal.vue'
import {
  api, difficultyLabels, typeLabels,
  type KnowledgePoint, type Paper, type Question,
} from '../api'
import { token } from '../session'

/** 试卷内容里的一行：题目 + 本卷实际分值，分值独立于题库建议分值。 */
interface PickedQuestion { question: Question; score: number }

/** 试卷基本信息。总分不在这里，因为它由各题分值汇总得出。 */
const meta = reactive({ name: '', durationMinutes: 90 })
/** 候选区的筛选条件。 */
const filters = reactive({ type: '', difficulty: '', keyword: '' })
/** 候选题目（题库里的启用题目）。 */
const candidates = ref<Question[]>([])
/** 候选区勾选的题目 ID。 */
const checkedLeft = ref<number[]>([])
/** 试卷区勾选的题目 ID。 */
const checkedRight = ref<number[]>([])
/** 已加入试卷的题目，数组顺序即试卷题号顺序。 */
const picked = ref<PickedQuestion[]>([])
const points = ref<KnowledgePoint[]>([])
const papers = ref<Paper[]>([])
const error = ref('')
const notice = ref('')
const busy = ref(false)
const previewOpen = ref(false)
/** 自动组卷弹窗是否打开。 */
const autoOpen = ref(false)
/** 正在导出的试卷；为 null 表示导出弹窗关闭。 */
const exporting = ref<Paper | null>(null)

/** 总分始终等于各题分值之和，不允许单独填写一个不一致的总分。 */
const totalScore = computed(() => picked.value.reduce((sum, item) => sum + (Number(item.score) || 0), 0))
/**
 * 总分保留一位小数后的值。
 *
 * 必须四舍五入：`0.1 + 0.2` 在浮点运算下是 0.30000000000000004，
 * 直接提交会被后端的一位小数校验拒掉。
 */
const roundedTotal = computed(() => Math.round(totalScore.value * 10) / 10)
/** 已入卷题目的 ID 集合，用 Set 是为了让下面的过滤是 O(1) 查找。 */
const pickedIds = computed(() => new Set(picked.value.map((item) => item.question.id)))

/** 候选区排除已入卷的题目，避免同一道题被重复加入（后端也会拒绝重复）。 */
const availableCandidates = computed(() => candidates.value.filter((item) => !pickedIds.value.has(item.id)))

/** 分值必须是一位小数且大于 0，和后端 DECIMAL(6,1) 校验保持一致。 */
const scoreProblem = computed(() => {
  const bad = picked.value.find((item) => {
    const score = Number(item.score)
    return !(score > 0) || Math.round(score * 10) !== score * 10
  })
  return bad ? `「${bad.question.stem.slice(0, 16)}」的分值必须大于 0 且最多一位小数` : ''
})

/** 拉取候选题目。一次取 100 条，组卷场景下够用，也避免在穿梭框里再做一层分页。 */
async function loadCandidates() {
  error.value = ''
  try {
    const result = await api.questions(token.value, { ...filters, status: 'ACTIVE', page: 1, size: 100 })
    candidates.value = result.items
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/** 拉取本人试卷列表，用于页面下方的「我的试卷」和发布操作。 */
async function loadPapers() {
  papers.value = await api.papers(token.value)
}

onMounted(async () => {
  try {
    points.value = await api.points(token.value)
    await loadPapers()
  } catch (reason) {
    error.value = (reason as Error).message
  }
  await loadCandidates()
})

// 题目被移出试卷后，清掉对应的勾选状态，否则「移出试卷」按钮会一直保持可点。
watch(picked, () => { checkedRight.value = checkedRight.value.filter((id) => pickedIds.value.has(id)) }, { deep: true })

/** side 用来区分左右两个勾选集合，避免在模板里传递已解包的 ref。 */
function toggle(side: 'left' | 'right', id: number) {
  const list = side === 'left' ? checkedLeft : checkedRight
  list.value = list.value.includes(id) ? list.value.filter((value) => value !== id) : [...list.value, id]
}

/** 把候选区勾选的题目加入试卷，分值默认取题库里的建议分值，之后可以逐题改。 */
function moveRight() {
  const adding = availableCandidates.value.filter((item) => checkedLeft.value.includes(item.id))
  picked.value = [...picked.value, ...adding.map((question) => ({ question, score: question.suggestedScore }))]
  checkedLeft.value = []
}

/** 把试卷区勾选的题目移回候选区。 */
function moveLeft() {
  picked.value = picked.value.filter((item) => !checkedRight.value.includes(item.question.id))
  checkedRight.value = []
}

/** 删除试卷中的某一题。 */
function removeAt(index: number) {
  picked.value = picked.value.filter((_, position) => position !== index)
}

/** 上移或下移一题。数组顺序就是题号顺序，因此调换位置即可。 */
function move(index: number, offset: number) {
  const target = index + offset
  if (target < 0 || target >= picked.value.length) return
  const next = [...picked.value]
  const [item] = next.splice(index, 1)
  if (!item) return
  next.splice(target, 0, item)
  picked.value = next
}

/**
 * 把自动组卷的方案填进试卷内容。
 *
 * 已在试卷里的题目跳过而不是重复加入：试卷题目在数据库上有唯一约束，重复的话保存必然失败。
 * 跳过了几道要如实说出来，否则教师会以为「应用 10 道」结果只多了 8 道是个 bug。
 */
function applyPlan(items: { question: Question; score: number }[]) {
  const existing = pickedIds.value
  const adding = items.filter((item) => !existing.has(item.question.id))
  picked.value = [...picked.value, ...adding.map((item) => ({ question: item.question, score: item.score }))]
  const skipped = items.length - adding.length
  notice.value = `自动组卷已加入 ${adding.length} 道题`
    + (skipped ? `，跳过 ${skipped} 道已在试卷中的题目` : '')
    + `，确认后请点「保存草稿」。`
  error.value = ''
  autoOpen.value = false
}

/**
 * 保存试卷草稿。
 *
 * 保存成功后清空当前编辑内容：一次组卷是一个完整动作，留着上一份的题目容易误操作。
 */
async function saveDraft() {
  error.value = ''
  notice.value = ''
  if (!meta.name.trim()) { error.value = '请填写试卷名称'; return }
  if (!picked.value.length) { error.value = '请至少选择一道题'; return }
  if (scoreProblem.value) { error.value = scoreProblem.value; return }
  busy.value = true
  try {
    const paper = await api.createPaper(token.value, {
      name: meta.name.trim(),
      durationMinutes: meta.durationMinutes,
      totalScore: roundedTotal.value,
      questions: picked.value.map((item) => ({ questionId: item.question.id, score: Number(item.score) })),
    })
    notice.value = `试卷草稿「${paper.name}」已保存，共 ${paper.questions.length} 题、${paper.totalScore} 分。`
    picked.value = []
    meta.name = ''
    await loadPapers()
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}

/** 发布试卷。发布后不可修改，因此加二次确认。 */
async function publish(paper: Paper) {
  if (!window.confirm(`发布后试卷不能再修改，确认发布「${paper.name}」吗？`)) return
  error.value = ''
  notice.value = ''
  try {
    await api.publishPaper(token.value, paper.id)
    notice.value = `试卷「${paper.name}」已发布，可用于创建考试。`
    await loadPapers()
  } catch (reason) {
    error.value = (reason as Error).message
  }
}
</script>

<template>
  <section class="content">
    <!-- 页头：标题 + 保存草稿 + 预览，与原型一致 -->
    <div class="page-head">
      <h2>手动组卷</h2>
      <span class="spacer" />
      <button class="btn" type="button" @click="autoOpen = true">自动组卷</button>
      <button class="btn" type="button" :disabled="busy" @click="saveDraft">保存草稿</button>
      <button class="btn btn-primary" type="button" :disabled="!picked.length" @click="previewOpen = true">预览</button>
    </div>

    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert ok" role="status">{{ notice }}</p>

    <div class="card card-pad filter-bar">
      <label>试卷名称 <input v-model="meta.name" style="min-width: 220px" placeholder="期中 Java 基础测试卷" /></label>
      <label>考试时长 <input v-model.number="meta.durationMinutes" type="number" min="1" max="600" style="width: 84px" /> 分钟</label>
      <!-- 总分用 output 而不是 input：它是计算结果而非输入项，
           灰底 + 不可编辑能直观表达「这里改不了」，也从根上避免填出不一致的总分 -->
      <label>总分
        <output class="control" style="width: 84px; display: grid; align-items: center; background: var(--head)">
          {{ roundedTotal }}
        </output> 分
      </label>
      <span class="muted">总分由下方各题分值自动汇总，不可单独填写。</span>
    </div>

    <!-- 穿梭式三栏：左候选、中间移动按钮、右试卷内容 -->
    <div class="compose">
      <div class="card">
        <div class="panel-title">
          <h3>题库候选</h3>
          <span class="spacer">可选 {{ availableCandidates.length }} 题</span>
        </div>
        <div class="card-pad filter-bar" style="border-bottom: 1px solid var(--line)">
          <label>题型
            <select v-model="filters.type" @change="loadCandidates">
              <option value="">全部</option>
              <option v-for="(label, key) in typeLabels" :key="key" :value="key">{{ label }}</option>
            </select>
          </label>
          <label>难度
            <select v-model="filters.difficulty" @change="loadCandidates">
              <option value="">全部</option>
              <option v-for="(label, key) in difficultyLabels" :key="key" :value="key">{{ label }}</option>
            </select>
          </label>
          <input v-model="filters.keyword" placeholder="请输入关键词" style="min-width: 130px" @keyup.enter="loadCandidates" />
          <button class="btn btn-primary" type="button" @click="loadCandidates">查询</button>
        </div>
        <div class="scroll-y">
          <table class="table">
            <thead>
              <tr>
                <th style="width: 36px"><span class="muted" aria-hidden="true">选</span></th>
                <th>题目内容</th>
                <th style="width: 76px">题型</th>
                <th style="width: 52px">难度</th>
                <th style="width: 54px" class="num">分值</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="question in availableCandidates" :key="question.id">
                <td>
                  <input type="checkbox"
                         :aria-label="`选择 ${question.stem}`"
                         :checked="checkedLeft.includes(question.id)"
                         @change="toggle('left', question.id)" />
                </td>
                <td class="cell-strong">{{ question.stem }}</td>
                <td>{{ typeLabels[question.type] }}</td>
                <td>{{ difficultyLabels[question.difficulty] }}</td>
                <td class="num">{{ question.suggestedScore }}</td>
              </tr>
              <tr v-if="!availableCandidates.length">
                <td colspan="5"><p class="empty">没有可加入的启用题目</p></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

        <!-- 移动按钮。两个方向分别只在对应一侧有勾选时可用，避免点了没反应 -->
      <div class="move">
        <button class="btn btn-primary" type="button" title="加入试卷" aria-label="加入试卷"
                :disabled="!checkedLeft.length" @click="moveRight">»</button>
        <button class="btn" type="button" title="移出试卷" aria-label="移出试卷"
                :disabled="!checkedRight.length" @click="moveLeft">«</button>
      </div>

      <div class="card">
        <div class="panel-title">
          <h3>试卷内容</h3>
          <span class="spacer">共 {{ picked.length }} 题 | 总分 {{ roundedTotal }} 分</span>
        </div>
        <div class="scroll-y">
          <table class="table">
            <thead>
              <tr>
                <th style="width: 36px"><span class="muted" aria-hidden="true">选</span></th>
                <th style="width: 46px">题号</th>
                <th>题目内容</th>
                <th style="width: 76px">题型</th>
                <th style="width: 76px" class="num">分值</th>
                <th style="width: 150px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, index) in picked" :key="item.question.id">
                <td>
                  <input type="checkbox"
                         :aria-label="`选择 ${item.question.stem}`"
                         :checked="checkedRight.includes(item.question.id)"
                         @change="toggle('right', item.question.id)" />
                </td>
                <td>{{ index + 1 }}</td>
                <td class="cell-strong">{{ item.question.stem }}</td>
                <td>{{ typeLabels[item.question.type] }}</td>
                <!-- 逐题分值。step=0.1 与「分值保留一位小数」的规则一致；
                     aria-label 带上题号，否则读屏器会读出一串没有区别的「分值」 -->
                <td class="num">
                  <input v-model.number="item.score" class="control score-input" type="number" min="0.1" step="0.1"
                         :aria-label="`第 ${index + 1} 题分值`" />
                </td>
                <td class="ops">
                  <button class="btn-link" type="button" :disabled="index === 0" @click="move(index, -1)">上移</button>
                  <span class="sep">|</span>
                  <button class="btn-link" type="button" :disabled="index === picked.length - 1" @click="move(index, 1)">下移</button>
                  <span class="sep">|</span>
                  <button class="btn-link" type="button" @click="removeAt(index)">删除</button>
                </td>
              </tr>
              <tr v-if="!picked.length">
                <td colspan="6"><p class="empty">勾选左侧题目后点击 » 加入试卷</p></td>
              </tr>
            </tbody>
          </table>
        </div>
        <!-- 底部汇总。分值不合法时直接在这里提示，而不是等提交后才报错 -->
        <div class="compose-foot">
          <span v-if="scoreProblem" class="sum-bad">{{ scoreProblem }}</span>
          <span v-else class="muted">题目分值合计：<b class="sum-ok">{{ roundedTotal }} 分</b></span>
        </div>
      </div>
    </div>

    <!-- 我的试卷。原型没画这块，但草稿需要一个发布入口，就近放在组卷页下方 -->
    <div class="card">
      <div class="panel-title"><h3>我的试卷</h3><span class="spacer">{{ papers.length }} 份</span></div>
      <table class="table">
        <thead>
          <tr>
            <th style="width: 60px">编号</th>
            <th>试卷名称</th>
            <th style="width: 70px" class="num">题数</th>
            <th style="width: 70px" class="num">总分</th>
            <th style="width: 90px" class="num">时长</th>
            <th style="width: 76px">状态</th>
            <th style="width: 130px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="paper in papers" :key="paper.id">
            <td class="muted">{{ paper.id }}</td>
            <td class="cell-strong">{{ paper.name }}</td>
            <td class="num">{{ paper.questions.length }}</td>
            <td class="num">{{ paper.totalScore }}</td>
            <td class="num">{{ paper.durationMinutes }} 分钟</td>
            <td :class="paper.status === 'PUBLISHED' ? 'state-on' : 'muted'">
              {{ paper.status === 'PUBLISHED' ? '已发布' : '草稿' }}
            </td>
            <td class="ops">
              <button v-if="paper.status === 'DRAFT'" class="btn-link" type="button" @click="publish(paper)">发布</button>
              <span v-if="paper.status === 'DRAFT'" class="sep">|</span>
              <button class="btn-link" type="button" @click="exporting = paper">导出</button>
            </td>
          </tr>
          <tr v-if="!papers.length"><td colspan="7"><p class="empty">暂无试卷</p></td></tr>
        </tbody>
      </table>
    </div>

    <!-- 预览弹窗。发布前的完整性自查入口，展示题目、题型、分值和选项 -->
    <div v-if="previewOpen" class="modal-mask" role="dialog" aria-modal="true" aria-label="试卷预览"
         @click.self="previewOpen = false">
      <div class="modal">
        <header>
          <span>试卷预览：{{ meta.name || '未命名试卷' }}</span>
          <button class="btn-link" type="button" style="margin-left: auto" @click="previewOpen = false">关闭</button>
        </header>
        <div class="body">
          <p class="muted">共 {{ picked.length }} 题，总分 {{ roundedTotal }} 分，考试时长 {{ meta.durationMinutes }} 分钟。</p>
          <ol style="display: grid; gap: 12px; padding-left: 20px">
            <li v-for="item in picked" :key="item.question.id">
              <p class="cell-strong">{{ item.question.stem }}（{{ typeLabels[item.question.type] }}，{{ item.score }} 分）</p>
              <p v-for="option in item.question.options" :key="option.key" class="muted">
                {{ option.key }}. {{ option.content }}
              </p>
            </li>
          </ol>
        </div>
        <footer><button class="btn btn-primary" type="button" @click="previewOpen = false">知道了</button></footer>
      </div>
    </div>

    <!-- 自动组卷。只把抽中的题目填进上面的「试卷内容」，保存仍走「保存草稿」 -->
    <AutoComposeModal v-if="autoOpen" :points="points" @close="autoOpen = false" @apply="applyPlan" />

    <!-- 试卷导出。预览与下载用的是同一份内容 -->
    <PaperExportModal v-if="exporting" :paper="exporting" @close="exporting = null" />
  </section>
</template>
