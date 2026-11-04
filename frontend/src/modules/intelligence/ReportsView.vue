<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import http from '../../api'
const parts = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
}).formatToParts(new Date())
const part = (type) => parts.find((p) => p.type === type).value
const today = `${part('year')}-${part('month')}-${part('day')}`
const form = ref({
  from: today.slice(0, 8) + '01',
  to: today,
  companyId: '',
  departmentId: '',
  userId: '',
  workplaceId: '',
})
const result = ref(null),
  error = ref(''),
  loading = ref(false),
  page = ref(1)
const labels = {
  NORMAL: '正常',
  LATE: '迟到',
  EARLY: '早退',
  LATE_AND_EARLY: '迟到及早退',
  ABSENT: '缺勤',
  MISSING_PUNCH: '缺卡',
  PENDING: '待结算',
  ON_LEAVE: '全天请假',
  REST_DAY: '休息日',
}
const params = () => Object.fromEntries(Object.entries(form.value).filter(([, v]) => v !== ''))
const options = ref({}),
  optionError = ref('')
onMounted(async () => {
  const paths = {
    companyId: '/organizations/companies',
    departmentId: '/organizations/departments',
    userId: '/employees',
    workplaceId: '/workplaces',
  }
  const values = await Promise.allSettled(
    Object.entries(paths).map(async ([key, path]) => [
      key,
      await http.get(path, { params: { page: 1, size: 100 } }),
    ]),
  )
  for (const value of values) {
    if (value.status === 'fulfilled') options.value[value.value[0]] = value.value[1]
    else optionError.value = '部分筛选建议加载失败，可刷新页面重试或直接输入 ID。'
  }
})
let generation = 0
watch(
  form,
  () => {
    generation++
    result.value = null
    error.value = ''
    page.value = 1
  },
  { deep: true },
)
async function load(target = 1) {
  const request = ++generation
  loading.value = true
  error.value = ''
  result.value = null
  try {
    const data = await http.get('/reports/attendance', {
      params: { ...params(), page: target, size: 20 },
    })
    if (request === generation) {
      result.value = data
      page.value = target
    }
  } catch (e) {
    if (request === generation) error.value = e.message
  } finally {
    loading.value = false
  }
}
const exportUrl = computed(() => '/api/reports/attendance/export?' + new URLSearchParams(params()))
const peak = computed(() =>
  Math.max(1, ...(result.value?.trend || []).map((d) => d.totals.workMinutes)),
)
const points = computed(() =>
  (result.value?.trend || [])
    .map(
      (d, i, all) =>
        `${30 + (i * 640) / Math.max(1, all.length - 1)},${160 - (d.totals.workMinutes / peak.value) * 130}`,
    )
    .join(' '),
)
const heatGroups = computed(() => {
  const groups = new Map()
  for (const p of result.value?.workplaces || []) {
    if (!groups.has(p.coordinateSystem)) groups.set(p.coordinateSystem, [])
    groups.get(p.coordinateSystem).push(p)
  }
  return [...groups].map(([system, places]) => {
    const minX = Math.min(...places.map((p) => p.longitude)),
      maxX = Math.max(...places.map((p) => p.longitude))
    const minY = Math.min(...places.map((p) => p.latitude)),
      maxY = Math.max(...places.map((p) => p.latitude))
    const weight = Math.max(1, ...places.map((p) => p.attendedDays))
    return {
      system,
      places: places.map((p) => ({
        ...p,
        x: minX === maxX ? 350 : 70 + ((p.longitude - minX) / (maxX - minX)) * 560,
        y: minY === maxY ? 100 : 160 - ((p.latitude - minY) / (maxY - minY)) * 110,
        radius: 10 + Math.sqrt(p.attendedDays / weight) * 24,
      })),
    }
  })
})
</script>

