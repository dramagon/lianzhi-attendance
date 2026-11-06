<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import http, { write } from '../../api'
const props = defineProps({ available: Boolean, suggestionsAvailable: Boolean })
const instruction = ref(''),
  suggestionMessage = ref('')
async function suggest() {
  if (busy.value || pending.value) return
  busy.value = true
  error.value = ''
  suggestionMessage.value = ''
  try {
    const result = await write(
      'post',
      '/assistant/approval-suggestions',
      {
        instruction: instruction.value,
      },
      { timeout: 65000 },
    )
    suggestionMessage.value = result.message
    if (result.ready) {
      Object.assign(draft, result.preview.draft)
      intentKey = null
    }
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
const draft = reactive({
  type: 'LEAVE',
  startAt: '',
  endAt: '',
  reason: '',
  punchType: 'IN',
  workplaceId: null,
})
const pending = ref(null),
  submitted = ref(null),
  busy = ref(false),
  error = ref(''),
  acknowledged = ref(false)
const workplaces = ref([]),
  placesError = ref('')
let intentKey = null
watch(draft, () => {
  if (!pending.value) intentKey = null
})
const labels = { LEAVE: '请假', OVERTIME: '加班', MISSING_PUNCH: '补卡' }
const states = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝', WITHDRAWN: '已撤回' }
function body() {
  return {
    type: draft.type,
    startAt: draft.startAt || null,
    endAt: draft.type === 'MISSING_PUNCH' ? null : draft.endAt || null,
    reason: draft.reason,
    punchType: draft.type === 'MISSING_PUNCH' ? draft.punchType : null,
    workplaceId: draft.type === 'MISSING_PUNCH' ? draft.workplaceId : null,
  }
}
async function prepare() {
  if (busy.value || pending.value) return
  busy.value = true
  error.value = ''
  submitted.value = null
  try {
    intentKey ||= crypto.randomUUID()
    pending.value = await write('post', '/assistant/approval-drafts', { intentKey, draft: body() })
    acknowledged.value = false
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
async function confirm() {
  if (busy.value || !pending.value || !acknowledged.value) return
  busy.value = true
  error.value = ''
  try {
    submitted.value = await write('post', '/assistant/approval-drafts/confirm', {
      confirmationId: pending.value.confirmationId,
      confirmed: true,
    })
    pending.value = null
    acknowledged.value = false
  } catch (e) {
    error.value = `${e.message}。若提交结果不明，请先查看“我的申请”，不要另建相同申请。`
  } finally {
    busy.value = false
  }
}
async function cancel() {
  if (busy.value || !pending.value) return
  busy.value = true
  error.value = ''
  try {
    await write('post', `/assistant/approval-drafts/${pending.value.confirmationId}/cancel`)
    pending.value = null
    acknowledged.value = false
    intentKey = null
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
function newDraft() {
  submitted.value = null
  intentKey = null
  error.value = ''
  draft.reason = ''
  draft.startAt = ''
  draft.endAt = ''
}
onMounted(async () => {
  try {
    workplaces.value = await http.get('/attendance/me/workplaces')
  } catch (e) {
    placesError.value = e.message
  }
})
</script>

<template>
  <section class="panel confirmation-panel" aria-label="申请确认">
    <h2>准备申请</h2>
    <p class="muted">
      可手工填写，或由已配置模型整理建议。建议不会提交申请，仍需核对、预览及明确确认。
    </p>
    <div v-if="!pending && !submitted" class="suggestion">
      <label
        >用一句话描述申请<textarea
          v-model="instruction"
          maxlength="1000"
          :disabled="busy"
          placeholder="例如：2026年11月6日9点至18点请假，原因是办理证件。"
        ></textarea></label
      ><button
        class="button button-secondary"
        :disabled="busy || !instruction.trim() || !props.suggestionsAvailable || !props.available"
        @click="suggest"
      >
        生成申请建议
      </button>
      <p v-if="!props.suggestionsAvailable" class="muted">模型未配置，可直接填写下方表单。</p>
      <p v-if="suggestionMessage" role="status">{{ suggestionMessage }}</p>
    </div>
    <div v-if="error" class="notice notice-error" role="alert">{{ error }}</div>
    <template v-if="submitted">
      <p role="status">
        申请 #{{ submitted.request.id }} 已提交，当前状态：{{
          states[submitted.request.state] || submitted.request.state
        }}。
      </p>
      <RouterLink class="text-button" to="/approvals">查看申请与审计</RouterLink
      ><button class="button button-secondary" @click="newDraft">准备另一份申请</button>
    </template>
    <form v-else-if="!pending" @submit.prevent="prepare">
      <fieldset :disabled="busy || !props.available">
        <div class="confirmation-fields">
          <label
            >申请类型<select v-model="draft.type">
              <option value="LEAVE">请假</option>
              <option value="OVERTIME">加班</option>
              <option value="MISSING_PUNCH">补卡</option>
            </select></label
          >
          <label
            >开始时间（北京时间）<input v-model="draft.startAt" type="datetime-local" required
          /></label>
          <label v-if="draft.type !== 'MISSING_PUNCH'"
            >结束时间（北京时间）<input v-model="draft.endAt" type="datetime-local" required
          /></label>
          <template v-else>
            <label
              >补卡方向<select v-model="draft.punchType">
                <option value="IN">上班</option>
                <option value="OUT">下班</option>
              </select></label
            >
            <label
              >补卡场所<select v-model="draft.workplaceId" required>
                <option :value="null" disabled>请选择本人有效场所</option>
                <option v-for="place in workplaces" :key="place.id" :value="place.id">
                  {{ place.name }}
                </option>
              </select></label
            >
            <p v-if="placesError" class="muted">{{ placesError }}</p>
          </template>
          <label class="confirmation-reason"
            >申请事由<textarea v-model="draft.reason" required maxlength="500" rows="3"></textarea>
          </label>
        </div>
        <button class="button button-primary" type="submit">预览申请内容</button>
      </fieldset>
    </form>
    <div v-else class="confirmation-preview" aria-label="待确认内容">
      <h3>请核对以下内容</h3>
      <dl>
        <dt>申请人</dt>
        <dd>{{ pending.preview.applicantName }}</dd>
        <dt>类型</dt>
        <dd>{{ labels[pending.preview.draft.type] }}</dd>
        <dt>开始时间</dt>
        <dd>{{ pending.preview.draft.startAt.replace('T', ' ') }}（北京时间）</dd>
        <template v-if="pending.preview.draft.endAt"
          ><dt>结束时间</dt>
          <dd>{{ pending.preview.draft.endAt.replace('T', ' ') }}（北京时间）</dd></template
        >
        <template v-if="pending.preview.draft.punchType"
          ><dt>补卡方向与场所</dt>
          <dd>
            {{ pending.preview.draft.punchType === 'IN' ? '上班' : '下班' }} ·
            {{
              workplaces.find((p) => p.id === pending.preview.draft.workplaceId)?.name ||
              '本人绑定场所'
            }}
          </dd></template
        >
        <dt>事由</dt>
        <dd>{{ pending.preview.draft.reason }}</dd>
      </dl>
      <p class="muted">
        此时尚未保存申请。预览自然时长
        {{ pending.preview.requestedMinutes }} 分钟，不代表有效工时或已获批准。确认有效期10分钟。
      </p>
      <label class="acknowledgement"
        ><input
          v-model="acknowledged"
          type="checkbox"
          :disabled="busy"
        />我已核对内容，同意提交这份申请</label
      >
      <div class="confirmation-buttons">
        <button class="button button-primary" :disabled="busy || !acknowledged" @click="confirm">
          确认提交申请</button
        ><button class="button button-secondary" :disabled="busy" @click="cancel">
          取消或修改
        </button>
      </div>
    </div>
    <p v-if="!props.available" class="muted">申请服务暂不可用，请稍后重试。</p>
  </section>
</template>

<style scoped>
.confirmation-panel {
  padding: 24px;
  margin-bottom: 20px;
}
.confirmation-panel h2 {
  margin-bottom: 14px;
}
fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.confirmation-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 230px), 1fr));
  gap: 14px;
  margin: 18px 0;
}
.confirmation-fields label {
  display: grid;
  gap: 8px;
  min-width: 0;
}
.confirmation-fields input,
.confirmation-fields select,
textarea {
  width: 100%;
  min-width: 0;
  padding: 10px;
  border: 1px solid #d6dce5;
  border-radius: 8px;
  font: inherit;
}
.confirmation-reason {
  grid-column: 1 / -1;
}
.confirmation-preview {
  overflow-wrap: anywhere;
}
.confirmation-preview dt {
  color: var(--muted);
  margin-top: 12px;
}
.confirmation-preview dd {
  margin: 5px 0 0;
  white-space: pre-wrap;
}
.confirmation-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 16px;
}
.acknowledgement {
  display: flex;
  gap: 10px;
  align-items: start;
  line-height: 1.6;
}
.acknowledgement input {
  margin-top: 5px;
  flex-shrink: 0;
}
@media (max-width: 600px) {
  .confirmation-panel {
    padding: 18px;
  }
}
</style>
