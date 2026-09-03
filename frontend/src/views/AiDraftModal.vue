<script setup lang="ts">
/**
 * AI 辅助出题弹窗：填要求 → 生成草稿 → 逐题确认。
 *
 * 界面刻意做成两段式而不是「一键写进题库」：
 *
 * 1. 题型、难度、知识点和分值由教师在这里选定，不交给模型。这四项决定题目能否通过业务校验，
 *    让模型猜只会提高失败率，也让「生成的和我要的不一样」变成常态。
 * 2. 生成结果只是草稿。点「编辑并保存」会把草稿填进普通的题目表单，教师改完再走
 *    题目新增接口保存——与手工出题完全同一条路径，AI 不存在任何绕过校验的通道。
 *
 * 未配置密钥、超时、非法 JSON 等失败都只在这个弹窗里显示错误，关掉弹窗即可继续手工出题。
 */
import { computed, reactive, ref } from 'vue'
import {
  api, difficultyLabels, isChoice, typeLabels,
  type AiDraftResult, type Difficulty, type KnowledgePoint, type QuestionPayload, type QuestionType,
} from '../api'
import { formatAnswer } from '../format'
import { token } from '../session'

/** @property points 可选知识点，由父组件统一拉取，避免每次开弹窗都请求一次 */
const props = defineProps<{ points: KnowledgePoint[] }>()

/** 采用某份草稿时交给父组件：由它打开题目表单并完成保存。 */
const emit = defineEmits<{ close: []; adopt: [draft: QuestionPayload] }>()

/** 生成参数。默认单选题、中等难度、一道题——最快看到结果。 */
const form = reactive({
  knowledgePointId: props.points[0] ? String(props.points[0].id) : '',
  type: 'SINGLE_CHOICE' as QuestionType,
  difficulty: 'MEDIUM' as Difficulty,
  count: 1,
  suggestedScore: 10,
  requirement: '',
})
const result = ref<AiDraftResult | null>(null)
const error = ref('')
const busy = ref(false)

/** 没有知识点就不能出题：题目必须挂在某个知识点下。 */
const ready = computed(() => Boolean(form.knowledgePointId))

/** 调用生成接口。失败只在弹窗内显示，不影响页面其他部分。 */
async function generate() {
  error.value = ''
  if (!ready.value) { error.value = '请先选择知识点'; return }
  busy.value = true
  try {
    result.value = await api.generateDrafts(token.value, {
      knowledgePointId: Number(form.knowledgePointId),
      type: form.type,
      difficulty: form.difficulty,
      count: form.count,
      suggestedScore: form.suggestedScore,
      requirement: form.requirement.trim() || null,
    })
  } catch (reason) {
    result.value = null
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="modal-mask" role="dialog" aria-modal="true" aria-label="AI 辅助出题" @click.self="emit('close')">
    <div class="modal modal-wide">
      <header>
        <span>AI 辅助出题</span>
        <button class="btn-link" type="button" style="margin-left: auto" @click="emit('close')">关闭</button>
      </header>

      <div class="body">
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>

        <!-- 生成参数。四项关键属性由教师定，模型只写题干、选项、答案和解析 -->
        <div class="grid-2">
          <label class="field">
            <span>知识点</span>
            <select v-model="form.knowledgePointId">
              <option value="" disabled>请选择</option>
              <option v-for="point in props.points" :key="point.id" :value="String(point.id)">{{ point.name }}</option>
            </select>
          </label>
          <label class="field">
            <span>题型</span>
            <select v-model="form.type">
              <option v-for="(label, key) in typeLabels" :key="key" :value="key">{{ label }}</option>
            </select>
          </label>
        </div>
        <div class="grid-2">
          <label class="field">
            <span>难度</span>
            <select v-model="form.difficulty">
              <option v-for="(label, key) in difficultyLabels" :key="key" :value="key">{{ label }}</option>
            </select>
          </label>
          <label class="field">
            <span>数量（1–5）</span>
            <input v-model.number="form.count" type="number" min="1" max="5" step="1" />
          </label>
        </div>
        <label class="field">
          <span>建议分值</span>
          <input v-model.number="form.suggestedScore" type="number" min="0.1" max="99999" step="0.1" />
        </label>
        <label class="field">
          <span>补充要求（选填）</span>
          <textarea v-model="form.requirement" maxlength="500" placeholder="例如：结合 Spring 注解，不要考 API 记忆" />
        </label>

        <div style="display: flex; gap: 10px; align-items: center">
          <button class="btn btn-primary" type="button" :disabled="busy || !ready" @click="generate">
            {{ busy ? '生成中…' : '生成草稿' }}
          </button>
          <span class="muted">草稿不会直接入库，需要逐题确认后才保存。</span>
        </div>

        <template v-if="result">
          <p class="muted">
            由 {{ result.protocol === 'anthropic' ? 'Anthropic' : 'OpenAI 兼容' }} 协议的
            <b>{{ result.model }}</b> 生成，共 {{ result.drafts.length }} 道可用草稿。
          </p>
          <!-- 被丢弃的草稿如实列出，而不是静默少给几道 -->
          <p v-for="warning in result.warnings" :key="warning" class="alert error" role="status">{{ warning }}</p>

          <div v-for="(draft, index) in result.drafts" :key="index" class="card card-pad answer-card">
            <p class="stem">
              {{ index + 1 }}. {{ draft.stem }}
              <span class="muted">（{{ typeLabels[draft.type] }} · {{ draft.suggestedScore }} 分）</span>
            </p>
            <ul v-if="isChoice(draft.type) && draft.options.length" class="option-list">
              <li v-for="option in draft.options" :key="option.key">{{ option.key }}. {{ option.content }}</li>
            </ul>
            <p class="answer-line"><span class="label">标准答案</span>
              <b>{{ formatAnswer(draft.standardAnswer, draft.options) }}</b>
            </p>
            <p v-if="draft.explanation" class="answer-line"><span class="label">解析</span>
              <span class="muted">{{ draft.explanation }}</span>
            </p>
            <p v-if="draft.tags" class="answer-line"><span class="label">关键词</span><span>{{ draft.tags }}</span></p>
            <div>
              <button class="btn btn-primary" type="button" @click="emit('adopt', draft)">编辑并保存</button>
            </div>
          </div>
        </template>
      </div>

      <footer>
        <button class="btn" type="button" @click="emit('close')">关闭</button>
      </footer>
    </div>
  </div>
</template>
