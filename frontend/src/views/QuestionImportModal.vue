<script setup lang="ts">
/**
 * 题库批量导入弹窗：选文件或粘贴表格 → 预览 → 确认导入。
 *
 * 界面刻意做成两步而不是「选完文件直接进库」，和 AI 出题弹窗是同一条判断标准：
 *
 * 1. **先预览，再导入。** 预览调的是同一个接口（`dryRun: true`），服务端不写任何数据，
 *    结果表逐行告诉教师「这行会进库 / 会跳过 / 为什么失败」。批量操作最怕的是
 *    「点一下，四十道题进了库，其中三道答案是错的」——那时已经没法一键撤销了。
 * 2. **确认按钮只有预览通过后才可点。** 没预览过就不给导入，避免误操作。
 *
 * 导入的题目走后端与手工出题完全相同的校验，前端这里不做任何题型规则判断——
 * 前端校验只改善体验，不构成保证。
 */
import { computed, ref } from 'vue'
import { api, typeLabels, type ImportResult } from '../api'
import { token } from '../session'

/** 导入成功后通知父组件刷新列表；`count` 用于提示语里的道数。 */
const emit = defineEmits<{ close: []; imported: [count: number] }>()

/** 表格正文。文件读进来和手工粘贴共用这一个值，因此两条路径的行为完全一致。 */
const content = ref('')
/** 选中的文件名，仅用于界面回显。 */
const fileName = ref('')
/** 上一次的预览结果；为 null 表示还没预览过，此时不允许导入。 */
const preview = ref<ImportResult | null>(null)
/** 真正导入后的结果，用于替换结果表并显示成功提示。 */
const done = ref<ImportResult | null>(null)
const error = ref('')
const busy = ref(false)

/** 当前展示的结果：导入结果优先于预览结果。 */
const result = computed(() => done.value ?? preview.value)
/** 有内容才能预览。 */
const ready = computed(() => content.value.trim().length > 0)
/** 预览过、有可导入的行、且还没导入过，才允许点「确认导入」。 */
const canImport = computed(() => Boolean(preview.value && preview.value.imported > 0 && !done.value))

/**
 * 读取本地文件。
 *
 * 用 `File.text()` 按 UTF-8 解码，因此 Excel 另存的「CSV UTF-8」可以直接用；
 * 而 Windows 下的「CSV 逗号分隔」是 GBK，会读成乱码——这一点在下面的说明里明确写出来，
 * 与其让教师猜为什么表头匹配不上，不如提前告诉他另存为哪一种。
 */
async function pickFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  error.value = ''
  reset()
  fileName.value = file.name
  try {
    content.value = await file.text()
  } catch {
    error.value = '读取文件失败，请改用直接粘贴表格内容。'
  }
}

/** 换了内容就作废上一次的预览结果，避免「预览的是旧内容、导入的是新内容」。 */
function reset() {
  preview.value = null
  done.value = null
}

/** 预览：服务端解析并校验，但不写库。 */
async function runPreview() {
  await run(true)
}

/** 确认导入：逐行写库，单行失败不影响其他行。 */
async function runImport() {
  await run(false)
}

