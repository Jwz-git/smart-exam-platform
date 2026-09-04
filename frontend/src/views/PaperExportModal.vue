<script setup lang="ts">
/**
 * 试卷导出弹窗：选格式 → 看预览 → 下载。
 *
 * 中间那一步「看预览」是刻意加的。导出这类操作最容易出的问题是「下载下来才发现不是想要的」——
 * 尤其是「含答案」这个开关：一份本该发给学生的卷子如果带着答案，问题已经发生了。
 * 因此这里直接把要下载的正文显示出来，改任何选项都立刻重新取一次。
 *
 * 两种格式的用途完全不同，界面上也如实写明：
 * - **Markdown**：给人看的卷子，可打印、可直接发给学生（不含答案时）；
 * - **CSV**：给系统看的题目交换文件，列名与题库导入模板一致，可以直接回导到题库。
 *   导入接口要求「标准答案」必填，因此 CSV 必然含答案，「含答案」开关对它不生效。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { api, type ExportFormat, type Paper } from '../api'
import { token } from '../session'

const props = defineProps<{ paper: Paper }>()
const emit = defineEmits<{ close: [] }>()

const format = ref<ExportFormat>('md')
const withAnswers = ref(false)
const content = ref('')
const error = ref('')
const busy = ref(false)

/** 下载文件名。中文原样保留，浏览器保存时也就是教师看到的试卷名。 */
const fileName = computed(() => `${props.paper.name}.${format.value === 'csv' ? 'csv' : 'md'}`)
/** CSV 一定带答案，因此那个开关要禁用并解释原因，而不是让它看起来生效了。 */
const answersLocked = computed(() => format.value === 'csv')

/** 取一次导出正文。选项变化时重新取，保证预览与下载的内容始终一致。 */
async function load() {
  error.value = ''
  busy.value = true
  try {
    content.value = await api.exportPaper(token.value, props.paper.id, format.value, withAnswers.value)
  } catch (reason) {
    content.value = ''
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(load)
watch([format, withAnswers], load)

/**
 * 下载当前预览的内容。
 *
 * 直接用已经取回的文本造 blob，而不是再请求一次：一是省一次往返，
 * 二是保证「下载到的」与「刚才看到的」是同一份内容。导出接口要带令牌，
 * 也用不了普通的 `<a href>`。
 */
function download() {
  const type = format.value === 'csv' ? 'text/csv;charset=utf-8' : 'text/markdown;charset=utf-8'
  const url = URL.createObjectURL(new Blob([content.value], { type }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName.value
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="modal-mask" role="dialog" aria-modal="true" aria-label="导出试卷" @click.self="emit('close')">
    <div class="modal modal-wide">
      <header>
        <span>导出试卷：{{ props.paper.name }}</span>
        <button class="btn-link" type="button" style="margin-left: auto" @click="emit('close')">关闭</button>
      </header>

      <div class="body">
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>

        <div class="filter-bar">
          <label>格式
            <select v-model="format" aria-label="导出格式">
              <option value="md">Markdown（可打印试卷）</option>
              <option value="csv">CSV（可回导题库）</option>
            </select>
          </label>
          <label>
            <input type="checkbox" :checked="withAnswers || answersLocked" :disabled="answersLocked"
                   aria-label="含参考答案与解析"
                   @change="withAnswers = ($event.target as HTMLInputElement).checked" />
            含参考答案与解析
          </label>
          <span class="muted">{{ answersLocked
            ? 'CSV 用于回导题库，「标准答案」是导入的必填列，因此必然含答案。'
            : '不勾选时正文里没有任何答案与解析，可直接发给学生。' }}</span>
        </div>

        <p class="muted">
          题干、选项、答案和分值取自<b>试卷快照</b>，与当时发布的卷子完全一致，之后改题库不影响导出结果。
          只有难度、知识点和标签三列快照里没有，取的是题库当前值。
        </p>

        <label class="field">
          <span>预览（{{ fileName }}，{{ content.length }} 字符）</span>
          <textarea
            :value="busy ? '正在生成…' : content"
            readonly
            rows="16"
            aria-label="导出内容预览"
            style="font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px"
          />
        </label>
      </div>

      <footer>
        <button class="btn" type="button" @click="emit('close')">关闭</button>
        <button class="btn btn-primary" type="button" :disabled="busy || !content" @click="download">
          下载文件
        </button>
      </footer>
    </div>
  </div>
</template>
