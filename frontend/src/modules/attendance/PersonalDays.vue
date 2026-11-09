<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import http from '../../api'
import { useAuthStore } from '../../stores/auth'
import { businessDate, statusLabels } from './api'

const props = defineProps({ available: Boolean })
const auth = useAuthStore()
const month = ref(businessDate().slice(0, 7)),
  days = ref(null),
  selected = ref(''),
  loading = ref(false),
  error = ref('')
let revision = 0,
  disposed = false
const validMonth = computed(() => /^(19\d{2}|[2-9]\d{3})-(0[1-9]|1[0-2])$/.test(month.value))
const count = computed(() =>
  validMonth.value
    ? new Date(
        Date.UTC(Number(month.value.slice(0, 4)), Number(month.value.slice(5)), 0),
      ).getUTCDate()
    : 0,
)
const offset = computed(() =>
  validMonth.value ? (new Date(`${month.value}-01T00:00:00Z`).getUTCDay() + 6) % 7 : 0,
)
const byDate = computed(() => new Map((days.value || []).map((day) => [day.businessDate, day])))
const detail = computed(() => byDate.value.get(selected.value))
const totals = computed(() =>
  (days.value || []).reduce(
    (sum, day) => ({
      work: sum.work + day.attendance.workMinutes,
      leave: sum.leave + day.leaveMinutes,
      overtime: sum.overtime + day.overtimeMinutes,
      pending: sum.pending + Number(day.attendance.status === 'PENDING'),
    }),
    { work: 0, leave: 0, overtime: 0, pending: 0 },
  ),
)
const dateFor = (day) => `${month.value}-${String(day).padStart(2, '0')}`
const label = (date) =>
  statusLabels[byDate.value.get(date)?.attendance.status] ||
  (byDate.value.has(date) ? '未知状态' : '无排班记录')
