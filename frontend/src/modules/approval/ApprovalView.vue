<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http, { write } from '../../api'

const route = useRoute()
const reviewing = computed(() => !!route.meta.approvalReview)
const status = ref(null),
  profile = ref(null),
  workplaces = ref([])
const error = ref(''),
  previewError = ref(''),
  preview = ref(null)
const loading = ref(false),
  validating = ref(false)
const tab = ref(reviewing.value ? 'inbox' : 'draft')
const form = reactive({
  type: 'LEAVE',
  startAt: '',
  endAt: '',
  punchType: 'IN',
  workplaceId: null,
  reason: '',
})
const labels = { LEAVE: '请假', MISSING_PUNCH: '补卡', OVERTIME: '加班' }
let sequence = 0
watch(form, () => {
  sequence++
  preview.value = null
  previewError.value = ''
  validating.value = false
})
watch(reviewing, (value) => {
  tab.value = value ? 'inbox' : 'draft'
  preview.value = null
})
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [readiness, employee] = await Promise.all([
      http.get('/approvals/status'),
      http.get('/employees/me'),
    ])
    status.value = readiness
    profile.value = employee
    workplaces.value = (await http.get(`/employees/${employee.userId}/workplaces`)).filter(
      (place) => place.enabled && place.companyId === employee.companyId,
    )
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
async function validate() {
  const current = ++sequence
  validating.value = true
  previewError.value = ''
  preview.value = null
  const missing = form.type === 'MISSING_PUNCH'
  try {
    const result = await write('post', '/approvals/preview', {
      type: form.type,
      startAt: form.startAt,
      endAt: missing ? null : form.endAt,
      punchType: missing ? form.punchType : null,
      workplaceId: missing ? form.workplaceId : null,
      reason: form.reason,
    })
    if (current === sequence) preview.value = result
  } catch (e) {
    if (current === sequence) previewError.value = e.message
  } finally {
    if (current === sequence) validating.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">申请与审批</div>
      <h1>{{ reviewing ? '审批工作台' : '我的申请' }}</h1>
      <p>
        {{
          reviewing ? '查看待审批申请及处理历史。' : '填写请假、补卡或加班申请，并查看申请进度。'
        }}
      </p>
    </div>
    <button class="button button-secondary" :disabled="loading" @click="load">刷新状态</button>
  </div>
  <div v-if="error" class="notice notice-error" role="alert">{{ error }}</div>
  <div v-else-if="status && !status.available" class="notice" role="status">
    <div>
      <b>申请保存与审批处理尚未接入</b>
      <p>当前可校验申请内容，暂不能提交申请或处理审批。</p>
      <ul>
        <li v-for="blocker in status.blockers" :key="blocker">{{ blocker }}</li>
      </ul>
    </div>
  </div>
  <p v-if="!reviewing && profile && !profile.companyId" class="notice">
    当前账号尚未分配公司，请先联系管理员完成组织归属。
  </p>
  <section class="panel approval-panel">
    <div class="approval-tabs" aria-label="审批页面分类">
      <template v-if="reviewing"
        ><button
          class="button"
          :class="tab === 'inbox' ? 'button-primary' : 'button-secondary'"
          @click="tab = 'inbox'"
        >
          待审批</button
        ><button
          class="button"
          :class="tab === 'history' ? 'button-primary' : 'button-secondary'"
          @click="tab = 'history'"
        >
          审批历史
        </button></template
      >
      <template v-else
        ><button
          class="button"
          :class="tab === 'draft' ? 'button-primary' : 'button-secondary'"
          @click="tab = 'draft'"
        >
          填写申请</button
        ><button
          class="button"
          :class="tab === 'mine' ? 'button-primary' : 'button-secondary'"
          @click="tab = 'mine'"
        >
          我的申请记录
        </button></template
      >
    </div>
    <form v-if="tab === 'draft'" class="approval-form" @submit.prevent="validate">
      <label
        >申请类型<select v-model="form.type">
          <option v-for="(label, value) in labels" :key="value" :value="value">{{ label }}</option>
        </select></label
      >
      <p class="muted small-text">时间统一按北京时间填写。</p>
      <label
        >{{ form.type === 'MISSING_PUNCH' ? '补卡时间' : '开始时间'
        }}<input v-model="form.startAt" type="datetime-local" required
      /></label>
      <label v-if="form.type !== 'MISSING_PUNCH'"
        >结束时间<input v-model="form.endAt" type="datetime-local" required
      /></label>
      <template v-else
        ><label
          >补卡方向<select v-model="form.punchType">
            <option value="IN">上班</option>
            <option value="OUT">下班</option>
          </select></label
        ><label
          >办公场所<select v-model="form.workplaceId" required>
            <option :value="null">请选择本人绑定场所</option>
            <option v-for="place in workplaces" :key="place.id" :value="place.id">
              {{ place.name }}
            </option>
          </select></label
        >
        <p v-if="!workplaces.length" class="muted small-text">
          尚未绑定有效办公场所，请联系管理员。
        </p></template
      >
      <label class="approval-wide"
        >申请事由<textarea
          v-model="form.reason"
          required
          maxlength="500"
          rows="4"
          placeholder="说明申请原因，最多500字"
        ></textarea>
      </label>
      <div v-if="previewError" class="notice notice-error approval-wide" role="alert">
        {{ previewError }}
      </div>
      <div v-if="preview" class="notice approval-wide" role="status">
        <div>
          <b>内容校验通过，尚未保存申请</b>
          <p>申请人：{{ preview.applicantName }} · 类型：{{ labels[preview.draft.type] }}</p>
          <p v-if="preview.draft.type !== 'MISSING_PUNCH'">
            申请时段 {{ preview.requestedMinutes }} 分钟，实际考勤待审批后核算。
          </p>
          <p>校验不会生成审批记录或修改考勤。</p>
        </div>
      </div>
      <div class="approval-actions approval-wide">
        <button
          class="button button-secondary"
          :disabled="loading || validating || !status?.validationAvailable || !profile?.companyId"
        >
          {{ validating ? '正在校验…' : '校验申请内容' }}</button
        ><button class="button button-primary" type="button" disabled>提交申请（尚未接入）</button>
      </div>
    </form>
    <div v-else class="approval-empty">
      <h2>
        {{
          tab === 'mine'
            ? '申请记录尚未接入'
            : tab === 'inbox'
              ? '待审批队列尚未接入'
              : '审批历史尚未接入'
        }}
      </h2>
      <p class="muted">当前无法读取真实申请记录。待申请保存与审批服务接通后开放此处。</p>
    </div>
  </section>
</template>

<style scoped>
.approval-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 22px 24px;
  border-bottom: 1px solid #edf0f5;
}
.approval-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  padding: 24px;
}
.approval-form label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-weight: 500;
}
.approval-form input,
.approval-form select,
.approval-form textarea {
  width: 100%;
  min-width: 0;
  padding: 11px 12px;
  border: 1px solid #ccd6e6;
  border-radius: 9px;
  font: inherit;
  background: white;
}
.approval-form textarea {
  resize: vertical;
}
.approval-wide {
  grid-column: 1 / -1;
}
.approval-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.approval-empty {
  padding: 46px 24px;
  text-align: center;
}
.approval-empty h2 {
  font-size: 18px;
}
@media (max-width: 600px) {
  .approval-form {
    grid-template-columns: 1fr;
    padding: 18px;
  }
  .approval-tabs {
    padding: 18px;
  }
}
</style>