/** 预览与导入只差一个参数，因此共用一段调用逻辑。 */
async function run(dryRun: boolean) {
  error.value = ''
  busy.value = true
  try {
    const outcome = await api.importQuestions(token.value, { content: content.value, dryRun })
    if (dryRun) {
      preview.value = outcome
      done.value = null
    } else {
      done.value = outcome
      emit('imported', outcome.imported)
    }
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}

/**
 * 下载模板。
 *
 * 模板由后端生成而不是前端写死：表头必须是解析器认得的列名，两处各写一份迟早会漂移，
 * 到时候教师下载下来照着填反而报错。下载需要带令牌，因此不能用普通的 `<a href>`，
 * 只能取回文本再造一个临时的 blob 链接。
 */
async function downloadTemplate() {
  error.value = ''
  try {
    const csv = await api.importTemplate(token.value)
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = '题库导入模板.csv'
    link.click()
    URL.revokeObjectURL(url)
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/** 结果行的中文标签与配色。跳过单独一档：它既不是成功也不是失败。 */
const outcomeLabels: Record<string, string> = { IMPORTED: '可导入', SKIPPED: '跳过', FAILED: '失败' }
function outcomeLabel(outcome: string) {
  if (outcome === 'IMPORTED') return done.value ? '已导入' : '可导入'
  return outcomeLabels[outcome] ?? outcome
}
function outcomeClass(outcome: string) {
  if (outcome === 'IMPORTED') return 'state-on'
  return outcome === 'FAILED' ? 'state-off' : 'muted'
}
</script>

<template>
  <div class="modal-mask" role="dialog" aria-modal="true" aria-label="批量导入题库" @click.self="emit('close')">
    <div class="modal modal-wide">
      <header>
        <span>批量导入题库</span>
        <button class="btn-link" type="button" style="margin-left: auto" @click="emit('close')">关闭</button>
      </header>

      <div class="body">
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>
        <p v-if="done" class="alert ok" role="status">
          导入完成：成功 {{ done.imported }} 道，跳过 {{ done.skipped }} 道，失败 {{ done.failed }} 道。
        </p>

        <!-- 第一步：给内容。选文件和粘贴是同一个入口，后端也是同一个接口。
             文件输入框要显式 width:auto——全局样式让 input 占满整行，否则「下载模板」会被挤到下一行 -->
        <div style="display: flex; gap: 10px; align-items: center">
          <input
            type="file"
            accept=".csv,.tsv,.txt"
            aria-label="选择表格文件"
            style="width: auto; border: 0; padding: 0"
            @change="pickFile"
          />
          <span v-if="fileName" class="muted">{{ fileName }}</span>
          <button class="btn" type="button" style="margin-left: auto" @click="downloadTemplate">下载模板</button>
        </div>

        <label class="field">
          <span>表格内容（可直接粘贴，支持逗号或制表符分隔）</span>
          <textarea
            v-model="content"
            rows="8"
            spellcheck="false"
            style="font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px"
            placeholder="题型,题干,难度,知识点,分值,选项,标准答案,解析,标签"
            @input="reset"
          />
        </label>

        <!-- 格式说明写在界面上而不是只放在文档里：教师是在这个弹窗里遇到问题的 -->
        <details class="card card-pad">
          <summary class="muted">填写说明（必填列：题型、题干、知识点、标准答案）</summary>
          <ul class="option-list" style="margin-top: 10px; padding-left: 20px">
            <li><b>题型</b>：单选题 / 多选题 / 判断题 / 简答题 / 编程题</li>
            <li><b>难度</b>：易 / 中 / 难，留空按「中」处理</li>
            <li><b>知识点</b>：按名称匹配，<b>必须已存在</b>；缺哪个会在结果里点名，可在题库页先添加</li>
            <li><b>分值</b>：留空按 10 分，最多一位小数</li>
            <li><b>选项</b>：选择题必填，用「|」分隔，如 <code>A. 甲|B. 乙|C. 丙|D. 丁</code>；
              不写 A. B. 这些序号时按顺序自动补</li>
            <li><b>标准答案</b>：选择题写选项号（<code>A</code> 或 <code>AB</code>）；判断题写 正确 / 错误；
              简答题和编程题写参考答案</li>
            <li>题干里带逗号时整格用英文双引号包起来；Excel 请另存为「CSV UTF-8」，
              否则中文会读成乱码</li>
            <li>单次最多 200 行；题干与题库中已有题目重复的行会被跳过，不会重复入库</li>
          </ul>
        </details>

        <div style="display: flex; gap: 10px; align-items: center">
          <button class="btn" type="button" :disabled="busy || !ready" @click="runPreview">
            {{ busy && !done ? '处理中…' : '预览' }}
          </button>
          <button class="btn btn-primary" type="button" :disabled="busy || !canImport" @click="runImport">
            确认导入{{ canImport ? ` ${preview!.imported} 道` : '' }}
          </button>
          <span class="muted">预览不会写入任何数据；确认导入后逐行写库，某一行失败不影响其他行。</span>
        </div>

        <!-- 第二步：逐行结果。预览和导入用同一张表，只有「可导入 / 已导入」这一个字的差别 -->
        <template v-if="result">
          <div class="stat-row">
            <div class="stat"><span>数据行</span><b>{{ result.total }}</b></div>
            <div class="stat">
              <span>{{ result.dryRun ? '可导入' : '已导入' }}</span>
              <b class="state-on">{{ result.imported }}</b>
            </div>
            <div class="stat"><span>跳过</span><b class="muted">{{ result.skipped }}</b></div>
            <div class="stat">
              <span>失败</span>
              <b :class="result.failed ? 'state-off' : 'muted'">{{ result.failed }}</b>
            </div>
          </div>

          <table class="table">
            <thead>
              <tr>
                <th style="width: 56px">行号</th>
                <th style="width: 76px">结果</th>
                <th style="width: 76px">题型</th>
                <th>题干</th>
                <th style="width: 40%">说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in result.rows" :key="row.line">
                <td class="muted">{{ row.line }}</td>
                <td :class="outcomeClass(row.outcome)">{{ outcomeLabel(row.outcome) }}</td>
                <td>{{ row.type ? typeLabels[row.type] : '—' }}</td>
                <td class="cell-strong">{{ row.stem || '—' }}</td>
                <td :class="row.outcome === 'FAILED' ? 'state-off' : 'muted'">{{ row.message ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </div>

      <footer>
        <button class="btn" type="button" @click="emit('close')">{{ done ? '完成' : '关闭' }}</button>
      </footer>
    </div>
  </div>
</template>
