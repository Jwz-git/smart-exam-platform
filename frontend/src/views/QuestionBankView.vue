<script setup lang="ts">
/**
 * 题库管理页，对应原型第 2 张图：筛选卡 + 表格 + 分页 + 新增/编辑弹窗。
 *
 * 几个与后端约定相关的点：
 * - 列表返回全部状态的题目，因此表格有「状态」列和启用/停用操作；
 * - 「关键词」同时匹配题干和标签，由后端一条 SQL 完成，前端不做二次过滤；
 * - 删除走的是后端的降级策略：未被试卷引用才真删，否则自动改为停用。
 */
import { onMounted, reactive, ref, watch } from 'vue'
import PagerBar from '../components/PagerBar.vue'
import AiDraftModal from './AiDraftModal.vue'
import QuestionFormModal from './QuestionFormModal.vue'
import QuestionImportModal from './QuestionImportModal.vue'
import {
  api, difficultyLabels, typeLabels,
  type KnowledgePoint, type Question, type QuestionPayload,
} from '../api'
import { token } from '../session'

/** 筛选条件。空字符串表示「全部」，拼查询串时会被跳过。 */
const filters = reactive({ type: '', difficulty: '', knowledgePointId: '', keyword: '', status: '' })
const page = ref(1)
const size = ref(10)
const total = ref(0)
const questions = ref<Question[]>([])
const points = ref<KnowledgePoint[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
/** 弹窗是否打开。 */
const editorOpen = ref(false)
/** 正在编辑的题目；为 null 表示新增。 */
const editing = ref<Question | null>(null)
/** AI 出题弹窗是否打开。 */
const aiOpen = ref(false)
/** 批量导入弹窗是否打开。 */
const importOpen = ref(false)
/** 从 AI 草稿预填到表单的内容；为 null 表示这次不是从草稿来的。 */
const prefill = ref<QuestionPayload | null>(null)
/** 弹窗组件引用，用于把保存失败的后端错误回填到弹窗里显示。 */
const modal = ref<InstanceType<typeof QuestionFormModal> | null>(null)
/** 快速新建知识点的输入框。题库页需要它，否则没有知识点时无法新增题目。 */
const pointName = ref('')

/** 拉取知识点，供筛选下拉框和题目表单使用。 */
async function loadPoints() {
  points.value = await api.points(token.value)
}

/**
 * 拉取当前筛选条件下的题目列表。
 *
 * 末尾的越界处理针对一个具体场景：某页只剩一条题目，删除后该页变空，
 * 如果不回退一页，用户会看到一张空表却显示「共 N 条」。
 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api.questions(token.value, { ...filters, page: page.value, size: size.value })
    questions.value = result.items
    total.value = result.total
    // 删除最后一条后当前页可能已越界，回退一页重新取数。
    if (!result.items.length && page.value > 1) {
      page.value -= 1
      await load()
    }
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    loading.value = false
  }
}

/** 点击查询。必须把页码复位到 1，否则在第 5 页改筛选条件会查到一个不存在的页。 */
function search() {
  page.value = 1
  void load()
}

/** 清空全部筛选条件并重新查询。 */
function resetFilters() {
  Object.assign(filters, { type: '', difficulty: '', knowledgePointId: '', keyword: '', status: '' })
  search()
}

// 翻页和改每页条数都要重新取数；筛选条件的变化则要等用户点「查询」，避免每敲一个字就发请求。
watch([page, size], () => void load())
onMounted(async () => {
  try {
    await loadPoints()
  } catch (reason) {
    error.value = (reason as Error).message
  }
  await load()
})

/** 打开新增弹窗。没有知识点时直接提示，因为题目必须挂在某个知识点下。 */
function openCreate() {
  if (!points.value.length) { error.value = '请先添加一个知识点，再新增题目。'; return }
  editing.value = null
  prefill.value = null
  editorOpen.value = true
}

/** 打开 AI 出题弹窗。同样需要先有知识点：出题要指定知识点，模型也需要它作为主题。 */
function openAi() {
  if (!points.value.length) { error.value = '请先添加一个知识点，再使用 AI 出题。'; return }
  error.value = ''
  notice.value = ''
  aiOpen.value = true
}

/**
 * 打开批量导入弹窗。
 *
 * 同样要求先有知识点：导入按名称匹配知识点且不会自动创建，题库里一个知识点都没有时
 * 每一行都会失败，不如在这里直接说清楚。
 */
function openImport() {
  if (!points.value.length) { error.value = '请先添加一个知识点，再导入题库。'; return }
  error.value = ''
  notice.value = ''
  importOpen.value = true
}

/**
 * 导入完成后刷新列表。
 *
 * 弹窗刻意不关：结果表里还有「哪几行跳过、哪几行失败」需要教师看完，
 * 关掉就等于把这些信息丢了。
 */
async function onImported(count: number) {
  notice.value = count > 0 ? `已导入 ${count} 道题目到题库。` : '本次没有新增题目，请看结果表里的原因。'
  await load()
}

/**
 * 采用一份 AI 草稿：关掉 AI 弹窗，把草稿填进普通的题目表单。
 *
 * `editing` 保持为 null，因此后续保存走的是新增接口而不是编辑接口——
 * 草稿在服务端并不存在，PUT 一个不存在的 ID 只会得到 404。
 */
function adoptDraft(draft: QuestionPayload) {
  aiOpen.value = false
  editing.value = null
  prefill.value = draft
  editorOpen.value = true
}

/**
 * 打开编辑弹窗。
 *
 * 这里重新按 id 查一次详情而不是直接用列表里的对象：列表可能是分页缓存的旧数据，
 * 而编辑表单需要与服务端当前状态一致的完整选项和标准答案。
 */
async function openEdit(question: Question) {
  error.value = ''
  try {
    // 列表行不含完整选项顺序以外的字段，编辑前按 id 重新取一次详情。
    editing.value = await api.question(token.value, question.id)
    prefill.value = null
    editorOpen.value = true
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/**
 * 保存题目。新增和编辑共用一套表单，靠 `editing` 是否为空区分。
 *
 * 失败时不关弹窗，而是把后端的错误信息回填进去：用户填了一屏内容，
 * 关掉弹窗等于让他重填一遍。
 */
async function save(payload: QuestionPayload) {
  notice.value = ''
  try {
    if (editing.value) await api.updateQuestion(token.value, editing.value.id, payload)
    else await api.createQuestion(token.value, payload)
    editorOpen.value = false
    notice.value = editing.value ? '题目已更新。' : prefill.value ? 'AI 草稿已确认并保存到题库。' : '题目已保存。'
    prefill.value = null
    await load()
  } catch (reason) {
    modal.value?.setError((reason as Error).message)
  }
}

/**
 * 启用或停用题目。
 *
 * 停用是影响面较大的操作（题目将无法加入新试卷），因此加二次确认；
 * 启用是恢复性操作，不打扰用户。
 */
async function toggleStatus(question: Question) {
  const next = question.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (next === 'DISABLED' && !window.confirm(`停用后该题不能再加入新试卷，确认停用「${question.stem.slice(0, 20)}」吗？`)) return
  error.value = ''
  notice.value = ''
  try {
    await api.setQuestionStatus(token.value, question.id, next)
    notice.value = next === 'ACTIVE' ? '题目已启用。' : '题目已停用，历史试卷不受影响。'
    await load()
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/** 快速新增知识点。名称全局唯一，重复时后端返回 409，这里把提示原样显示出来。 */
async function addPoint() {
  const name = pointName.value.trim()
  if (!name) return
  error.value = ''
  try {
    await api.createPoint(token.value, name)
    pointName.value = ''
    await loadPoints()
    notice.value = '知识点已添加。'
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/** 把后端返回的 `a, b` 形式标签拆成数组，渲染成一个个小标签块。中英文逗号都兼容。 */
function tagsOf(question: Question) {
  return (question.tags ?? '').split(/[,，]/).map((tag) => tag.trim()).filter(Boolean)
}
</script>

<template>
  <section class="content">
    <!-- 页头：标题 + 右上「新增题目」主按钮，与原型一致 -->
    <div class="page-head">
      <h2>题库管理</h2>
      <span class="spacer" />
      <!-- 批量导入与 AI 出题都放在新增左边：它们是可选的辅助入口，主按钮仍然是手工新增 -->
      <button class="btn" type="button" @click="openImport">导入题库</button>
      <button class="btn" type="button" @click="openAi">AI 出题</button>
      <button class="btn btn-primary" type="button" @click="openCreate">新增题目</button>
    </div>

    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert ok" role="status">{{ notice }}</p>

    <!-- 筛选卡。比原型多了「状态」筛选和「添加知识点」：
         列表现在会返回停用题目，需要能按状态筛；而知识点是新增题目的前提，
         本期没有独立的知识点管理页，所以在这里提供一个最简入口 -->
    <div class="card card-pad filter-bar">
      <label>题型
        <select v-model="filters.type">
          <option value="">全部</option>
          <option v-for="(label, key) in typeLabels" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <label>难度
        <select v-model="filters.difficulty">
          <option value="">全部</option>
          <option v-for="(label, key) in difficultyLabels" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <label>知识点
        <select v-model="filters.knowledgePointId">
          <option value="">请选择</option>
          <option v-for="point in points" :key="point.id" :value="String(point.id)">{{ point.name }}</option>
        </select>
      </label>
      <label>状态
        <select v-model="filters.status">
          <option value="">全部</option>
          <option value="ACTIVE">启用</option>
          <option value="DISABLED">停用</option>
        </select>
      </label>
      <label>关键词
        <input v-model="filters.keyword" placeholder="请输入关键词" @keyup.enter="search" />
      </label>
      <button class="btn btn-primary" type="button" @click="search">查询</button>
      <button class="btn" type="button" @click="resetFilters">重置</button>
      <span class="spacer" style="margin-left: auto; display: flex; gap: 8px">
        <input v-model="pointName" placeholder="新知识点名称" style="min-width: 150px" @keyup.enter="addPoint" />
        <button class="btn" type="button" @click="addPoint">添加知识点</button>
      </span>
    </div>

    <!-- 题目表格。列顺序与原型一致：题目ID / 题目内容 / 题型 / 难度 / 知识点 / 关键词 / 状态 / 操作 -->
    <div class="card">
      <table class="table">
        <thead>
          <tr>
            <th style="width: 84px">题目ID</th>
            <th>题目内容</th>
            <th style="width: 82px">题型</th>
            <th style="width: 58px">难度</th>
            <th style="width: 110px">知识点</th>
            <th style="width: 150px">关键词</th>
            <th style="width: 64px">状态</th>
            <th style="width: 116px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="question in questions" :key="question.id">
            <!-- 展示用编号加了偏移，只为对齐原型里 100001 起的样式；真正的操作一律用 question.id -->
            <td class="muted">{{ 100000 + question.id }}</td>
            <td class="cell-strong">{{ question.stem }}</td>
            <td>{{ typeLabels[question.type] }}</td>
            <td>{{ difficultyLabels[question.difficulty] }}</td>
            <td>{{ question.knowledgePointName }}</td>
            <!-- 关键词渲染成一组小标签；没有标签时显示破折号，保持列宽稳定 -->
            <td>
              <span v-if="tagsOf(question).length" class="tag-list">
                <span v-for="tag in tagsOf(question)" :key="tag" class="tag">{{ tag }}</span>
              </span>
              <span v-else class="muted">—</span>
            </td>
            <!-- 状态用颜色区分：启用绿、停用红，与原型一致 -->
            <td :class="question.status === 'ACTIVE' ? 'state-on' : 'state-off'">
              {{ question.status === 'ACTIVE' ? '启用' : '停用' }}
            </td>
            <!-- 操作列与原型一致：编辑 | 停用（已停用的显示为启用） -->
            <td class="ops">
              <button class="btn-link" type="button" @click="openEdit(question)">编辑</button>
              <span class="sep">|</span>
              <button class="btn-link" type="button" @click="toggleStatus(question)">
                {{ question.status === 'ACTIVE' ? '停用' : '启用' }}
              </button>
            </td>
          </tr>
          <!-- 空数据与加载中分开处理，避免刚进页面就闪一下「暂无题目」 -->
          <tr v-if="!questions.length && !loading">
            <td colspan="8"><p class="empty">暂无符合条件的题目</p></td>
          </tr>
          <tr v-if="loading">
            <td colspan="8"><p class="empty">加载中…</p></td>
          </tr>
        </tbody>
      </table>
      <PagerBar v-model:page="page" v-model:size="size" :total="total" />
    </div>

    <!-- 新增/编辑弹窗。用 v-if 而不是 v-show，确保每次打开都重新初始化表单 -->
    <QuestionFormModal
      v-if="editorOpen"
      ref="modal"
      :question="editing"
      :points="points"
      :prefill="prefill"
      @close="editorOpen = false"
      @save="save"
    />

    <!-- AI 出题弹窗。草稿在这里预览，采用后进入上面的表单再确认保存 -->
    <AiDraftModal v-if="aiOpen" :points="points" @close="aiOpen = false" @adopt="adoptDraft" />

    <!-- 批量导入弹窗。先预览再确认，导入后由 onImported 刷新列表 -->
    <QuestionImportModal v-if="importOpen" @close="importOpen = false" @imported="onImported" />
  </section>
</template>
