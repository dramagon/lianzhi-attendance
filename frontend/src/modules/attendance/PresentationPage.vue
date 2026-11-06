<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import QRCode from 'qrcode'
import jsQR from 'jsqr'
import { zipSync, strToU8 } from 'fflate'
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
const report = ref(null),
  document = ref(null),
  answer = ref(null),
  searchHits = ref([])
const extraComplete = computed(
  () =>
    report.value && document.value?.processing === 'PARSED' && answer.value?.state === 'ANSWERED',
)
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
  report.value = document.value = answer.value = null
  searchHits.value = []
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
      name = `办公场所${stamp}`
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
      displayName: `员工${stamp}`,
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
    const canvas = window.document.createElement('canvas')
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
async function demonstrateReports() {
  return action(async () => {
    if (!milestones.value.every((step) => step.done)) throw new Error('请先完成打卡与审批步骤')
    await asUser('admin')
    const context = scenario.value
    const result = await http.get('/reports/attendance', {
      params: { from: context.date, to: context.tomorrow, userId: context.userId },
    })
    const leave = result.records.items.find((row) => row.date === context.tomorrow)
    if (
      result.source !== 'AttendanceDays.list' ||
      leave?.leaveMinutes !== 480 ||
      !leave.approvalIds.includes(context.approvalId)
    )
      throw new Error('报表尚未核对到本轮480分钟请假及审批来源')
    report.value = result
    message.value = '报表已核对：读取同一考勤口径，包含本轮审批来源。'
  })
}
async function demonstrateKnowledge() {
  return action(async () => {
    await asUser('admin')
    const employee = await http.get('/employees/me')
    if (!document.value) {
      const form = new FormData()
      form.append('companyId', employee.companyId)
      form.append('visibility', 'COMPANY')
      const policy = `${scenario.value.username} 年假申请流程：员工先提交请假申请，管理员审批通过后，系统按排班扣除休息时间并回写考勤。展示班次09:00至18:00，午休12:00至13:00，全天请假为480分钟。未经审批的申请不计入获批请假。本制度为课堂合成资料，不是真实公司制度。`
      const bytes = zipSync({
        '[Content_Types].xml': strToU8(
          '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
        ),
        '_rels/.rels': strToU8(
          '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
        ),
        'word/document.xml': strToU8(
          `<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>${policy}</w:t></w:r></w:p></w:body></w:document>`,
        ),
      })
      form.append(
        'file',
        new Blob([bytes], {
          type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        }),
        `课堂制度-${scenario.value.username}.docx`,
      )
      document.value = await write('post', '/knowledge/documents', form)
    }
    const deadline = Date.now() + 60000
    while (['QUEUED', 'PROCESSING'].includes(document.value.processing)) {
      if (disposed) throw new Error('已退出演示台')
      if (Date.now() >= deadline) throw new Error('文档解析尚未完成，可稍后重试；不会伪造成功结果')
      await new Promise((resolve) => setTimeout(resolve, 500))
      document.value = await http.get(`/knowledge/documents/${document.value.id}`)
    }
    if (document.value.processing !== 'PARSED')
      throw new Error(`文档未解析成功：${document.value.message}`)
    const hits = await http.get('/knowledge/search', {
      params: { query: `${scenario.value.username} 年假`, mode: 'TEXT', limit: 10 },
    })
    if (!hits.some((hit) => hit.citation.documentId === document.value.id))
      throw new Error('未检索到刚上传的制度，请核对知识库状态')
    searchHits.value = hits
    message.value = '知识库已核对：文件已落库、解析并通过文字检索命中。'
  })
}
async function demonstrateAnswer() {
  return action(async () => {
    if (!document.value || !searchHits.value.length) throw new Error('请先完成知识库上传与检索')
    await asUser(scenario.value.username)
    const result = await write('post', '/assistant/ask', {
      question: `${scenario.value.username} 年假`,
    })
    if (
      result.state !== 'ANSWERED' ||
      !result.references.some((ref) => ref.source.documentId === document.value.id)
    )
      throw new Error(result.message || '问答未返回本轮制度的有效来源')
    const source = result.references.find((ref) => ref.source.documentId === document.value.id)
    const original = await http.get(
      `/knowledge/documents/${source.source.documentId}/sources/${source.source.chunk.sequence}`,
      {
        params: { version: source.source.version },
      },
    )
    if (!original.chunk.content.includes(source.quote)) throw new Error('摘录与当前原文不一致')
    answer.value = result
    await asUser('admin')
    message.value = '摘录已核对：按员工权限读取制度，引用与当前原文一致；未调用大模型。'
  })
}
async function downloadReport() {
  return action(async () => {
    await asUser('admin')
    const context = scenario.value
    const query = new URLSearchParams({
      from: context.date,
      to: context.tomorrow,
      userId: context.userId,
    })
    const response = await fetch(`/api/reports/attendance/export?${query}`, {
      credentials: 'same-origin',
    })
    if (!response.ok) throw new Error('导出失败，请核对权限及网络后重试')
    const url = URL.createObjectURL(await response.blob())
    const link = window.document.createElement('a')
    link.href = url
    link.download = `课堂考勤-${context.username}.csv`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    message.value = '已下载真实报表CSV，可用Excel打开核对。'
  })
}
async function playAll(full = false) {
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
  if (full)
    tasks.push(
      ['读取统计报表', demonstrateReports],
      ['上传制度并检索', demonstrateKnowledge],
      ['按员工权限摘录制度', demonstrateAnswer],
    )
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
      else if (milestones.value.every((step) => step.done) && (!full || extraComplete.value))
        message.value = full
          ? '整站演示已完成：打卡、审批回写、统计报表、知识库检索和带出处摘录均已核对。'
          : '完整演示已完成：两条打卡记录、审批通过及480分钟请假均已从后台核对。'
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
        <button class="button button-primary" :disabled="!canClick" @click="playAll()">
          一键完整演示
        </button>
        <button class="button button-primary" :disabled="!canClick" @click="playAll(true)">
          一键整站演示
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
    <section class="panel block" aria-label="整站联动结果">
      <h2>报表、知识库与制度摘录</h2>
      <p>
        无需SDK、网络或外部模型。上传的是合成制度；回答仅摘录有权访问的原文，不代表生成式AI、OCR或语音转写已接入。
      </p>
      <div class="steps">
        <button
          class="button button-secondary"
          :disabled="!canClick || !milestones.every((step) => step.done)"
          @click="demonstrateReports"
        >
          6. 核对报表
        </button>
        <button
          class="button button-secondary"
          :disabled="!canClick || !report"
          @click="demonstrateKnowledge"
        >
          7. 上传制度并检索
        </button>
        <button
          class="button button-secondary"
          :disabled="!canClick || !searchHits.length"
          @click="demonstrateAnswer"
        >
          8. 核对带出处摘录
        </button>
        <button
          class="button button-secondary"
          :disabled="!canClick || !report"
          @click="downloadReport"
        >
          下载本轮CSV
        </button>
      </div>
      <p v-if="report" data-testid="demo-report">
        报表已核对：{{ report.records.total }}条日记录，请假{{
          report.totals.leaveMinutes
        }}分钟；来源{{ report.source }}。
      </p>
      <p v-if="document" data-testid="demo-document">
        制度：{{ document.name }} · {{ document.processing }} ·
        {{ document.chunkCount }}个切片；本轮文字检索{{
          searchHits.filter((hit) => hit.citation.documentId === document.id).length
        }}条命中。
      </p>
      <div v-if="answer" data-testid="demo-answer">
        <h3>离线原文摘录（非生成式AI）</h3>
        <p style="white-space: pre-wrap">{{ answer.text }}</p>
        <p v-for="reference in answer.references" :key="reference.id">
          出处[{{ reference.id }}]：{{ reference.source.documentId }} · 版本{{
            reference.source.version
          }}
          · 切片{{ reference.source.chunk.sequence }}
        </p>
      </div>
      <div class="steps">
        <RouterLink class="button button-secondary" to="/reports">统计报表与图表</RouterLink>
        <RouterLink class="button button-secondary" to="/knowledge">知识库文件管理</RouterLink>
        <RouterLink class="button button-secondary" to="/assistant">业务助手与确认申请</RouterLink>
      </div>
      <p>
        若当前是员工身份，报表需要重新登录演示管理员；整站演示完成后已自动切回管理员。刷新只恢复考勤进度，报表与制度可按6—8步重新核对。
      </p>
    </section>
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