function move(delta) {
  if (!validMonth.value) return
  const date = new Date(
    Date.UTC(Number(month.value.slice(0, 4)), Number(month.value.slice(5)) - 1 + delta, 1),
  )
  month.value = date.toISOString().slice(0, 7)
}
async function load() {
  const current = ++revision,
    queryMonth = month.value
  days.value = null
  error.value = ''
  loading.value = false
  selected.value = ''
  if (!props.available || !auth.user) return
  if (!validMonth.value) {
    error.value = '请选择1900至9999年之间的有效月份'
    return
  }
  loading.value = true
  try {
    const result = await http.get('/attendance/me/days', {
      params: { from: `${queryMonth}-01`, to: `${queryMonth}-${count.value}` },
    })
    if (disposed || current !== revision) return
    days.value = result.filter((day) => day.businessDate.startsWith(`${queryMonth}-`))
    const today = businessDate()
    selected.value = today.startsWith(`${queryMonth}-`)
      ? today
      : days.value[0]?.businessDate || `${queryMonth}-01`
  } catch (e) {
    if (!disposed && current === revision) error.value = e.message
  } finally {
    if (!disposed && current === revision) loading.value = false
  }
}
function download() {
  if (loading.value || error.value || !days.value?.length) return
  const cell = (value) => {
    let text = String(value ?? '')
    if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`
    return `"${text.replaceAll('"', '""')}"`
  }
  const rows = [
    [
      '业务日期',
      '状态',
      '场所编号',
      '签到（北京时间）',
      '签退（北京时间）',
      '有效工时分钟',
      '获批请假分钟',
      '获批加班分钟',
      '审批来源ID',
    ],
    ...days.value.map((day) => [
      day.businessDate,
      statusLabels[day.attendance.status] || day.attendance.status,
      day.workplaceId,
      day.checkIn,
      day.checkOut,
      day.attendance.workMinutes,
      day.leaveMinutes,
      day.overtimeMinutes,
      day.approvalIds.join(';'),
    ]),
  ]
  const url = URL.createObjectURL(
    new Blob(['\uFEFF' + rows.map((row) => row.map(cell).join(',')).join('\r\n')], {
      type: 'text/csv;charset=utf-8',
    }),
  )
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `我的考勤-${month.value}.csv`
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
watch(() => [props.available, auth.user?.id, month.value], load, { immediate: true, flush: 'sync' })
onUnmounted(() => {
  disposed = true
  revision++
})
</script>
<template>
  <section class="b-card personal-days" aria-label="个人月度考勤">
    <div class="month-heading">
      <h2>个人月度考勤</h2>
      <span>仅本人 · 北京时间</span>
    </div>
    <div class="month-controls">
      <label>考勤月份<input v-model="month" type="month" min="1900-01" max="9999-12" /></label>
      <button
        class="button button-secondary"
        :disabled="!validMonth || month === '1900-01'"
        @click="move(-1)"
      >
        上个月
      </button>
      <button
        class="button button-secondary"
        :disabled="!validMonth || month === '9999-12'"
        @click="move(1)"
      >
        下个月
      </button>
      <button class="button button-secondary" :disabled="!available || loading" @click="load">
        刷新月度考勤
      </button>
      <button
        class="button button-secondary"
        :disabled="loading || !!error || !days?.length"
        @click="download"
      >
        导出本人月度CSV
      </button>
    </div>
    <p>
      按正式排班与审批回写计算；无排班记录不等于缺勤，待结算不提前判定缺卡。快速上下班可能为0分钟。
    </p>
    <p v-if="!available">日考勤服务尚未就绪，请稍后刷新。</p>
    <p v-else-if="loading" role="status">正在读取 {{ month }} 的本人日记录…</p>
    <div v-else-if="error" role="alert" class="notice notice-error">
      {{ error }}，可点击“刷新月度考勤”重试。
    </div>
    <template v-else-if="days">
      <dl class="month-summary" aria-label="本月记录汇总">
        <div>
          <dt>排班日记录</dt>
          <dd>{{ days.length }} 天</dd>
        </div>
        <div>
          <dt>有效工时</dt>
          <dd>{{ totals.work }} 分钟</dd>
        </div>
        <div>
          <dt>获批请假</dt>
          <dd>{{ totals.leave }} 分钟</dd>
        </div>
        <div>
          <dt>获批加班</dt>
          <dd>{{ totals.overtime }} 分钟</dd>
        </div>
        <div>
          <dt>待结算</dt>
          <dd>{{ totals.pending }} 天</dd>
        </div>
      </dl>
      <p v-if="!days.length">本月没有正式排班日记录，不据此推断缺勤。</p>
      <div class="month-calendar" aria-label="日考勤日历">
        <span
          v-for="name in ['一', '二', '三', '四', '五', '六', '日']"
          :key="name"
          class="weekday"
          >{{ name }}</span
        >
        <span v-for="blank in offset" :key="`blank-${blank}`" aria-hidden="true"></span>
        <button
          v-for="day in count"
          :key="day"
          :aria-label="`${dateFor(day)} ${label(dateFor(day))}`"
          :aria-pressed="selected === dateFor(day)"
          :class="{ recorded: byDate.has(dateFor(day)) }"
          @click="selected = dateFor(day)"
        >
          <b>{{ day }}</b
          ><span>{{ label(dateFor(day)) }}</span>
        </button>
      </div>
      <div class="month-detail" aria-label="选中日期详情" aria-live="polite">
        <h3>{{ selected }} · {{ label(selected) }}</h3>
        <template v-if="detail">
          <p>
            场所编号 {{ detail.workplaceId }} · 签到
            {{ detail.checkIn?.replace('T', ' ') || '暂无' }} · 签退
            {{ detail.checkOut?.replace('T', ' ') || '暂无' }}
          </p>
          <p>
            有效工时 {{ detail.attendance.workMinutes }} 分钟 · 获批请假
            {{ detail.leaveMinutes }} 分钟 · 获批加班 {{ detail.overtimeMinutes }} 分钟
          </p>
          <p>
            迟到 {{ detail.attendance.lateMinutes }} 分钟 · 早退
            {{ detail.attendance.earlyMinutes }} 分钟{{
              detail.attendance.overnight ? ' · 跨天班次，按开始日归属' : ''
            }}
          </p>
          <p>审批来源：{{ detail.approvalIds.join('、') || '无审批调整' }}</p>
          <p v-for="note in detail.attendance.notes" :key="note">{{ note }}</p>
          <RouterLink
            v-if="
              ['MISSING_PUNCH', 'ABSENT', 'LATE', 'EARLY', 'LATE_AND_EARLY'].includes(
                detail.attendance.status,
              )
            "
            class="text-link"
            to="/approvals"
            >前往申请页核对或补卡</RouterLink
          >
          <p>日结果可包含已批准的补卡或请假；下方原始事件保留原记录，不会被审批覆盖。</p>
        </template>
        <p v-else>该日没有可查询的正式排班日记录；如有疑问请联系管理人员核对排班。</p>
      </div>
    </template>
  </section>
</template>
<style scoped>
.personal-days {
  margin-bottom: 24px;
}
.month-heading,
.month-controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.month-heading {
  justify-content: space-between;
}
h2 {
  font-size: 18px;
}
h3 {
  font-size: 15px;
}
p,
dt,
.month-heading span {
  font-size: 12px;
  color: #65758e;
  line-height: 1.8;
}
.month-controls label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
input {
  min-width: 0;
  padding: 8px;
  border: 1px solid #dce4ef;
  border-radius: 8px;
  font: inherit;
}
.month-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.month-summary div {
  flex: 1 1 110px;
  padding: 12px;
  background: #f5f8ff;
  border-radius: 10px;
}
dd {
  margin: 6px 0 0;
  font-size: 16px;
  font-weight: 600;
}
.month-calendar {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 6px;
}
.weekday {
  text-align: center;
  padding: 8px;
  font-size: 12px;
}
.month-calendar button {
  min-width: 0;
  padding: 10px 3px;
  border: 1px solid #e4eaf3;
  background: white;
  border-radius: 8px;
  cursor: pointer;
  color: #65758e;
}
.month-calendar button b,
.month-calendar button span {
  display: block;
}
.month-calendar button span {
  font-size: 11px;
  margin-top: 6px;
  overflow-wrap: anywhere;
}
.month-calendar .recorded {
  background: #f0f5ff;
  color: #214eaf;
}
.month-calendar button[aria-pressed='true'] {
  outline: 2px solid #2864ed;
  outline-offset: 1px;
}
.month-detail {
  margin-top: 18px;
  padding: 16px;
  border: 1px solid #e4eaf3;
  border-radius: 10px;
  overflow-wrap: anywhere;
}
@media (max-width: 500px) {
  .month-calendar {
    gap: 4px;
  }
  .month-calendar button {
    padding: 8px 1px;
  }
  .month-controls {
    align-items: stretch;
  }
  .month-controls label {
    flex-basis: 100%;
  }
}
</style>
