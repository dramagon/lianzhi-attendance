<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import QRCode from 'qrcode'
import jsQR from 'jsqr'
import http, { write } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { businessDate, statusLabels, timeText } from './api'
const auth = useAuthStore()
const info = ref(null),
  scenario = ref(null),
  busy = ref(false),
  error = ref(''),
  message = ref(''),
  qrImage = ref(''),
  records = ref([]),
  days = ref([]),
  approval = ref(null),
  automatic = ref(false),
  stopRequested = ref(false),
  currentStep = ref(''),
  storageNotice = ref(''),
  scenarioReady = ref(false)
const savedKey = 'lianzhi.presentation.v2'
const scenarioFields = [
  'date',
  'tomorrow',
  'username',
  'userId',
  'workplaceId',
  'approvalId',
  'approvalKey',
  'inRequest',
  'outRequest',
]
function scenarioData(value) {
  return Object.fromEntries(scenarioFields.map((key) => [key, value[key]]))
}
const password = 'Lianzhi@2026' // Public synthetic identities, only in the explicitly enabled isolated classroom app.
const demoLocation = { latitude: 31.2, longitude: 121.5, coordinateSystem: 'WGS84' }
let disposed = false
const signedIn = computed(() => records.value.some((item) => item.type === 'IN'))
const signedOut = computed(() => records.value.some((item) => item.type === 'OUT'))
const leaveDay = computed(() =>
  days.value.find((item) => item.businessDate === scenario.value?.tomorrow),
)
const canRun = computed(() => info.value?.classroom === true && !busy.value)
const canClick = computed(() => canRun.value && !automatic.value)
const milestones = computed(() => [
  { label: '档案与排班', done: scenarioReady.value },
  { label: '上班记录', done: signedIn.value },
  { label: '下班记录', done: signedOut.value },
  { label: '员工申请', done: !!approval.value?.entry?.request?.id },
  {
    label: '审批回写',
    done:
      approval.value?.entry?.request?.state === 'APPROVED' &&
      leaveDay.value?.leaveMinutes === 480 &&
      leaveDay.value.approvalIds.includes(scenario.value?.approvalId),
  },
])
function nextDate(date) {
  const day = new Date(`${date}T12:00:00+08:00`)
  day.setUTCDate(day.getUTCDate() + 1)
  return businessDate(day)
}
function remember() {
  const value = JSON.stringify(scenarioData(scenario.value))
  try {
    localStorage.setItem(savedKey, value)
    storageNotice.value = ''
    return
  } catch {
    /* Try tab storage next. */
  }
  try {
    sessionStorage.setItem(savedKey, value)
  } catch {
    /* Current page still works without storage. */
  }
  storageNotice.value = '浏览器未允许长期保存进度，本页仍可演示；关闭浏览器后可能需要重新准备。'
}
function savedScenario() {
  for (const storage of ['localStorage', 'sessionStorage']) {
    try {
      const saved = JSON.parse(window[storage].getItem(savedKey))
      if (saved) return saved
    } catch {
      /* Disabled or malformed browser storage must not disable the app. */
    }
  }
  // Migrate the previous version's current-tab progress when available.
  try {
    return JSON.parse(sessionStorage.getItem(`lianzhi.presentation.${info.value.instance}`))
  } catch {
    return null
  }
}
function validScenario(value) {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
  return (
    value &&
    /^show[0-9]{13}$/.test(value.username) &&
    Number.isSafeInteger(value.userId) &&
    value.userId > 0 &&
    Number.isSafeInteger(value.workplaceId) &&
    value.workplaceId > 0 &&
    /^\d{4}-\d{2}-\d{2}$/.test(value.date) &&
    Number.isFinite(Date.parse(`${value.date}T12:00:00+08:00`)) &&
    value.tomorrow === nextDate(value.date) &&
    (value.approvalId === null ||
      (Number.isSafeInteger(value.approvalId) && value.approvalId > 0)) &&
    [value.approvalKey, value.inRequest, value.outRequest].every((key) => uuid.test(key))
  )
}
function clearResults() {
  records.value = []
  days.value = []
  approval.value = null
  qrImage.value = ''
  scenarioReady.value = false
}
async function asUser(username) {
  if (disposed) throw new Error('已退出演示台，请重新进入后核对记录')
  if (auth.user?.username !== username) await auth.signIn(username, password)
}
async function action(task) {
  if (!canRun.value || disposed) return false
  busy.value = true
  error.value = message.value = ''
  try {
    await task()
    return !disposed
  } catch (e) {
    if (!disposed) error.value = e.message
    return false
  } finally {
    if (!disposed) busy.value = false
  }
}
async function refresh() {
  if (!scenario.value) return
  const context = scenario.value
  scenarioReady.value = false
  await asUser(context.username)
  const identity = await http.get('/employees/me')
  if (
    identity.userId !== context.userId ||
    identity.username !== context.username ||
    identity.employeeNo !== `SHOW${context.username.slice(4)}`
  )
    throw new Error('保存进度与当前演示身份不匹配，请重新准备；没有修改已有记录。')
  const workplaces = await http.get('/attendance/me/workplaces')
  if (!workplaces.some((place) => place.id === context.workplaceId))
    throw new Error('保存的演示场所已不可用，请重新准备；历史记录仍保留。')
  const data = await Promise.all([
    http.get('/attendance/me/records', {
      params: { from: context.date, to: context.date, page: 1, size: 20 },
    }),
    http.get('/attendance/me/days', {
      params: { from: context.date, to: context.tomorrow },
    }),
    context.approvalId ? http.get(`/approvals/${context.approvalId}`) : Promise.resolve(null),
  ])
  if (disposed || context !== scenario.value) return
  const owner = data[2]?.entry?.request
  if (owner && owner.applicantId !== context.userId)
    throw new Error('审批来源与本轮员工不匹配，请重新准备。')
  records.value = data[0].items
  days.value = data[1]
  approval.value = data[2]
  scenarioReady.value = true
}
async function prepare() {
  return action(async () => {
    clearResults()
    scenario.value = null
    await asUser('admin')
    const employee = await http.get('/employees/me'),
      status = await http.get('/attendance/status')
    if (!employee.companyId || !status.available)
      throw new Error('演示公司或考勤服务未就绪，请使用独立展示包启动')
    const stamp = Date.now().toString(),
      name = `课堂演示${stamp}`
    const date = businessDate(new Date(status.serverTime))
    await write('post', '/workplaces', {
      companyId: employee.companyId,
      name,
      ...demoLocation,
      radiusMeters: 200,
      enabled: true,
    })
    const places = await http.get('/workplaces', {
      params: { companyId: employee.companyId, page: 1, size: 100 },
    })
    let place = places.items.find((item) => item.name === name)
    for (let page = Math.ceil(places.total / 100); !place && page > 1; page--) {
      const more = await http.get('/workplaces', {
        params: { companyId: employee.companyId, page, size: 100 },
      })
      place = more.items.find((item) => item.name === name)
    }
    if (!place) throw new Error('演示场所未找到，请重新准备')
    const person = await write('post', '/employees', {
      username: `show${stamp}`,
      displayName: '课堂合成员工',
      employeeNo: `SHOW${stamp}`,
      role: 'EMPLOYEE',
      companyId: employee.companyId,
      departmentId: null,
      enabled: true,
      password,
      workplaceIds: [place.id],
    })
    const tomorrow = nextDate(date),
      weekdays = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
    for (const [day, start, end] of [
      [date, '00:00', '23:59'],
      [tomorrow, '09:00', '18:00'],
    ]) {
      await write('post', '/attendance/schedules', {
        userId: person.userId,
        businessDate: day,
        workplaceId: place.id,
        rule: {
          start,
          end,
          breakStart: '12:00',
          breakEnd: '13:00',
          graceMinutes: 5,
          workdays: weekdays,
        },
        holiday: false,
      })
    }
    scenario.value = {
      date,
      tomorrow,
      username: person.username,
      userId: person.userId,
      workplaceId: place.id,
      approvalId: null,
      approvalKey: crypto.randomUUID(),
      inRequest: crypto.randomUUID(),
      outRequest: crypto.randomUUID(),
    }
    remember()
    approval.value = null
    qrImage.value = ''
    await refresh()
    message.value = '已创建合成员工、场所及今明两天排班。尚未生成任何打卡或审批成功记录。'
  })
}
async function punch(type) {
  return action(async () => {
    if (!scenarioReady.value) throw new Error('请先恢复进度或重新读取真实结果')
    const context = scenario.value
    await asUser('admin')
    const challenge = await write(
      'post',
      `/attendance/workplaces/${context.workplaceId}/challenge`,
      {},
    )
    const canvas = document.createElement('canvas')
    await QRCode.toCanvas(canvas, challenge.token, { width: 400, margin: 3 })
    qrImage.value = canvas.toDataURL('image/png')
    const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height)
    const token = jsQR(pixels.data, pixels.width, pixels.height)?.data
    canvas.width = canvas.height = 0
    if (token !== challenge.token) throw new Error('二维码图片解码失败，未提交打卡')
    await asUser(context.username)
    const requestId = type === 'IN' ? context.inRequest : context.outRequest
    try {
      await write('post', '/attendance/me/punches', {
        type,
        token,
        requestId,
        location: demoLocation,
        accuracyMeters: 10,
      })
    } catch (e) {
      // Resolve lost responses without fabricating success or automatically writing another event.
      const receipt = await http.get(`/attendance/me/punches/requests/${requestId}`)
      if (!receipt.found) throw e
    }
    await refresh()
    message.value = `服务器已确认${type === 'IN' ? '上班' : '下班'}记录；二维码图片由jsQR实际解码，定位使用页面标注的演示坐标。`
  })
}
async function requestLeave() {
  return action(async () => {
    if (!scenarioReady.value) throw new Error('请先恢复进度或重新读取真实结果')
    const context = scenario.value
    await asUser(context.username)
    const entry = await write('post', '/approvals', {
      requestKey: context.approvalKey,
      draft: {
        type: 'LEAVE',
        startAt: `${context.tomorrow}T09:00:00`,
        endAt: `${context.tomorrow}T18:00:00`,
        punchType: null,
        workplaceId: null,
        reason: '课堂展示用合成请假申请',
      },
    })
    context.approvalId = entry.request.id
    remember()
    await refresh()
    message.value = '真实申请已提交，当前等待管理人员审批；提交不等于批准。'
  })
}
async function approve() {
  return action(async () => {
    if (!scenarioReady.value) throw new Error('请先恢复进度或重新读取真实结果')
    await asUser('admin')
    const detail = await http.get(`/approvals/${scenario.value.approvalId}`)
    if (detail.entry.request.state === 'PENDING')
      await write('post', `/approvals/${scenario.value.approvalId}/actions`, {
        action: 'APPROVE',
        expectedVersion: detail.entry.request.version,
        note: '课堂展示审批',
      })
    await refresh()
    message.value = '已读取服务端审批及日考勤结果，审批来源可追溯。'
  })
}
async function restore() {
  return action(async () => {
    const saved = savedScenario()
    if (!saved) {
      message.value = '没有保存的演示进度，可以准备新一轮展示。'
      return
    }
    if (!validScenario(saved)) throw new Error('保存进度格式无效，请重新准备；不会删除后台记录。')
    const status = await http.get('/attendance/status')
    if (saved.date !== businessDate(new Date(status.serverTime))) {
      message.value = '上次演示不在今天，历史记录仍保留；请准备今天的新一轮展示。'
      return
    }
    clearResults()
    scenario.value = scenarioData(saved)
    if (!(await actionRestore())) return
    remember()
    message.value = '已从后台核对并恢复上次演示，未重新提交打卡或申请。'
  })
}
async function actionRestore() {
  try {
    await refresh()
    return true
  } catch (e) {
    clearResults()
    if ([401, 403, 404].includes(e.response?.status)) {
      scenario.value = null
      throw new Error('上次进度在当前演示库中不可用，请重新准备；后台历史未被删除。')
    }
    throw e
  }
}
async function playAll() {
  if (!canClick.value) return
  automatic.value = true
  stopRequested.value = false
  const tasks = [
    ['准备档案与排班', prepare],
    ['识别二维码并上班', () => punch('IN')],
    ['识别二维码并下班', () => punch('OUT')],
    ['员工提交请假', requestLeave],
    ['管理员审批及回写', approve],
  ]
  try {
    for (const [label, task] of tasks) {
      if (disposed || stopRequested.value) break
      currentStep.value = label
      if (!(await task())) {
        stopRequested.value = true
        break
      }
      await new Promise((resolve) => setTimeout(resolve, 800))
    }
    if (!disposed) {
      currentStep.value = ''
      if (stopRequested.value)
        message.value = '自动演示已暂停，已完成的记录保留。请核对后台结果后继续。'
      else if (milestones.value.every((step) => step.done))
        message.value = '完整演示已完成：两条打卡记录、审批通过及480分钟请假均已从后台核对。'
    }
  } finally {
    if (!disposed) automatic.value = false
  }
}
onMounted(async () => {
  try {
    info.value = await http.get('/attendance/presentation')
    await restore()
  } catch {
    error.value = '此页面只在独立课堂展示包中开放。请运行展示包的启动脚本，再以演示管理员登录。'
  }
})
onUnmounted(() => {
  disposed = true
  stopRequested.value = true
})
</script>
<template>
  <div class="presentation">
    <div class="page-heading">
      <div>
        <span class="eyebrow">课堂展示 · 独立演示数据</span>
        <h1>从打卡到审批，一次讲清楚</h1>
        <p>真实业务接口、数据库记录和审批来源；流程按下方顺序操作。</p>
      </div>
      <RouterLink class="button button-secondary" to="/attendance">打开原始打卡页</RouterLink>
    </div>
    <div class="notice" role="note">
      <b>演示边界：</b
      >员工和申请是合成测试数据；定位固定为展示坐标，并非真实GPS。二维码会真实签发、解码并由服务器校验。人脸仅支持设备预览，未接入真实SDK。
    </div>
    <div v-if="error" class="notice notice-error" role="alert">{{ error }}</div>
    <div v-if="message" class="notice" role="status">{{ message }}</div>
    <div v-if="storageNotice" class="notice" role="note">{{ storageNotice }}</div>
    <div class="panel controls">
      <h2>展示步骤</h2>
      <div class="steps">
        <button class="button button-primary" :disabled="!canClick" @click="playAll">
          一键完整演示
        </button>
        <button
          v-if="automatic"
          class="button button-secondary"
          :disabled="stopRequested"
          @click="stopRequested = true"
        >
          {{ stopRequested ? '等待当前操作结束…' : '暂停自动演示' }}
        </button>
        <button class="button button-secondary" :disabled="!canClick" @click="restore">
          恢复上次进度
        </button>
      </div>
      <p v-if="automatic">
        正在演示：{{ currentStep }}。暂停将在当前操作完成后生效，已落库记录会保留。
      </p>
      <ol class="milestones" aria-label="真实结果进度">
        <li v-for="step in milestones" :key="step.label" :class="{ done: step.done }">
          {{ step.done ? '已核对' : '待完成' }} · {{ step.label }}
        </li>
      </ol>
      <p>
        当前身份：{{ auth.user?.displayName }} ·
        {{ auth.user?.roleName }}。操作会在合成员工与演示管理员间切换，后端仍独立校验权限。
      </p>
      <div class="steps">
        <button class="button button-primary" :disabled="!canClick" @click="prepare">
          {{ busy ? '处理中…' : scenario ? '重新准备新一轮展示' : '1. 准备演示数据' }}
        </button>
        <button
          class="button button-secondary"
          :disabled="!canClick || !scenarioReady || signedIn"
          @click="punch('IN')"
        >
          2. 二维码识别并上班
        </button>
        <button
          class="button button-secondary"
          :disabled="!canClick || !scenarioReady || !signedIn || signedOut"
          @click="punch('OUT')"
        >
          3. 二维码识别并下班
        </button>
        <button
          class="button button-secondary"
          :disabled="!canClick || !scenarioReady || !signedOut || !!scenario?.approvalId"
          @click="requestLeave"
        >
          4. 员工提交请假
        </button>
        <button
          class="button button-secondary"
          :disabled="
            !canClick ||
            !scenarioReady ||
            !scenario?.approvalId ||
            approval?.entry?.request?.state === 'APPROVED'
          "
          @click="approve"
        >
          5. 管理员同意申请
        </button>
        <button
          class="button button-secondary"
          :disabled="!canClick || !scenario"
          @click="action(refresh)"
        >
          重新读取真实结果
        </button>
      </div>
      <p>
        准备步骤只建立档案和排班，不预写打卡。当天使用全天展示班次，快速上下班可能显示迟到/早退及0分钟工时，这是实际时间规则的结果。
      </p>
    </div>
    <div v-if="scenario" class="grid">
      <section class="panel block">
        <h2>真实打卡事件</h2>
        <p>合成账号：{{ scenario.username }} · {{ scenario.date }}</p>
        <p v-if="!records.length">尚无打卡记录，请按顺序演示。</p>
        <div v-for="item in records" :key="item.id" class="entry">
          <b>{{ item.type === 'IN' ? '上班' : '下班' }}</b
          ><span>{{ timeText(item.occurredAt) }} · 记录 #{{ item.id }}</span>
        </div>
        <img v-if="qrImage" :src="qrImage" alt="本轮真实签名二维码" />
        <p v-if="qrImage">页面图片已实际解码；二维码短时有效，下一次打卡重新签发。</p>
      </section>
      <section class="panel block">
        <h2>请假与考勤回写</h2>
        <p>{{ scenario.tomorrow }} · 09:00—18:00，扣除1小时休息</p>
        <p>审批状态：{{ approval?.entry?.request?.state || '尚未申请' }}</p>
        <div v-if="leaveDay" class="entry">
          <b>{{ statusLabels[leaveDay.attendance.status] || leaveDay.attendance.status }}</b
          ><span>获批请假 {{ leaveDay.leaveMinutes }} 分钟</span
          ><span>来源申请：{{ leaveDay.approvalIds.join('、') || '尚无' }}</span>
        </div>
        <RouterLink v-if="scenario.approvalId" class="text-link" to="/approvals"
          >打开申请页，选择我的记录核对</RouterLink
        >
        <p>只有后端批准并完成考勤调整后，才显示实际请假分钟和来源。</p>
      </section>
    </div>
    <section class="panel block">
      <h2>其他展示入口</h2>
      <div class="steps">
        <RouterLink class="button button-secondary" to="/attendance/rules"
          >规则试算：正常 / 迟到 / 跨天</RouterLink
        ><RouterLink class="button button-secondary" to="/attendance/face"
          >摄像头预览与人脸状态</RouterLink
        ><RouterLink class="button button-secondary" to="/attendance/device"
          >权限检查与验收报告</RouterLink
        >
      </div>
    </section>
  </div>
</template>
<style scoped>
.controls,
.block {
  padding: 24px;
  margin-bottom: 22px;
  min-width: 0;
}
h2 {
  font-size: 17px;
}
p {
  font-size: 12px;
  color: #71839a;
  line-height: 1.9;
  margin: 14px 0;
  overflow-wrap: anywhere;
}
.steps {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 20px 0;
}
.milestones {
  list-style: none;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.milestones li {
  font-size: 12px;
  color: #72849e;
  background: #f3f6fa;
  padding: 8px 10px;
  border-radius: 7px;
}
.milestones .done {
  color: #16744a;
  background: #eaf8f0;
}
.grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 22px;
}
.entry {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 10px;
  padding: 15px 0;
  border-bottom: 1px solid #e5ebf4;
  font-size: 13px;
}
img {
  width: 220px;
  max-width: 100%;
  display: block;
  margin: 20px auto;
}
@media (max-width: 700px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
  }
  .controls,
  .block {
    padding: 18px;
  }
  .steps .button {
    width: 100%;
  }
}
</style>