<template>
  <div class="report-page">
    <header>
      <p class="eyebrow">数据与来源</p>
      <h1>考勤统计报表</h1>
      <p>按北京时间业务日统计，使用正式排班、原始打卡和已生效审批调整。</p>
    </header>
    <section class="panel report-filters">
      <h2>查询范围</h2>
      <form @submit.prevent="load(1)">
        <label>开始日期<input v-model="form.from" type="date" required /></label
        ><label>结束日期<input v-model="form.to" type="date" required /></label>
        <label
          >公司 ID<input
            v-model="form.companyId"
            list="report-companyId"
            type="number"
            min="1"
            step="1"
            placeholder="授权范围内全部"
        /></label>
        <label
          >部门 ID<input
            v-model="form.departmentId"
            list="report-departmentId"
            type="number"
            min="1"
            step="1"
            placeholder="全部部门"
        /></label>
        <label
          >员工 ID<input
            v-model="form.userId"
            list="report-userId"
            type="number"
            min="1"
            step="1"
            placeholder="全部在职员工"
        /></label>
        <label
          >场所 ID<input
            v-model="form.workplaceId"
            list="report-workplaceId"
            type="number"
            min="1"
            step="1"
            placeholder="全部场所"
        /></label>
        <button class="button button-primary" type="submit" :disabled="loading">
          {{ loading ? '查询中…' : '查询报表' }}
        </button>
      </form>
      <datalist v-for="(data, key) in options" :id="'report-' + key" :key="key">
        <option
          v-for="item in data.items"
          :key="item.userId || item.id"
          :value="item.userId || item.id"
        >
          {{ item.displayName || item.name }} #{{ item.userId || item.id }}
        </option>
      </datalist>
      <p v-if="optionError" role="status">{{ optionError }}</p>
      <p>
        最多 94 天、100 名当前启用员工，超出范围会明确提示。筛选建议各列授权范围前 100
        项，可按名称选择或直接输入 ID；员工调动后按当前组织归属筛选。
      </p>
    </section>
    <p v-if="error" role="alert" class="report-error">{{ error }}</p>
    <p v-if="!result && !loading && !error" class="panel">
      请选择范围后查询。修改条件后需重新查询，未查询时不显示统计数字。
    </p>
    <template v-if="result">
      <div class="report-toolbar">
        <span
          >当前启用员工 {{ result.employeeCount }} 人 · {{ result.timeZone }} ·
          {{
            new Date(result.generatedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
          }}</span
        ><a class="button button-secondary" :href="exportUrl">导出当前范围 CSV</a>
      </div>
      <div class="report-cards">
        <section class="panel">
          <span>已结束应出勤人天</span><strong>{{ result.totals.expectedDays }}</strong
          ><small>排除休息日和全天请假</small>
        </section>
        <section class="panel">
          <span>完整打卡出勤率</span
          ><strong>{{
            result.totals.attendanceRate === null ? '不适用' : result.totals.attendanceRate + '%'
          }}</strong
          ><small
            >{{ result.totals.attendedDays }} / {{ result.totals.expectedDays }}，含迟到早退</small
          >
        </section>
        <section class="panel">
          <span>有效工时（分钟）</span><strong>{{ result.totals.workMinutes }}</strong
          ><small
            >请假 {{ result.totals.leaveMinutes }} · 加班 {{ result.totals.overtimeMinutes }}</small
          >
        </section>
        <section class="panel">
          <span>待结束／待结算人天</span><strong>{{ result.totals.pendingDays }}</strong
          ><small>未进入出勤率分母</small>
        </section>
      </div>
      <section class="panel">
        <h2>异常与请假</h2>
        <p>
          迟到 {{ result.totals.lateDays }} · 早退 {{ result.totals.earlyDays }} · 缺勤
          {{ result.totals.absentDays }} · 缺卡 {{ result.totals.missingPunchDays }} · 全天请假
          {{ result.totals.leaveDays }} · 休息日 {{ result.totals.restDays }} 人天
        </p>
        <p>
          同一天迟到且早退分别计数；部分请假仍属于应出勤人天。工时使用 B
          的实时结果，未结束班次的数据可能继续变化。
        </p>
      </section>
      <section class="panel">
        <h2>有效工时趋势</h2>
        <p v-if="!result.totals.scheduledDays">当前范围没有正式排班，不推算出勤或缺勤。</p>
        <svg
          v-else
          class="report-chart"
          viewBox="0 0 700 200"
          role="img"
          aria-label="按日有效工时趋势，详细数字见下方表格"
        >
          <path d="M30 20V160H670" fill="none" stroke="#ccd5df" />
          <polyline :points="points" fill="none" stroke="#2563eb" stroke-width="3" />
          <text x="30" y="190">{{ form.from }}</text>
          <text x="570" y="190">{{ form.to }}</text>
        </svg>
        <details>
          <summary>逐日统计数据</summary>
          <div class="report-table">
            <table>
              <thead>
                <tr>
                  <th>日期</th>
                  <th>应出勤人天</th>
                  <th>完整打卡</th>
                  <th>待结算</th>
                  <th>有效工时分钟</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in result.trend" :key="d.date">
                  <td>{{ d.date }}</td>
                  <td>{{ d.totals.expectedDays }}</td>
                  <td>{{ d.totals.attendedDays }}</td>
                  <td>{{ d.totals.pendingDays }}</td>
                  <td>{{ d.totals.workMinutes }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </details>
      </section>
      <section class="panel">
        <h2>场所分布与出勤热度</h2>
        <p>
          以办公场所登记坐标绘制相对分布，圆大小表示存在签到或签退的人天。同坐标系单独绘制；此图无地图底图，不代表员工实时位置或人流预测。
        </p>
        <p v-if="!heatGroups.length">当前范围没有场所数据。</p>
        <figure v-for="group in heatGroups" :key="group.system">
          <figcaption>{{ group.system }} · 经度向右、纬度向上</figcaption>
          <svg
            class="report-chart"
            viewBox="0 0 700 220"
            role="img"
            :aria-label="group.system + '场所出勤热度，坐标和权重见下方表格'"
          >
            <g v-for="p in group.places" :key="p.workplaceId">
              <circle
                :cx="p.x"
                :cy="p.y"
                :r="p.radius"
                fill="#2563eb"
                fill-opacity="0.25"
                stroke="#2563eb"
              >
                <title>{{ p.name }} · {{ p.attendedDays }} 人天</title>
              </circle>
              <text :x="p.x" :y="p.y + 48" text-anchor="middle">#{{ p.workplaceId }}</text>
            </g>
          </svg>
        </figure>
        <div class="report-table">
          <table>
            <thead>
              <tr>
                <th>场所</th>
                <th>坐标系</th>
                <th>经度／纬度</th>
                <th>排班人天</th>
                <th>有打卡人天</th>
                <th>有效工时分钟</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in result.workplaces" :key="p.workplaceId">
                <td>{{ p.name }} #{{ p.workplaceId }}</td>
                <td>{{ p.coordinateSystem }}</td>
                <td>{{ p.longitude }} / {{ p.latitude }}</td>
                <td>{{ p.scheduledDays }}</td>
                <td>{{ p.attendedDays }}</td>
                <td>{{ p.workMinutes }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <section class="panel">
        <h2>可回查日记录</h2>
        <p>
          来源 {{ result.source }}；审批编号对应 A
          的申请审计。导出会重新读取当前数据，审批或打卡变化后请刷新报表。
        </p>
        <div class="report-table">
          <table>
            <thead>
              <tr>
                <th>员工</th>
                <th>日期／场所</th>
                <th>状态</th>
                <th>签到／签退</th>
                <th>有效／请假／加班分钟</th>
                <th>审批来源ID</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in result.records.items" :key="r.userId + ':' + r.date">
                <td>
                  {{ r.employeeName }} #{{ r.userId
                  }}<small>{{ r.employeeNo }} · {{ r.departmentName || '未分配部门' }}</small>
                </td>
                <td>
                  {{ r.date }}<small>{{ r.workplaceName }} #{{ r.workplaceId }}</small>
                </td>
                <td>
                  {{ labels[r.status] || r.status }}<small v-if="!r.finalized">班次未结束</small>
                </td>
                <td>
                  {{ r.checkIn || '—' }}<small>{{ r.checkOut || '—' }}</small>
                </td>
                <td>{{ r.workMinutes }} / {{ r.leaveMinutes }} / {{ r.overtimeMinutes }}</td>
                <td>{{ r.approvalIds.length ? r.approvalIds.join(', ') : '无审批调整' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="!result.records.total">没有符合条件的日记录。</p>
        <div class="report-pagination">
          <button
            class="button button-secondary"
            :disabled="page === 1 || loading"
            @click="load(page - 1)"
          >
            上一页</button
          ><span>第 {{ page }} 页 · 共 {{ result.records.total }} 条</span
          ><button
            class="button button-secondary"
            :disabled="page * 20 >= result.records.total || loading"
            @click="load(page + 1)"
          >
            下一页
          </button>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.report-page {
  display: grid;
  gap: 20px;
  min-width: 0;
}
.report-page h1 {
  margin: 4px 0 10px;
}
.report-page p {
  color: #64748b;
  line-height: 1.7;
}
.report-page .panel {
  padding: 24px;
  min-width: 0;
}
.report-page h2 {
  font-size: 18px;
  margin: 0 0 16px;
}
.report-filters form {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
.report-filters label {
  display: grid;
  gap: 8px;
  font-size: 14px;
}
.report-filters input {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  border: 1px solid #ccd5df;
  padding: 10px;
  border-radius: 8px;
  background: white;
}
.report-toolbar,
.report-pagination {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
}
.report-toolbar {
  font-size: 13px;
  color: #64748b;
  overflow-wrap: anywhere;
}
.report-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
.report-cards span,
.report-cards small {
  display: block;
  color: #64748b;
  font-size: 13px;
}
.report-cards strong {
  display: block;
  font-size: 30px;
  margin: 14px 0;
}
.report-chart {
  display: block;
  width: 100%;
  height: auto;
  min-height: 130px;
  max-height: 260px;
  background: #f8fafc;
  border-radius: 12px;
}
.report-chart text {
  font-size: 12px;
  fill: #475569;
}
.report-table {
  overflow: auto;
  max-width: 100%;
}
.report-table table {
  border-collapse: collapse;
  width: 100%;
  font-size: 13px;
  white-space: nowrap;
}
.report-table td,
.report-table th {
  text-align: left;
  padding: 12px;
  border-bottom: 1px solid #e2e8f0;
}
.report-table small {
  display: block;
  color: #64748b;
  margin-top: 5px;
}
.report-error {
  color: #b91c1c !important;
}
.report-page figure {
  margin: 16px 0;
}
.report-page summary {
  cursor: pointer;
  padding: 12px 0;
}
.report-pagination {
  margin-top: 16px;
}
@media (max-width: 900px) {
  .report-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .report-filters form {
    grid-template-columns: 1fr;
  }
  .report-page .panel {
    padding: 16px;
  }
  .report-cards {
    gap: 10px;
  }
  .report-cards strong {
    font-size: 24px;
  }
}
</style>
