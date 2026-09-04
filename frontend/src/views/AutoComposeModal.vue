<script setup lang="ts">
/**
 * 规则自动组卷弹窗：写几条「题型 / 难度 / 知识点 + 抽几道 + 每道几分」的规则，生成一份方案。
 *
 * 两个刻意的设计决定：
 *
 * 1. **只生成方案，不直接保存试卷。** 点「应用到试卷」只是把抽中的题目填进组卷页右侧，
 *    保存仍走原来的「保存草稿」。这样分值合计、题目归属和启用状态的校验只有一处实现，
 *    自动组卷不会成为一条绕过校验的旁路——与 AI 出题「只出草稿、保存走普通接口」是同一条标准。
 * 2. **抽题结果直接展示出来。** 抽题是随机的，如果生成和保存各抽一次，教师看到的方案
 *    就不是最终保存的那份。展示具体题目也让「换一批」有意义：不满意再点一次即可。
 */
import { ref } from 'vue'
import {
  api, difficultyLabels, typeLabels,
  type AutoComposePlan, type AutoComposeRule, type Difficulty, type KnowledgePoint,
  type Question, type QuestionType,
} from '../api'
import { token } from '../session'

const props = defineProps<{ points: KnowledgePoint[] }>()
/** 应用方案时把「题目 + 分值」交给组卷页，由它填进试卷内容。 */
const emit = defineEmits<{ close: []; apply: [items: { question: Question; score: number }[]] }>()

/** 表单里的一条规则。三个筛选维度用空串表示「不限」，提交时再转成 null。 */
interface RuleForm { type: string; difficulty: string; knowledgePointId: string; count: number; score: number }

/** 默认给一条「不限条件、抽 5 道、每道 10 分」的规则，教师改比从零加更快。 */
const rules = ref<RuleForm[]>([{ type: '', difficulty: '', knowledgePointId: '', count: 5, score: 10 }])
const plan = ref<AutoComposePlan | null>(null)
const error = ref('')
const busy = ref(false)

/** 加一条规则，默认沿用上一条的分值，通常同一份卷子里同题型分值相同。 */
function addRule() {
  const last = rules.value[rules.value.length - 1]
  rules.value.push({ type: '', difficulty: '', knowledgePointId: '', count: 5, score: last?.score ?? 10 })
}

/** 删一条规则。至少留一条，否则界面上会出现一个无法提交的空表。 */
function removeRule(index: number) {
  if (rules.value.length <= 1) return
  rules.value = rules.value.filter((_, position) => position !== index)
  plan.value = null
}

/** 生成方案。反复点就是「换一批」：服务端每次重新随机抽题。 */
async function generate() {
  error.value = ''
  busy.value = true
  try {
    plan.value = await api.autoComposePaper(token.value, rules.value.map(toRule))
  } catch (reason) {
    plan.value = null
    error.value = (reason as Error).message
  } finally {
    busy.value = false
  }
}

/** 表单值转接口参数：空串是「不限」，必须转成 null 而不是空字符串。 */
function toRule(form: RuleForm): AutoComposeRule {
  return {
    type: form.type ? (form.type as QuestionType) : null,
    difficulty: form.difficulty ? (form.difficulty as Difficulty) : null,
    knowledgePointId: form.knowledgePointId ? Number(form.knowledgePointId) : null,
    count: Number(form.count),
    score: Number(form.score),
  }
}

/** 把方案交给组卷页。父组件负责去重和提示，这里只管把数据抛出去。 */
function apply() {
  if (!plan.value) return
  emit('apply', plan.value.items.map((item) => ({ question: item.question, score: item.score })))
}
</script>

