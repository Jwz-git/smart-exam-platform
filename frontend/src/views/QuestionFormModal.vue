<script setup lang="ts">
/**
 * 新增/编辑题目的弹窗表单，五种题型共用。
 *
 * 难点在于标准答案的形态随题型变化：选择题是选项键数组、判断题是布尔值、
 * 简答题和编程题是文本。这里的做法是用四个独立字段分别存放（singleAnswer /
 * multipleAnswer / booleanAnswer / textAnswer），提交时再按当前题型拼成后端要的结构。
 * 好处是切换题型不会丢已填内容，用户切回去还在。
 *
 * 表单校验与后端 `QuestionService#validate` 一一对应。前端校验只为少一次失败往返，
 * 后端仍会完整校验一遍，两处都不能省。
 */
import { computed, reactive, ref, watch } from 'vue'
import {
  difficultyLabels, isChoice, typeLabels,
  type Difficulty, type KnowledgePoint, type Option, type Question, type QuestionPayload, type QuestionType,
} from '../api'

/**
 * @property question 要编辑的题目；为 null 表示新增
 * @property points   可选知识点列表，由父组件统一拉取，避免每次开弹窗都请求一次
 * @property prefill  预填内容，用于 AI 草稿：`question` 为 null 时按它初始化表单。
 *                    刻意不复用 `question`——那样保存时会走「编辑」分支去 PUT 一个不存在的 ID
 */
const props = defineProps<{ question: Question | null; points: KnowledgePoint[]; prefill?: QuestionPayload | null }>()

/** 保存动作交给父组件执行：它才知道该调新增接口还是编辑接口。 */
const emit = defineEmits<{ close: []; save: [payload: QuestionPayload] }>()

/** 表单状态。四个 answer 字段按题型分别使用，见文件头说明。 */
const form = reactive({
  type: 'SINGLE_CHOICE' as QuestionType,
  stem: '',
  difficulty: 'MEDIUM' as Difficulty,
  tags: '',
  explanation: '',
  suggestedScore: 10,
  knowledgePointId: '',
  options: [] as Option[],
  singleAnswer: '',
  multipleAnswer: [] as string[],
  booleanAnswer: true,
  textAnswer: '',
})
const error = ref('')

/**
 * 打开时初始化表单，三种情形：
 * 编辑已有题目 → 回填该题；AI 草稿 → 回填草稿；纯新增 → 空表单加两个空选项。
 *
 * 草稿与已有题目的字段形状几乎一致，因此统一归一成一个 `source` 再回填，避免写三段重复代码。
 */
watch(() => [props.question, props.prefill], () => {
  error.value = ''
  const first = props.points[0]
  const source = props.question ?? props.prefill ?? null
  if (!source) {
    Object.assign(form, {
      type: 'SINGLE_CHOICE', stem: '', difficulty: 'MEDIUM', tags: '', explanation: '',
      suggestedScore: 10, knowledgePointId: first ? String(first.id) : '',
      options: [{ key: 'A', content: '' }, { key: 'B', content: '' }],
      singleAnswer: '', multipleAnswer: [], booleanAnswer: true, textAnswer: '',
    })
    return
  }
  const question = source
  const answer = question.standardAnswer
  Object.assign(form, {
    type: question.type,
    stem: question.stem,
    difficulty: question.difficulty,
    tags: question.tags ?? '',
    explanation: question.explanation ?? '',
    suggestedScore: question.suggestedScore,
    knowledgePointId: String(question.knowledgePointId),
    options: question.options.map((option) => ({ key: option.key, content: option.content })),
    singleAnswer: Array.isArray(answer) ? String(answer[0] ?? '') : '',
    multipleAnswer: Array.isArray(answer) ? answer.map(String) : [],
    booleanAnswer: typeof answer === 'boolean' ? answer : true,
    textAnswer: typeof answer === 'string' ? answer : '',
  })
}, { immediate: true })

/** 切换题型时补齐或清空选项，避免留下与题型不符的数据。 */
watch(() => form.type, (type) => {
  if (isChoice(type) && form.options.length < 2) {
    form.options = [{ key: 'A', content: '' }, { key: 'B', content: '' }]
  }
  if (!isChoice(type)) form.options = []
})

