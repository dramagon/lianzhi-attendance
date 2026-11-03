<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import http, { write } from '../../api'
const props = defineProps({ mode: String, profile: Object })
const items = ref([]),
  total = ref(0),
  page = ref(1),
  loading = ref(false),
  error = ref('')
const detail = ref(null),
  note = ref(''),
  processing = ref(false),
  decisionError = ref(''),
  message = ref('')
const labels = { LEAVE: '请假', MISSING_PUNCH: '补卡', OVERTIME: '加班' }
const states = { PENDING: '待审批', APPROVED: '已同意', REJECTED: '已拒绝', WITHDRAWN: '已撤回' }
const time = (value) => (value ? value.replace('T', ' ') : '—')
let sequence = 0,
  details = 0,
  alive = true
async function load() {
  const current = ++sequence
  loading.value = true
  error.value = ''
  items.value = []
  try {
    const result = await http.get(props.mode === 'mine' ? '/approvals/me' : '/approvals/inbox', {
      params: {
        page: page.value,
        size: 10,
        state: props.mode === 'mine' ? 'ALL' : props.mode === 'history' ? 'PROCESSED' : 'PENDING',
      },
    })
    if (current === sequence) {
      items.value = result.items
      total.value = result.total
    }
  } catch (e) {
    if (current === sequence) error.value = e.message
  } finally {
    if (current === sequence) loading.value = false
  }
}
async function show(id) {
  const current = ++details
  detail.value = null
  note.value = ''
  decisionError.value = ''
  message.value = ''
  try {
    const result = await http.get(`/approvals/${id}`)
    if (current === details) detail.value = result
  } catch (e) {
    if (current === details) error.value = e.message
  }
}
async function decide(action) {
  if (!detail.value || processing.value) return
  processing.value = true
  decisionError.value = ''
  message.value = ''
  try {
    const result = await write('post', `/approvals/${detail.value.entry.request.id}/actions`, {
      action,
      expectedVersion: detail.value.entry.request.version,
      note: note.value,
    })
    if (!alive) return
    detail.value = result
    message.value =
      action === 'APPROVE'
        ? '已同意申请，考勤调整已同步。'
        : action === 'REJECT'
          ? '已拒绝申请。'
          : '已撤回申请。'
    await load()
  } catch (e) {
    if (alive) decisionError.value = e.message
  } finally {
    if (alive) processing.value = false
  }
}
async function turn(delta) {
  page.value += delta
  detail.value = null
  details++
  await load()
}
function closeDetail() {
  detail.value = null
  details++
}
onMounted(load)
onBeforeUnmount(() => {
  alive = false
  sequence++
  details++
})
</script>
<template>
  <div class="approval-records">
    <div class="actions">
      <button class="button button-secondary" :disabled="loading || processing" @click="load">
        刷新记录
      </button>
    </div>
    <div v-if="error" class="notice notice-error" role="alert">{{ error }}</div>
    <p v-else-if="loading" class="muted">正在读取申请…</p>
    <p v-else-if="!items.length" class="empty">
      {{
        mode === 'mine' ? '暂无申请记录' : mode === 'inbox' ? '暂无待审批申请' : '暂无已处理申请'
      }}
    </p>
    <article v-for="entry in items" :key="entry.request.id" class="record">
      <div>
        <b>{{ labels[entry.request.draft.type] }} · {{ entry.applicantName }}</b>
        <p>
          {{ time(entry.request.draft.startAt)
          }}<span v-if="entry.request.draft.endAt"> 至 {{ time(entry.request.draft.endAt) }}</span>
        </p>
        <span>{{ states[entry.request.state] }} · 申请 #{{ entry.request.id }}</span>
      </div>
      <button
        class="button button-secondary"
        :disabled="processing"
        @click="show(entry.request.id)"
      >
        查看 #{{ entry.request.id }}
      </button>
    </article>
    <div v-if="!loading && !error && total" class="actions pagination">
      <button class="button button-secondary" :disabled="page <= 1 || processing" @click="turn(-1)">
        上一页</button
      ><span>第 {{ page }} 页 · 共 {{ total }} 条</span
      ><button
        class="button button-secondary"
        :disabled="page * 10 >= total || processing"
        @click="turn(1)"
      >
        下一页
      </button>
    </div>
    <section v-if="detail" class="detail" aria-label="申请详情">
      <div class="actions">
        <h2>申请 #{{ detail.entry.request.id }} · {{ states[detail.entry.request.state] }}</h2>
        <button class="button button-secondary" :disabled="processing" @click="closeDetail">
          关闭详情
        </button>
      </div>
      <p>{{ detail.entry.applicantName }} · {{ labels[detail.entry.request.draft.type] }}</p>
      <p>
        {{ time(detail.entry.request.draft.startAt)
        }}<span v-if="detail.entry.request.draft.endAt">
          至 {{ time(detail.entry.request.draft.endAt) }}</span
        >
      </p>
      <p v-if="detail.entry.request.draft.punchType">
        {{ detail.entry.request.draft.punchType === 'IN' ? '上班补卡' : '下班补卡' }} · 场所 #{{
          detail.entry.request.draft.workplaceId
        }}
      </p>
      <p class="reason">{{ detail.entry.request.draft.reason }}</p>
      <h3>处理记录</h3>
      <ol>
        <li v-for="event in detail.history" :key="event.version">
          {{ event.version === 0 ? '已提交' : states[event.toState] }} ·
          {{ time(event.occurredAt) }} · 操作人 #{{ event.actorId }}
          <p v-if="event.reason" class="reason">{{ event.reason }}</p>
        </li>
      </ol>
      <div v-if="message" class="notice" role="status">{{ message }}</div>
      <div v-if="decisionError" class="notice notice-error" role="alert">{{ decisionError }}</div>
      <template v-if="detail.entry.request.state === 'PENDING'">
        <template v-if="mode !== 'mine' && profile?.userId !== detail.entry.request.applicantId"
          ><label
            >处理说明（拒绝时必填）<textarea
              v-model="note"
              :disabled="processing"
              maxlength="500"
              rows="3"
            ></textarea>
          </label>
          <div class="actions">
            <button class="button button-primary" :disabled="processing" @click="decide('APPROVE')">
              同意申请并同步考勤</button
            ><button
              class="button button-secondary"
              :disabled="processing || !note.trim()"
              @click="decide('REJECT')"
            >
              拒绝申请
            </button>
          </div></template
        >
        <p v-else-if="mode !== 'mine'" class="muted">
          不能审批自己的申请，请由其他有权限的管理人员处理。
        </p>
        <button
          v-if="profile?.userId === detail.entry.request.applicantId"
          class="button button-secondary"
          :disabled="processing"
          @click="decide('WITHDRAW')"
        >
          撤回申请
        </button>
      </template>
      <p v-else class="muted">申请已处理。已生效申请暂不支持撤销。</p>
    </section>
  </div>
</template>
<style scoped>
.approval-records {
  padding: 24px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}
.empty {
  padding: 30px 0;
  text-align: center;
}
.record {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 18px 0;
  border-bottom: 1px solid #edf0f5;
  overflow-wrap: anywhere;
}
.record p {
  font-size: 14px;
  margin: 8px 0;
}
.record span {
  font-size: 13px;
  color: #61728c;
}
.record button {
  flex-shrink: 0;
}
.pagination {
  margin-top: 20px;
}
.detail {
  border-top: 2px solid #edf0f5;
  margin-top: 24px;
  padding-top: 18px;
  overflow-wrap: anywhere;
}
.detail h2 {
  font-size: 19px;
  margin-right: auto;
}
.detail li {
  margin-bottom: 16px;
  font-size: 14px;
}
.reason {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}
textarea {
  width: 100%;
  padding: 12px;
  border: 1px solid #ccd6e6;
  border-radius: 9px;
  font: inherit;
  resize: vertical;
}
@media (max-width: 600px) {
  .approval-records {
    padding: 18px;
  }
  .record {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
