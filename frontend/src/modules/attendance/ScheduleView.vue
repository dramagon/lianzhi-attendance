<script setup>
import { computed, ref, reactive, onMounted } from 'vue'
import http from '../../api'
import { useAuthStore } from '../../stores/auth'
import { attendancePost, businessDate, statusLabels } from './api'
const auth = useAuthStore(),
  manager = computed(() => ['ADMIN', 'HR'].includes(auth.user?.role))
const employees = ref([]),
  places = ref([]),
  schedules = ref([]),
  days = ref([]),
  error = ref(''),
  message = ref(''),
  busy = ref(false),
  query = ref('')
const date = businessDate(),
  filter = reactive({ from: date, to: date }),
  form = reactive({
    userId: auth.user?.id,
    workplaceId: '',
    businessDate: date,
    start: '09:00',
    end: '18:00',
    breakStart: '12:00',
    breakEnd: '13:00',
    graceMinutes: 5,
    holiday: false,
    workdays: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'],
  })
const week = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
async function people() {
  try {
    employees.value = (
      await http.get('/employees', { params: { query: query.value, page: 1, size: 100 } })
    ).items
  } catch (e) {
    error.value = e.message
  }
}
async function employeeChanged() {
  error.value = ''
  places.value = []
  form.workplaceId = ''
  try {
    places.value = (await http.get(`/employees/${form.userId}/workplaces`)).filter(
      (p) => p.enabled && p.coordinateSystem === 'WGS84',
    )
    form.workplaceId = places.value[0]?.id || ''
    await load()
  } catch (e) {
    error.value = e.message
  }
}
async function load() {
  busy.value = true
  error.value = ''
  try {
    const params = { ...filter, ...(manager.value ? { userId: form.userId } : {}) }
    const [s, d] = await Promise.all([
      http.get(manager.value ? '/attendance/schedules' : '/attendance/me/schedules', { params }),
      http.get(manager.value ? '/attendance/days' : '/attendance/me/days', { params }),
    ])
    schedules.value = s
    days.value = d
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
async function save() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await attendancePost('/attendance/schedules', {
      userId: Number(form.userId),
      workplaceId: Number(form.workplaceId),
      businessDate: form.businessDate,
      holiday: form.holiday,
      rule: {
        start: form.start,
        end: form.end,
        breakStart: form.breakStart || null,
        breakEnd: form.breakEnd || null,
        graceMinutes: Number(form.graceMinutes),
        workdays: form.workdays,
      },
    })
    message.value = '正式排班已保存，可在所选日期与场所打卡。'
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
onMounted(async () => {
  if (manager.value) {
    await people()
    await employeeChanged()
  } else await load()
})
</script>
<template>
  <div class="planning">
    <div class="page-heading">
      <div>
        <span class="eyebrow">联智考勤 · 正式业务</span>
        <h1>排班与日考勤</h1>
        <p>先安排真实班次，再查看打卡与审批调整后的统一结果。</p>
      </div>
      <RouterLink class="button button-secondary" to="/attendance">返回打卡工作台</RouterLink>
    </div>
    <div v-if="error" class="notice notice-error" role="alert">{{ error }}</div>
    <div v-if="message" class="notice" role="status">{{ message }}</div>
    <section v-if="manager" class="panel block">
      <h2>管理排班</h2>
      <div class="fields">
        <label
          >搜索员工<input
            aria-label="搜索员工"
            v-model="query"
            placeholder="姓名、账号或工号"
          /><button type="button" class="text-button" @click="people">搜索</button></label
        ><label
          >员工<select aria-label="员工" v-model="form.userId" @change="employeeChanged">
            <option v-for="person in employees" :key="person.userId" :value="person.userId">
              {{ person.displayName }} · {{ person.employeeNo }}
            </option>
          </select></label
        >
      </div>
      <form @submit.prevent="save">
        <div class="fields">
          <label
            >业务日期<input v-model="form.businessDate" type="date" :min="date" required /></label
          ><label
            >有效绑定场所（WGS84）<select
              aria-label="有效绑定场所（WGS84）"
              v-model="form.workplaceId"
              required
            >
              <option value="">请选择场所</option>
              <option v-for="p in places" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select></label
          ><label>上班时间<input v-model="form.start" type="time" required /></label
          ><label>下班时间<input v-model="form.end" type="time" required /></label
          ><label>休息开始<input v-model="form.breakStart" type="time" /></label
          ><label>休息结束<input v-model="form.breakEnd" type="time" /></label
          ><label
            >迟到宽限（分钟）<input
              v-model="form.graceMinutes"
              type="number"
              min="0"
              max="120"
              required
          /></label>
        </div>
        <fieldset>
          <legend>每周工作日</legend>
          <label v-for="(day, i) in week" :key="day"
            ><input v-model="form.workdays" type="checkbox" :value="day" />周{{
              '一二三四五六日'[i]
            }}</label
          >
        </fieldset>
        <label class="check"><input v-model="form.holiday" type="checkbox" />本日期为休息日</label>
        <p>跨天班次归开始日；已有打卡的班次不可改写。可更新尚无事件的当日或未来排班。</p>
        <button class="button button-primary" :disabled="busy || !form.workplaceId">
          {{ busy ? '保存中…' : '保存正式排班' }}
        </button>
      </form>
    </section>
    <section class="panel block">
      <h2>{{ manager ? '所选员工' : '我的' }}排班与日记录</h2>
      <form class="fields" @submit.prevent="load">
        <label>开始日期<input v-model="filter.from" type="date" required /></label
        ><label>结束日期<input v-model="filter.to" type="date" :min="filter.from" required /></label
        ><button class="button button-secondary" :disabled="busy">查询</button>
      </form>
      <p v-if="busy" role="status">查询中…</p>
      <p v-else-if="!schedules.length">所选范围没有正式排班，不推断为缺勤。</p>
      <div v-else class="scroll">
        <table>
          <thead>
            <tr>
              <th>日期</th>
              <th>班次</th>
              <th>状态</th>
              <th>有效工时</th>
              <th>请假</th>
              <th>实际加班</th>
              <th>审批来源</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(s, i) in schedules" :key="s.businessDate">
              <td>{{ s.businessDate }}</td>
              <td>{{ s.rule.start }}–{{ s.rule.end }}</td>
              <td>{{ statusLabels[days[i]?.attendance.status] || days[i]?.attendance.status }}</td>
              <td>{{ days[i]?.attendance.workMinutes }} 分</td>
              <td>{{ days[i]?.leaveMinutes }} 分</td>
              <td>{{ days[i]?.overtimeMinutes }} 分</td>
              <td>{{ days[i]?.approvalIds.join('、') || '无' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p>
        日记录由正式班次、原始打卡和已通过审批调整计算，查询范围最多94天；无事件且班次未结束时保持待结算。
      </p>
    </section>
  </div>
</template>
<style scoped>
.block {
  padding: 25px;
  margin-bottom: 24px;
}
.fields {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  align-items: end;
  margin: 20px 0;
}
.fields > label {
  flex: 1;
  min-width: 160px;
  display: flex;
  flex-direction: column;
  gap: 9px;
  font-size: 12px;
  color: #6f8099;
}
.fields input,
.fields select {
  padding: 11px;
  border: 1px solid #dfe6f0;
  border-radius: 7px;
  width: 100%;
  font: inherit;
  color: #344763;
}
p {
  font-size: 12px;
  line-height: 1.9;
  color: #8492a6;
  margin: 15px 0;
}
fieldset {
  display: flex;
  gap: 15px;
  flex-wrap: wrap;
  border: 0;
  padding: 0;
  margin: 20px 0;
}
fieldset legend {
  font-size: 12px;
  color: #74849a;
  margin-bottom: 10px;
}
fieldset label,
.check {
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 5px;
}
input[type='checkbox'] {
  accent-color: #2563eb;
}
.scroll {
  overflow: auto;
}
td,
th {
  white-space: nowrap;
}
@media (max-width: 600px) {
  .block {
    padding: 18px;
  }
  .fields {
    gap: 12px;
  }
  .fields > label {
    min-width: 125px;
  }
}
</style>