/** 规范化后的选项键集合，用于查重和校验答案是否指向真实存在的选项。 */
const optionKeys = computed(() => form.options.map((option) => option.key.trim().toUpperCase()).filter(Boolean))

/** 追加一个选项，键按 A、B、C 顺序自动生成。 */
function addOption() {
  const next = String.fromCharCode(65 + form.options.length)
  form.options.push({ key: next, content: '' })
}
/** 删除选项，同时清掉指向它的答案，避免留下一个指向已删除选项的标准答案。 */
function removeOption(index: number) {
  const removed = form.options[index]?.key.trim().toUpperCase()
  form.options.splice(index, 1)
  if (form.singleAnswer === removed) form.singleAnswer = ''
  form.multipleAnswer = form.multipleAnswer.filter((key) => key !== removed)
}
/** 多选题勾选或取消一个答案键。 */
function toggleMultiple(key: string) {
  form.multipleAnswer = form.multipleAnswer.includes(key)
    ? form.multipleAnswer.filter((value) => value !== key)
    : [...form.multipleAnswer, key]
}

/** 前端先做一遍与后端一致的校验，减少一次失败往返；后端仍会再校验一次。 */
function buildPayload(): QuestionPayload | null {
  if (!form.stem.trim()) { error.value = '题干不能为空'; return null }
  if (!form.knowledgePointId) { error.value = '请选择知识点'; return null }
  if (!(form.suggestedScore > 0)) { error.value = '建议分值必须大于 0'; return null }
  let standardAnswer: unknown
  if (isChoice(form.type)) {
    if (form.options.length < 2) { error.value = '选择题至少需要两个选项'; return null }
    if (new Set(optionKeys.value).size !== form.options.length) { error.value = '选项键不能重复或为空'; return null }
    if (form.options.some((option) => !option.content.trim())) { error.value = '选项内容不能为空'; return null }
    const picked = form.type === 'SINGLE_CHOICE' ? (form.singleAnswer ? [form.singleAnswer] : []) : form.multipleAnswer
    if (!picked.length) { error.value = '请勾选标准答案'; return null }
    standardAnswer = picked
  } else if (form.type === 'TRUE_FALSE') {
    standardAnswer = form.booleanAnswer
  } else {
    if (!form.textAnswer.trim()) { error.value = '请填写参考答案'; return null }
    standardAnswer = form.textAnswer.trim()
  }
  return {
    type: form.type,
    stem: form.stem.trim(),
    difficulty: form.difficulty,
    tags: form.tags.trim() || null,
    standardAnswer,
    explanation: form.explanation.trim() || null,
    suggestedScore: Number(form.suggestedScore),
    knowledgePointId: Number(form.knowledgePointId),
    options: isChoice(form.type)
      ? form.options.map((option) => ({ key: option.key.trim().toUpperCase(), content: option.content.trim() }))
      : [],
  }
}

/** 校验并提交。校验不通过时只显示提示，不触发保存。 */
function submit() {
  error.value = ''
  const payload = buildPayload()
  if (payload) emit('save', payload)
}

/**
 * 暴露给父组件的方法：保存失败时把后端错误写进弹窗内部显示，
 * 而不是关掉弹窗再在页面上提示——那样用户填的内容就白填了。
 */
defineExpose({ setError: (message: string) => { error.value = message } })
</script>

