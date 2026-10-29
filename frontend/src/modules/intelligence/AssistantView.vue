<script setup>
import { onMounted, ref, watch } from 'vue'
import http from '../../api'

const capability = ref(null)
const error = ref('')
const loading = ref(false)
const result = ref(null)
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const from = ref(today)
const to = ref(today)
watch([from, to], () => {
  if (result.value?.type === 'attendance') result.value = null
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
    <h2>制度问答暂未开放</h2>
    <p class="muted">文档接入完成并配置模型后，可根据有权阅读的资料生成带来源的回答。</p>
    <p class="muted">
      问答模型：{{
        capability.modelConfiguration === 'NOT_CONFIGURED' ? '未配置' : '已配置，尚待验证'
      }}；文字识别与音视频转写：未配置。
    </p>
    <label for="assistant-question">制度问题</label>
    <div class="assistant-controls">
      <input id="assistant-question" disabled placeholder="制度问答尚未开放" /><button
        class="button button-primary"
        disabled
      >
        提问
      </button>
    </div>
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
.assistant-controls input {
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
