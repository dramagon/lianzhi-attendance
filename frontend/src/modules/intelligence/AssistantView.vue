<script setup>
import { onMounted, ref, watch } from 'vue'
import http, { write } from '../../api'
import { useAuthStore } from '../../stores/auth'
import ApprovalConfirmation from './ApprovalConfirmation.vue'

const capability = ref(null)
const auth = useAuthStore()
const departments = ref([])
const departmentId = ref(null)
const error = ref('')
const loading = ref(false)
const result = ref(null)
const question = ref(''),
  answer = ref(null),
  asking = ref(false),
  reference = ref(null)
let questionSequence = 0
watch(question, () => {
  questionSequence++
  answer.value = null
  reference.value = null
})
async function ask() {
  const sequence = ++questionSequence
  asking.value = true
  error.value = ''
  answer.value = null
  reference.value = null
  try {
    const value = await write(
      'post',
      '/assistant/ask',
      { question: question.value },
      { timeout: 65000 },
    )
    if (sequence === questionSequence) answer.value = value
  } catch (e) {
    if (sequence === questionSequence) error.value = e.message
  } finally {
    asking.value = false
  }
}
async function source(item) {
  const sequence = questionSequence
  reference.value = null
  try {
    const c = item.source
    const value = await http.get(
      `/knowledge/documents/${c.documentId}/sources/${c.chunk.sequence}`,
      { params: { version: c.version } },
    )
    if (sequence === questionSequence) reference.value = value
  } catch (e) {
    if (sequence === questionSequence) error.value = e.message
  }
}
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const from = ref(today)
const to = ref(today)
watch([from, to, departmentId], () => {
  if (['attendance', 'summary'].includes(result.value?.type)) result.value = null
})
const labels = {
  NORMAL: '正常',
  LATE: '迟到',
  EARLY: '早退',
  LATE_AND_EARLY: '迟到且早退',
  PENDING: '待结束',
  ABSENT: '缺勤',
  MISSING_PUNCH: '缺卡',
  REST_DAY: '休息日',
  ON_LEAVE: '请假',
}
const states = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝', WITHDRAWN: '已撤回' }
async function load() {
  error.value = ''
  try {
    capability.value = await http.get('/assistant/status')
    if (['ADMIN', 'HR'].includes(auth.user?.role)) {
      const page = await http.get('/organizations/departments', { params: { page: 1, size: 100 } })
      departments.value = page.items
    }
  } catch (e) {
    error.value = e.message
  }
}
async function query(type) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  result.value = null
  try {
    if (type === 'attendance') {
      const items = await http.get('/assistant/attendance', {
        params: { from: from.value, to: to.value },
      })
      result.value = { type, items }
    } else if (type === 'summary') {
      const summary = await http.get('/assistant/attendance/summary', {
        params: { from: from.value, to: to.value, departmentId: departmentId.value },
      })
      result.value = { type, summary }
    } else {
      const page = await http.get('/assistant/approvals', { params: { page: 1, size: 20 } })
      result.value = { type, ...page }
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">资料与业务查询</div>
      <h1>业务助手</h1>
      <p>查看本人考勤和申请进度，结果来自业务记录。</p>
    </div>
  </div>
  <div v-if="error" role="alert" class="notice notice-error">
    {{ error }}<button class="text-button" @click="load">刷新服务状态</button>
  </div>
  <section v-if="capability" class="panel assistant-panel" aria-label="制度问答状态">
    <h2>制度问答</h2>
    <p class="muted">
      根据有权阅读的资料生成带来源的回答，无可用依据时明确说明。文档中的命令不会获得业务操作权限。
    </p>
    <p class="muted">
      问答模型：{{
        capability.modelConfiguration === 'OFFLINE_EXTRACTIVE'
          ? '离线原文摘录（非生成式AI）'
          : capability.modelConfiguration === 'NOT_CONFIGURED'
            ? '未配置'
            : answer?.state === 'ANSWERED'
              ? '模型已响应'
              : '已配置，等待提问'
      }}；OCR：{{
        capability.ocrConfiguration === 'NOT_CONFIGURED' ? '未配置' : '已配置，尚待验证'
      }}；转写：{{
        capability.transcriptionConfiguration === 'NOT_CONFIGURED' ? '未配置' : '已配置，尚待验证'
      }}。
    </p>
    <label for="assistant-question">制度问题</label>
    <div class="assistant-controls">
      <input
        id="assistant-question"
        v-model="question"
        maxlength="500"
        placeholder="例如：年假如何申请？"
      /><button
        class="button button-primary"
        :disabled="
          asking ||
          !question.trim() ||
          !capability.knowledgeAvailable ||
          capability.modelConfiguration === 'NOT_CONFIGURED'
        "
        @click="ask"
      >
        {{ asking ? '正在核对依据…' : '提问' }}
      </button>
    </div>
  </section>
  <section v-if="answer" class="panel assistant-panel" aria-live="polite">
    <h2>回答与引用</h2>
    <p>{{ answer.message }}</p>
    <p v-if="answer.text" style="white-space: pre-wrap; overflow-wrap: anywhere">
      {{ answer.text }}
    </p>
    <ul>
      <li v-for="item in answer.references" :key="item.id">
        <strong>{{ item.id }} · 第{{ item.source.version }}版</strong>
        <blockquote>{{ item.quote }}</blockquote>
        <button class="button button-secondary" @click="source(item)">核对引用原文</button>
      </li>
    </ul>
    <section v-if="reference" aria-label="引用原文">
      <h3>引用原文 · 第{{ reference.version }}版</h3>
      <p>
        {{ reference.chunk.source.kind }} ·
        {{ reference.chunk.source.ordinal || reference.chunk.source.name || '' }}
      </p>
      <pre style="white-space: pre-wrap; overflow-wrap: anywhere">{{
        reference.chunk.content
      }}</pre>
      <button class="button button-secondary" @click="reference = null">关闭引用</button>
    </section>
  </section>
  <section class="panel assistant-panel" aria-label="本人业务查询">
    <h2>我的业务记录</h2>
    <div class="assistant-controls">
      <label>开始日期<input v-model="from" type="date" :disabled="loading" /></label>
      <label>结束日期<input v-model="to" type="date" :disabled="loading" /></label>
      <button
        class="button button-primary"
        :disabled="loading || !capability?.businessQueriesAvailable"
        @click="query('attendance')"
      >
        查询本人考勤
      </button>
      <button
        class="button button-secondary"
        :disabled="loading || !capability?.businessQueriesAvailable"
        @click="query('approvals')"
      >
        查询本人申请
      </button>
      <template v-if="['ADMIN', 'HR'].includes(auth.user?.role)">
        <label
          >统计部门<select v-model="departmentId" :disabled="loading">
            <option :value="null">全部授权部门</option>
            <option v-for="dept in departments" :key="dept.id" :value="dept.id">
              {{ dept.name }}
            </option>
          </select></label
        >
        <button class="button button-secondary" :disabled="loading" @click="query('summary')">
          查询授权汇总
        </button>
      </template>
    </div>
    <p class="muted small-text">
      考勤最多查询94天；申请先显示最近20条，完整记录请进入“我的申请”。这里的查询不会创建或审批申请。
    </p>
    <p v-if="loading" role="status">正在读取业务记录…</p>
    <div v-if="result" aria-live="polite">
      <template v-if="result.type === 'attendance'">
        <p class="muted">来源：考勤日记录。进行中的班次结果可能变化。</p>
        <p v-if="!result.items.length">所选日期没有排班日记录，不据此推断缺勤。</p>
        <ul v-else class="assistant-results">
          <li v-for="day in result.items" :key="day.businessDate">
            <strong
              >{{ day.businessDate }} ·
              {{ labels[day.attendance.status] || day.attendance.status }}</strong
            >
            <p>
              有效工时 {{ day.attendance.workMinutes }} 分钟 · 请假 {{ day.leaveMinutes }} 分钟 ·
              加班 {{ day.overtimeMinutes }} 分钟
            </p>
            <small v-if="day.approvalIds.length">审批来源：{{ day.approvalIds.join('、') }}</small>
          </li>
        </ul>
      </template>
      <template v-else-if="result.type === 'summary'">
        <p class="muted">
          来源：授权员工的考勤日记录；当前启用且已分配公司的员工，最多100人。未结束班次结果可能变化。
        </p>
        <p>
          统计员工 {{ result.summary.employeeCount }} 人 · 排班日
          {{ result.summary.scheduledDays }} 个 · 待结束 {{ result.summary.pendingDays }} 个
        </p>
        <p>
          有效工时 {{ result.summary.workMinutes }} 分钟 · 请假
          {{ result.summary.leaveMinutes }} 分钟 · 加班 {{ result.summary.overtimeMinutes }} 分钟
        </p>
        <p v-if="!result.summary.scheduledDays">所选范围没有排班日记录，不推断缺勤或出勤率。</p>
      </template>
      <template v-else>
        <p class="muted">来源：本人申请记录，共 {{ result.total }} 条。</p>
        <p v-if="!result.items.length">暂无申请记录。</p>
        <ul v-else class="assistant-results">
          <li v-for="entry in result.items" :key="entry.request.id">
            <strong
              >申请 #{{ entry.request.id }} ·
              {{ states[entry.request.state] || entry.request.state }}</strong
            >
            <p>{{ entry.request.draft.reason }}</p>
          </li>
        </ul>
        <RouterLink class="text-button" to="/approvals">查看完整申请与审计</RouterLink>
      </template>
    </div>
  </section>
  <ApprovalConfirmation
    v-if="capability"
    :available="capability.writeAvailable"
    :suggestions-available="capability.modelConfiguration === 'CONFIGURED_NOT_VERIFIED'"
  />
</template>

<style scoped>
.assistant-panel {
  padding: 24px;
  margin-bottom: 20px;
}
.assistant-panel h2 {
  margin-bottom: 14px;
}
.assistant-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
  margin: 12px 0;
}
.assistant-controls label {
  display: grid;
  gap: 8px;
}
.assistant-controls input,
.assistant-controls select {
  max-width: 100%;
  border: 1px solid #d6dce5;
  border-radius: 8px;
  padding: 10px 12px;
  min-width: 0;
}
#assistant-question {
  flex: 1 1 220px;
}
.assistant-results {
  padding: 0;
  list-style: none;
}
.assistant-results li {
  padding: 14px 0;
  border-top: 1px solid var(--line);
  overflow-wrap: anywhere;
}
@media (max-width: 600px) {
  .assistant-panel {
    padding: 18px;
  }
}
</style>