<template>
  <!-- click.self 保证只有点击遮罩本身才关闭；点弹窗内部不会误关 -->
  <div class="modal-mask" role="dialog" aria-modal="true" aria-label="题目表单" @click.self="emit('close')">
    <form class="modal" @submit.prevent="submit">
      <header>
        <span>{{ props.question ? '编辑题目' : props.prefill ? '确认 AI 草稿' : '新增题目' }}</span>
        <button class="btn-link" type="button" style="margin-left: auto" @click="emit('close')">关闭</button>
      </header>

      <div class="body">
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>

        <div class="grid-2">
          <label class="field">
            <span>题型</span>
            <select v-model="form.type">
              <option v-for="(label, key) in typeLabels" :key="key" :value="key">{{ label }}</option>
            </select>
          </label>
          <label class="field">
            <span>难度</span>
            <select v-model="form.difficulty">
              <option v-for="(label, key) in difficultyLabels" :key="key" :value="key">{{ label }}</option>
            </select>
          </label>
        </div>

        <label class="field">
          <span>题干</span>
          <textarea v-model="form.stem" placeholder="请输入题目内容" required />
        </label>

        <div class="grid-2">
          <label class="field">
            <span>知识点</span>
            <select v-model="form.knowledgePointId" required>
              <option value="" disabled>请选择</option>
              <option v-for="point in props.points" :key="point.id" :value="String(point.id)">{{ point.name }}</option>
            </select>
          </label>
          <label class="field">
            <span>建议分值</span>
            <input v-model.number="form.suggestedScore" type="number" min="0.1" max="99999" step="0.1" required />
          </label>
        </div>

        <label class="field">
          <span>关键词（英文逗号分隔，例如 Java, 特点）</span>
          <input v-model="form.tags" maxlength="200" placeholder="Java, 特点" />
        </label>

        <!-- 选择题：选项编辑与标准答案勾选合并在一处。
             单选用 radio、多选用 checkbox，勾中哪个就表示哪个是正确答案，
             比「先填选项再到另一个框里输入答案键」少一次心智负担，也不会填出不存在的键 -->
        <template v-if="isChoice(form.type)">
          <div class="field">
            <span>选项与标准答案</span>
            <div class="card card-pad" style="display: grid; gap: 8px">
              <div v-for="(option, index) in form.options" :key="index"
                   style="display: flex; align-items: center; gap: 8px">
                <input
                  v-if="form.type === 'SINGLE_CHOICE'"
                  type="radio"
                  name="single-answer"
                 
                  :aria-label="`选项 ${option.key} 为标准答案`"
                  :checked="form.singleAnswer === option.key.trim().toUpperCase()"
                  @change="form.singleAnswer = option.key.trim().toUpperCase()"
                />
                <input
                  v-else
                  type="checkbox"
                 
                  :aria-label="`选项 ${option.key} 为标准答案`"
                  :checked="form.multipleAnswer.includes(option.key.trim().toUpperCase())"
                  @change="toggleMultiple(option.key.trim().toUpperCase())"
                />
                <!-- 选项键限长 8，与数据库 VARCHAR(8) 对齐 -->
                <input v-model="option.key" maxlength="8" style="width: 64px" aria-label="选项键" />
                <input v-model="option.content" placeholder="选项内容" aria-label="选项内容" />
                <!-- 至少保留两个选项，因此只剩两个时禁用删除 -->
                <button class="btn-link danger" type="button" :disabled="form.options.length <= 2"
                        @click="removeOption(index)">删除</button>
              </div>
              <button class="btn" type="button" style="justify-self: start" @click="addOption">添加选项</button>
            </div>
          </div>
        </template>

        <!-- 判断题：答案是布尔值，用下拉框直接选，不需要选项 -->
        <label v-else-if="form.type === 'TRUE_FALSE'" class="field">
          <span>标准答案</span>
          <select v-model="form.booleanAnswer">
            <option :value="true">正确</option>
            <option :value="false">错误</option>
          </select>
        </label>

        <!-- 简答题与编程题：只填参考答案，供教师阅卷时对照，不参与自动判分 -->
        <label v-else class="field">
          <span>参考答案（教师阅卷时对照，不做自动判分）</span>
          <textarea v-model="form.textAnswer" placeholder="请输入参考答案" />
        </label>

        <label class="field">
          <span>解析（选填）</span>
          <textarea v-model="form.explanation" placeholder="解题思路或评分要点" />
        </label>
      </div>

      <footer>
        <button class="btn" type="button" @click="emit('close')">取消</button>
        <button class="btn btn-primary" type="submit">保存</button>
      </footer>
    </form>
  </div>
</template>