<template>
  <div class="modal-mask" role="dialog" aria-modal="true" aria-label="规则自动组卷" @click.self="emit('close')">
    <div class="modal modal-wide">
      <header>
        <span>规则自动组卷</span>
        <button class="btn-link" type="button" style="margin-left: auto" @click="emit('close')">关闭</button>
      </header>

      <div class="body">
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>
        <p class="muted">
          规则按顺序抽题，同一道题不会被两条规则重复抽中，因此<b>先写条件窄的规则</b>更符合预期。
          只从本人题库里<b>启用状态</b>的题目中抽取；题目不够时会直接报错，不会悄悄少抽几道。
        </p>

        <!-- 规则表。每行一条规则，三个筛选维度都可以留「不限」 -->
        <table class="table">
          <thead>
            <tr>
              <th style="width: 44px">序号</th>
              <th style="width: 120px">题型</th>
              <th style="width: 96px">难度</th>
              <th>知识点</th>
              <th style="width: 96px" class="num">抽题数</th>
              <th style="width: 104px" class="num">每题分值</th>
              <th style="width: 60px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(rule, index) in rules" :key="index">
              <td class="muted">{{ index + 1 }}</td>
              <td>
                <select v-model="rule.type" :aria-label="`第 ${index + 1} 条规则的题型`" @change="plan = null">
                  <option value="">不限</option>
                  <option v-for="(label, key) in typeLabels" :key="key" :value="key">{{ label }}</option>
                </select>
              </td>
              <td>
                <select v-model="rule.difficulty" :aria-label="`第 ${index + 1} 条规则的难度`" @change="plan = null">
                  <option value="">不限</option>
                  <option v-for="(label, key) in difficultyLabels" :key="key" :value="key">{{ label }}</option>
                </select>
              </td>
              <td>
                <select v-model="rule.knowledgePointId" :aria-label="`第 ${index + 1} 条规则的知识点`" @change="plan = null">
                  <option value="">不限</option>
                  <option v-for="point in props.points" :key="point.id" :value="String(point.id)">{{ point.name }}</option>
                </select>
              </td>
              <td class="num">
                <input v-model.number="rule.count" class="control" type="number" min="1" max="100"
                       :aria-label="`第 ${index + 1} 条规则抽题数`" @input="plan = null" />
              </td>
              <td class="num">
                <input v-model.number="rule.score" class="control" type="number" min="0.1" step="0.1"
                       :aria-label="`第 ${index + 1} 条规则每题分值`" @input="plan = null" />
              </td>
              <td class="ops">
                <button class="btn-link" type="button" :disabled="rules.length <= 1" @click="removeRule(index)">
                  删除
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div style="display: flex; gap: 10px; align-items: center">
          <button class="btn" type="button" @click="addRule">添加规则</button>
          <button class="btn btn-primary" type="button" :disabled="busy" @click="generate">
            {{ busy ? '抽题中…' : plan ? '换一批' : '生成方案' }}
          </button>
          <span class="muted">生成方案不会保存试卷；应用到试卷后仍需点「保存草稿」。</span>
        </div>

        <!-- 规则执行结果。候选池大小比「抽了几道」更有信息量：等于抽题数时这条规则没有随机空间 -->
        <template v-if="plan">
          <div class="stat-row">
            <div class="stat"><span>抽中题数</span><b class="cell-strong">{{ plan.questionCount }}</b></div>
            <div class="stat"><span>方案总分</span><b class="cell-strong">{{ plan.totalScore }}</b></div>
            <div class="stat"><span>规则条数</span><b>{{ plan.rules.length }}</b></div>
          </div>

          <table class="table">
            <thead>
              <tr>
                <th style="width: 44px">规则</th>
                <th>条件</th>
                <th style="width: 80px" class="num">抽题数</th>
                <th style="width: 92px" class="num">候选池</th>
                <th style="width: 92px" class="num">每题分</th>
                <th style="width: 80px" class="num">小计</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="rule in plan.rules" :key="rule.ruleIndex">
                <td class="muted">{{ rule.ruleIndex }}</td>
                <td class="cell-strong">{{ rule.label }}</td>
                <td class="num">{{ rule.count }}</td>
                <td class="num" :class="rule.poolSize === rule.count ? 'muted' : ''">
                  {{ rule.poolSize }}{{ rule.poolSize === rule.count ? '（无可换）' : '' }}
                </td>
                <td class="num">{{ rule.score }}</td>
                <td class="num">{{ rule.subtotal }}</td>
              </tr>
            </tbody>
          </table>

          <div class="card">
            <div class="panel-title">
              <h3>抽中的题目</h3>
              <span class="spacer">共 {{ plan.items.length }} 题 | 总分 {{ plan.totalScore }} 分</span>
            </div>
            <table class="table">
              <thead>
                <tr>
                  <th style="width: 44px">题号</th>
                  <th>题目内容</th>
                  <th style="width: 76px">题型</th>
                  <th style="width: 52px">难度</th>
                  <th style="width: 60px" class="num">分值</th>
                  <th style="width: 56px" class="num">规则</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(item, index) in plan.items" :key="item.question.id">
                  <td class="muted">{{ index + 1 }}</td>
                  <td class="cell-strong">{{ item.question.stem }}</td>
                  <td>{{ typeLabels[item.question.type] }}</td>
                  <td>{{ difficultyLabels[item.question.difficulty] }}</td>
                  <td class="num">{{ item.score }}</td>
                  <td class="num muted">{{ item.ruleIndex }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </div>

      <footer>
        <button class="btn" type="button" @click="emit('close')">关闭</button>
        <button class="btn btn-primary" type="button" :disabled="!plan" @click="apply">
          应用到试卷{{ plan ? ` ${plan.questionCount} 道` : '' }}
        </button>
      </footer>
    </div>
  </div>
</template>
