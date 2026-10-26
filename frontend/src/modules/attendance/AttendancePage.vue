<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  Calendar,
  Clock,
  Location,
  Refresh,
  Camera,
  CircleCheck,
  Warning,
  ArrowRight,
  VideoCamera,
} from '@element-plus/icons-vue'
import http from '../../api'
import { useAuthStore } from '../../stores/auth'
import { attendancePost, businessDate, statusLabels, timeText } from './api'

const route = useRoute()
const auth = useAuthStore()
const view = computed(() => route.meta.attendanceView || 'today')
const capability = ref(null),
  face = ref(null),
  loading = ref(false),
  error = ref(''),
  feedback = ref('')
const now = ref(new Date()),
  workplaces = ref([]),
  workplaceId = ref(''),
  token = ref(''),
  punchType = ref('IN')
const location = ref(null),
  locating = ref(false),
  punching = ref(false),
  requestId = ref(null),
  challenge = ref(null)
const result = ref(null),
  calculating = ref(false),
  ruleError = ref(''),
  records = ref(null),
  recordsLoading = ref(false),
  recordsError = ref('')
const today = businessDate()
const range = reactive({ from: `${today.slice(0, 8)}01`, to: today, page: 1, size: 10 })
const form = reactive({
  date: today,
  start: '09:00',
  end: '18:00',
  breakStart: '12:00',
  breakEnd: '13:00',
  graceMinutes: 5,
  workdays: [1, 2, 3, 4, 5],
  holiday: false,
  finalized: true,
  checkIn: `${today}T09:12`,
  checkOut: `${today}T18:00`,
})
const weekdayNames = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日']
const weekdays = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
const tabs = [
  { path: '/attendance', title: '今日打卡', icon: Clock },
  { path: '/attendance/records', title: '考勤记录', icon: Calendar },
  { path: '/attendance/rules', title: '排班与规则', icon: Calendar },
  { path: '/attendance/face', title: '人脸管理', icon: Camera },
]
const heading = computed(
  () =>
    ({
      today: ['每一天，从有序开始', '扫码、定位与身份校验，在一个地方完成。'],
      records: ['我的考勤记录', '查看真实打卡事件，保留每一次出勤的来源。'],
      rules: ['让每一分钟，都有清晰的规则', '独立试算班次与异常，先解释结果，再接入业务。'],
      face: ['安全验证，从清晰的授权开始', '查看设备准备情况与人脸能力的真实接入状态。'],
    })[view.value],
)
const available = computed(() => capability.value?.available === true)
const readinessMessages = computed(() => [
  ...(!capability.value?.directoryAvailable
    ? ['员工、办公场所与班次尚未完成配置，暂时无法打卡。']
    : []),
  ...(!capability.value?.storageAvailable ? ['考勤记录服务尚未启用，启用后可查询本人记录。'] : []),
])
const canManage = computed(() => ['ADMIN', 'HR'].includes(auth.user?.role))
const locationFresh = computed(
  () => location.value && now.value.getTime() - location.value.timestamp < 60000,
)
const canPunch = computed(
  () => available.value && token.value.trim() && locationFresh.value && !punching.value,
)
const serverClock = computed(() => timeText(now.value))
const dateText = computed(() =>
  new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'full' }).format(
    now.value,
  ),
)
let timer,
  stream,
  disposed = false,
  loadRevision = 0,
  recordsRevision = 0
const video = ref(null),
  cameraBusy = ref(false),
  cameraActive = ref(false),
  cameraError = ref(''),
  cameraConsent = ref(false)
const timeFormat = (v) => timeText(v)
const dayAfter = (date) => {
  const d = new Date(`${date}T12:00:00+08:00`)
  d.setUTCDate(d.getUTCDate() + 1)
  return businessDate(d)
}
function preset(kind) {
  const date = form.date || today
  Object.assign(form, {
    start: '09:00',
    end: '18:00',
    breakStart: '12:00',
    breakEnd: '13:00',
    graceMinutes: 5,
    holiday: false,
    finalized: true,
    workdays: [1, 2, 3, 4, 5, 6, 7],
    checkIn: `${date}T09:00`,
    checkOut: `${date}T18:00`,
  })
  if (kind === 'late') form.checkIn = `${date}T09:12`
  if (kind === 'missing') form.checkOut = ''
  if (kind === 'night')
    Object.assign(form, {
      start: '22:00',
      end: '06:00',
      breakStart: '02:00',
      breakEnd: '03:00',
      checkIn: `${date}T22:00`,
      checkOut: `${dayAfter(date)}T06:00`,
    })
  if (kind === 'rest') form.holiday = true
  calculate()
}
async function load() {
  const revision = ++loadRevision
  loading.value = true
  error.value = ''
  capability.value = null
  try {
    const [status, faceStatus] = await Promise.all([
      http.get('/attendance/status'),
      http.get('/face/status'),
    ])
    if (disposed || revision !== loadRevision) return
    capability.value = status
    face.value = faceStatus
    if (status.available) {
      workplaces.value = await http.get('/attendance/me/workplaces')
      workplaceId.value = workplaces.value[0]?.id || ''
      if (view.value === 'records') await loadRecords()
    }
  } catch (e) {
    if (!disposed && revision === loadRevision) error.value = e.message
  } finally {
    if (revision === loadRevision) loading.value = false
  }
}
let ruleRevision = 0
async function calculate() {
  if (calculating.value) return
  const revision = ruleRevision
  calculating.value = true
  ruleError.value = ''
  result.value = null
  try {
    const input = {
      date: form.date,
      rule: {
        start: form.start,
        end: form.end,
        breakStart: form.breakStart || null,
        breakEnd: form.breakEnd || null,
        graceMinutes: form.graceMinutes === '' ? null : Number(form.graceMinutes),
        workdays: form.workdays.map((v) => weekdays[v - 1]),
      },
      checkIn: form.checkIn ? `${form.checkIn}:00` : null,
      checkOut: form.checkOut ? `${form.checkOut}:00` : null,
      holiday: form.holiday,
      finalized: form.finalized,
    }
    if (!form.date || !form.start || !form.end || form.graceMinutes === '')
      throw new Error('请填写业务日期、班次时间和迟到宽限')
    const data = await attendancePost('/attendance/rules/preview', input)
    if (!disposed && revision === ruleRevision) result.value = data
  } catch (e) {
    if (!disposed && revision === ruleRevision) ruleError.value = e.message
  } finally {
    calculating.value = false
  }
}
watch(
  form,
  () => {
    ruleRevision++
    result.value = null
    ruleError.value = ''
  },
  { deep: true, flush: 'sync' },
)
watch([token, punchType], () => {
  requestId.value = null
})
function locate() {
  feedback.value = ''
  location.value = null
  requestId.value = null
  if (!window.isSecureContext) {
    feedback.value = '定位需要 HTTPS 安全环境，请通过安全地址访问。'
    return
  }
  if (!navigator.geolocation) {
    feedback.value = '当前设备不支持定位，请更换浏览器或设备。'
    return
  }
  locating.value = true
  navigator.geolocation.getCurrentPosition(
    (p) => {
      if (disposed) return
      location.value = {
        latitude: p.coords.latitude,
        longitude: p.coords.longitude,
        accuracy: p.coords.accuracy,
        timestamp: Date.now(),
      }
      locating.value = false
    },
    (e) => {
      if (disposed) return
      locating.value = false
      feedback.value =
        {
          1: '定位权限被拒绝，请在浏览器设置中允许后重试。',
          2: '暂时无法获取位置，请移到开阔处重试。',
          3: '定位超时，请检查设备定位与网络后重试。',
        }[e.code] || '定位失败，请重试。'
    },
    { enableHighAccuracy: true, timeout: 12000, maximumAge: 0 },
  )
}
async function punch() {
  if (!canPunch.value) return
  if (
    !window.confirm(
      `确认提交本次${punchType.value === 'IN' ? '上班' : '下班'}打卡？时间将由服务器记录。`,
    )
  )
    return
  punching.value = true
  feedback.value = ''
  try {
    requestId.value ||= crypto.randomUUID()
    const event = await attendancePost('/attendance/me/punches', {
      type: punchType.value,
      token: token.value.trim(),
      requestId: requestId.value,
      location: {
        latitude: location.value.latitude,
        longitude: location.value.longitude,
        coordinateSystem: 'WGS84',
      },
      accuracyMeters: location.value.accuracy,
    })
    token.value = ''
    feedback.value = `打卡成功 · ${timeText(event.occurredAt)}，可在考勤记录查看。`
  } catch (e) {
    feedback.value = e.message
  } finally {
    punching.value = false
  }
}
async function issue() {
  if (!workplaceId.value) return
  feedback.value = ''
  challenge.value = null
  try {
    challenge.value = await attendancePost(
      `/attendance/workplaces/${workplaceId.value}/challenge`,
      {},
    )
  } catch (e) {
    feedback.value = e.message
  }
}
async function copyToken() {
  try {
    await navigator.clipboard.writeText(challenge.value.token)
    feedback.value = '挑战令牌已复制，有效期 60 秒。'
  } catch {
    feedback.value = '浏览器不允许复制，请手动选中令牌复制。'
  }
}
async function loadRecords() {
  if (!available.value) return
  const revision = ++recordsRevision
  recordsLoading.value = true
  recordsError.value = ''
  try {
    if (!range.from || !range.to || range.to < range.from)
      throw new Error('请选择有效的开始与结束日期')
    const data = await http.get('/attendance/me/records', { params: { ...range } })
    if (!disposed && revision === recordsRevision) records.value = data
  } catch (e) {
    if (revision === recordsRevision) {
      records.value = null
      recordsError.value = e.message
    }
  } finally {
    if (revision === recordsRevision) recordsLoading.value = false
  }
}
function searchRecords() {
  range.page = 1
  loadRecords()
}
function changePage(delta) {
  range.page += delta
  loadRecords()
}
function stopCamera() {
  stream?.getTracks().forEach((track) => track.stop())
  stream = null
  cameraActive.value = false
  if (video.value) video.value.srcObject = null
}
async function startCamera() {
  cameraError.value = ''
  if (!cameraConsent.value) {
    cameraError.value = '请先勾选仅在本机预览的授权说明。'
    return
  }
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    cameraError.value = '摄像头需要支持媒体权限的浏览器与 HTTPS 安全环境。'
    return
  }
  cameraBusy.value = true
  try {
    const media = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'user' },
      audio: false,
    })
    if (disposed || view.value !== 'face' || !cameraConsent.value) {
      media.getTracks().forEach((t) => t.stop())
      return
    }
    stream = media
    cameraActive.value = true
    await nextTick()
    video.value.srcObject = media
    await video.value.play()
  } catch (e) {
    stopCamera()
    cameraError.value =
      {
        NotAllowedError: '摄像头权限被拒绝，请在浏览器设置中允许后重试。',
        NotFoundError: '未检测到可用摄像头。',
        NotReadableError: '摄像头正被其他应用占用。',
      }[e.name] || '摄像头暂不可用，请检查设备连接。'
  } finally {
    cameraBusy.value = false
  }
}
watch(cameraConsent, (allowed) => {
  if (!allowed) stopCamera()
})
watch(view, (v) => {
  stopCamera()
  feedback.value = ''
  if (v === 'records') loadRecords()
})
onMounted(() => {
  load()
  timer = setInterval(() => {
    now.value = new Date()
  }, 1000)
})
onUnmounted(() => {
  disposed = true
  clearInterval(timer)
  stopCamera()
})
</script>

<template>
  <div class="b-attendance">
    <div class="page-heading b-heading">
      <div>
        <span class="eyebrow">联智考勤 · {{ dateText }}</span>
        <h1>{{ heading[0] }}</h1>
        <p>{{ heading[1] }}</p>
      </div>
      <span class="tag tag-blue">{{ auth.user?.roleName }}工作空间</span>
    </div>
    <nav class="b-tabs" aria-label="考勤导航">
      <RouterLink
        v-for="tab in tabs"
        :key="tab.path"
        :to="tab.path"
        :class="{ selected: route.path === tab.path }"
        ><component :is="tab.icon" />{{ tab.title }}</RouterLink
      >
    </nav>
    <div v-if="error" class="notice notice-error" role="alert">
      {{ error }}<button class="text-button" @click="load">重新连接</button>
    </div>
    <div v-else-if="loading" class="notice" role="status">正在获取考勤与人脸能力状态…</div>
    <div v-else-if="!available && capability" class="b-readiness">
      <div class="b-readiness-title">
        <Warning /><b>规则试算已可用，真实打卡等待业务接入</b
        ><button class="text-button" @click="load">刷新状态</button>
      </div>
      <ul>
        <li v-for="reason in readinessMessages" :key="reason">{{ reason }}</li>
      </ul>
      <span class="small-text">不会生成模拟出勤，也不会修改已有账号、组织或业务数据。</span>
    </div>
    <div v-if="feedback" class="notice" role="status">
      {{ feedback }}<button class="text-button" @click="feedback = ''">收起</button>
    </div>

    <template v-if="view === 'today'">
      <div class="b-grid">
        <section class="b-card b-punch-card">
          <div class="b-card-title">
            <h2>今日打卡</h2>
            <span class="tag" :class="available ? 'tag-green' : 'tag-neutral'">{{
              available ? '服务可用' : '待接入'
            }}</span>
          </div>
          <div class="b-clock">{{ serverClock }}</div>
          <p class="b-center muted">北京时间 · 最终打卡时间由服务器生成</p>
          <div class="b-place">
            <Location />
            <div>
              <b>{{ available ? '请先获取定位并扫描场所挑战' : '尚未绑定可用办公场所' }}</b>
              <p>
                {{
                  available
                    ? '只展示你有权访问的员工与场所信息'
                    : '接入员工、场所与班次后，才能进行真实打卡'
                }}
              </p>
            </div>
          </div>
          <label class="b-field"
            ><span>打卡类型</span
            ><select v-model="punchType" :disabled="!available || punching">
              <option value="IN">上班签到</option>
              <option value="OUT">下班签退</option>
            </select></label
          >
          <label class="b-field"
            ><span>场所挑战令牌</span
            ><textarea
              v-model="token"
              :disabled="!available || punching"
              maxlength="512"
              placeholder="粘贴管理员发放的有效场所挑战令牌"
              rows="3"
            />
          </label>
          <button class="b-punch-button" :disabled="!canPunch" @click="punch">
            <Clock />{{ punching ? '正在提交…' : available ? '确认打卡' : '等待业务接入' }}
          </button>
          <p class="b-center b-small">
            {{ available ? '定位 60 秒内有效 · 令牌过期后请重新获取' : '当前不会生成打卡记录' }}
          </p>
          <div class="b-two">
            <RouterLink class="b-shortcut" to="/attendance/records"
              ><Calendar /><span>查看考勤记录</span><ArrowRight /></RouterLink
            ><RouterLink class="b-shortcut" to="/attendance/rules"
              ><Clock /><span>试算考勤规则</span><ArrowRight
            /></RouterLink>
          </div>
        </section>
        <div>
          <section class="b-card">
            <div class="b-card-title">
              <h2>打卡前，准备这三步</h2>
              <span class="tag tag-outline">WGS84</span>
            </div>
            <ol class="b-checklist">
              <li>
                <span>01</span>
                <div>
                  <b>确认班次与场所</b>
                  <p>由组织模块提供有效的员工绑定及打卡半径。</p>
                </div>
              </li>
              <li>
                <span>02</span>
                <div>
                  <b>获取真实设备定位</b>
                  <p>需要浏览器授权。定位精度不足或超出范围时拒绝提交。</p>
                </div>
              </li>
              <li>
                <span>03</span>
                <div>
                  <b>获取短时场所挑战</b>
                  <p>服务端验签、防过期，并按员工独立防重。</p>
                </div>
              </li>
            </ol>
            <button
              class="button button-secondary b-full"
              :disabled="locating || !available"
              @click="locate"
            >
              <Location />{{ locating ? '定位中…' : '获取当前位置' }}
            </button>
            <div v-if="location" class="b-location-result">
              <b>{{ locationFresh ? '定位已获取' : '定位已过期，请重新获取' }}</b>
              <p>精度约 {{ Math.round(location.accuracy) }} 米 · 仅在本次打卡发送</p>
            </div>
          </section>
          <section v-if="canManage" class="b-card">
            <h2>场所挑战管理</h2>
            <p class="b-description">
              仅授权管理人员可生成。服务端再次校验公司与场所范围；当前提供令牌，扫码呈现尚待接入。
            </p>
            <label class="b-field"
              ><span>授权场所</span
              ><select v-model="workplaceId" :disabled="!available">
                <option value="">{{ available ? '请选择场所' : '等待场所接口接入' }}</option>
                <option v-for="p in workplaces" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select></label
            ><button
              class="button button-secondary b-full"
              :disabled="!available || !workplaceId"
              @click="issue"
            >
              <Refresh />生成短时挑战
            </button>
            <div v-if="challenge" class="b-location-result">
              <p>到期时间 {{ timeFormat(challenge.expiresAt) }}</p>
              <textarea readonly :value="challenge.token" aria-label="场所挑战令牌" /><button
                class="text-button"
                @click="copyToken"
              >
                复制令牌
              </button>
            </div>
          </section>
          <section v-else class="b-card">
            <h2>为什么需要这些权限？</h2>
            <p class="b-description">
              定位用于确认是否在办公场所。摄像头仅在你主动授权时开启；未接入 SDK
              时，不会上传或保存人脸图像。
            </p>
            <RouterLink class="text-link" to="/attendance/face"
              >查看人脸能力与设备准备 <ArrowRight
            /></RouterLink>
          </section>
        </div>
      </div>
    </template>

    <template v-else-if="view === 'records'">
      <section class="b-card">
        <div class="b-card-title">
          <h2>我的原始打卡事件</h2>
          <span class="tag tag-outline">仅本人数据</span>
        </div>
        <form class="b-record-filters" @submit.prevent="searchRecords">
          <label class="b-field"
            ><span>开始日期</span><input v-model="range.from" type="date" required /></label
          ><label class="b-field"
            ><span>结束日期</span
            ><input v-model="range.to" type="date" :min="range.from" required /></label
          ><button class="button button-primary" :disabled="!available || recordsLoading">
            {{ recordsLoading ? '查询中…' : '查询记录' }}
          </button>
        </form>
        <p class="b-small">
          每次查询最多 94 天。按服务器事件时间倒序显示，个人页面不接受其他员工编号。
        </p>
        <div v-if="recordsError" role="alert" class="notice notice-error">
          {{ recordsError }}<button class="text-button" @click="loadRecords">重试</button>
        </div>
        <div v-if="recordsLoading" class="b-empty" role="status">正在查询真实打卡记录…</div>
        <div v-else-if="!available || !records?.items.length" class="b-empty">
          <Calendar />
          <h3>{{ !available ? '考勤存储尚未接入' : '这个时间范围内暂无记录' }}</h3>
          <p>
            {{
              !available
                ? '接入完成后将在这里展示真实记录，当前不填充模拟数据。'
                : '可以调整查询日期，或完成真实打卡后再查看。'
            }}
          </p>
          <RouterLink class="text-link" to="/attendance">返回今日打卡 <ArrowRight /></RouterLink>
        </div>
        <template v-else
          ><div class="b-table-scroll">
            <table>
              <thead>
                <tr>
                  <th>业务日期</th>
                  <th>北京时间</th>
                  <th>类型</th>
                  <th>场所编号</th>
                  <th>距离</th>
                  <th>来源</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="event in records.items" :key="event.id">
                  <td>{{ event.businessDate }}</td>
                  <td>{{ timeFormat(event.occurredAt) }}</td>
                  <td>
                    <span class="tag tag-blue">{{ event.type === 'IN' ? '上班' : '下班' }}</span>
                  </td>
                  <td>{{ event.workplaceId }}</td>
                  <td>{{ Math.round(event.distanceMeters) }} 米</td>
                  <td>二维码与定位</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="b-pagination">
            <span>共 {{ records.total }} 条 · 第 {{ records.page }} 页</span
            ><button
              class="button button-secondary"
              :disabled="range.page <= 1 || recordsLoading"
              @click="changePage(-1)"
            >
              上一页</button
            ><button
              class="button button-secondary"
              :disabled="range.page * range.size >= records.total || recordsLoading"
              @click="changePage(1)"
            >
              下一页
            </button>
          </div></template
        >
      </section>
    </template>

    <template v-else-if="view === 'rules'">
      <div class="b-rule-note">
        <CircleCheck />
        <div>
          <b>规则试算 · 不保存为正式排班</b>
          <p>修改任意字段后，结果立即失效；点击试算查看新的考勤结果。</p>
        </div>
      </div>
      <div class="b-presets">
        <span>快速体验</span
        ><button
          v-for="item in [
            { key: 'normal', label: '正常班次' },
            { key: 'late', label: '迟到' },
            { key: 'missing', label: '缺卡' },
            { key: 'night', label: '跨天班次' },
            { key: 'rest', label: '休息日' },
          ]"
          :key="item.key"
          class="button button-secondary"
          :disabled="calculating"
          @click="preset(item.key)"
        >
          {{ item.label }}
        </button>
      </div>
      <form class="b-grid" @submit.prevent="calculate">
        <section class="b-card">
          <div class="b-card-title">
            <h2>① 定义班次</h2>
            <span class="tag tag-outline">精确到分钟</span>
          </div>
          <label class="b-field"
            ><span>业务日期（以班次开始日归属）</span
            ><input v-model="form.date" type="date" required
          /></label>
          <div class="b-fields">
            <label class="b-field"
              ><span>上班时间</span><input v-model="form.start" type="time" required /></label
            ><label class="b-field"
              ><span>下班时间</span><input v-model="form.end" type="time" required /></label
            ><label class="b-field"
              ><span>休息开始（可留空）</span><input v-model="form.breakStart" type="time" /></label
            ><label class="b-field"
              ><span>休息结束（可留空）</span><input v-model="form.breakEnd" type="time"
            /></label>
          </div>
          <p class="b-small">下班早于上班即为跨天班次。无休息时，请同时清空两个休息字段。</p>
          <label class="b-field"
            ><span>迟到宽限（分钟）</span
            ><input v-model="form.graceMinutes" type="number" min="0" max="120" step="1" required
          /></label>
          <fieldset class="b-weekdays">
            <legend>每周工作日</legend>
            <label v-for="(name, i) in weekdayNames" :key="name"
              ><input v-model="form.workdays" type="checkbox" :value="i + 1" />{{
                name.slice(-1)
              }}</label
            >
          </fieldset>
          <label class="b-checkbox"
            ><input v-model="form.holiday" type="checkbox" />此业务日期为节假日 / 调休休息日</label
          >
        </section>
        <section class="b-card b-rule-result">
          <h2>② 试算打卡时间</h2>
          <p class="b-description">这是试算输入，不会创建出勤记录。跨天签退请填写下一天的日期。</p>
          <label class="b-field"
            ><span>签到日期与时间（留空表示缺卡）</span
            ><input v-model="form.checkIn" type="datetime-local" /></label
          ><label class="b-field"
            ><span>签退日期与时间（留空表示缺卡）</span
            ><input v-model="form.checkOut" type="datetime-local" /></label
          ><label class="b-checkbox"
            ><input v-model="form.finalized" type="checkbox" />班次已结束，按结算状态判断缺勤 /
            缺卡</label
          ><button class="button button-primary b-full" :disabled="calculating">
            {{ calculating ? '正在计算…' : '计算考勤结果' }}
          </button>
          <div v-if="ruleError" class="notice notice-error b-result" role="alert">
            {{ ruleError }}
          </div>
          <div v-else-if="result" class="b-result" aria-live="polite">
            <div class="row b-card-title">
              <span class="tag" :class="result.status === 'NORMAL' ? 'tag-green' : 'tag-blue'">{{
                statusLabels[result.status]
              }}</span
              ><span class="b-small">{{ result.overnight ? '跨天班次' : '当日班次' }}</span>
            </div>
            <div class="b-work-minutes">
              {{ Math.floor(result.workMinutes / 60) }}<small>小时</small
              >{{ result.workMinutes % 60 }}<small>分钟</small>
            </div>
            <p class="b-small">有效工时</p>
            <div class="b-result-stats">
              <span
                >迟到 <b>{{ result.lateMinutes }} 分</b></span
              ><span
                >早退 <b>{{ result.earlyMinutes }} 分</b></span
              ><span
                >休息扣除 <b>{{ result.breakMinutes }} 分</b></span
              >
            </div>
            <ul>
              <li v-for="note in result.notes" :key="note">{{ note }}</li>
            </ul>
          </div>
          <div v-else class="b-result b-result-placeholder">
            <Clock />
            <p>设置好班次后，点击计算查看结果</p>
            <span class="b-small">不会将缺卡或未结算状态当作正常出勤</span>
          </div>
        </section>
      </form>
    </template>

    <template v-else-if="view === 'face'">
      <div class="b-grid">
        <section class="b-card">
          <div class="b-card-title">
            <h2>设备预览</h2>
            <span class="tag tag-neutral">不上传图像</span>
          </div>
          <div class="b-camera">
            <video
              v-show="cameraActive"
              ref="video"
              autoplay
              muted
              playsinline
              aria-label="本机摄像头预览"
            /><template v-if="!cameraActive"
              ><div class="b-face-outline" />
              <p>正对摄像头，保持光线充足</p>
              <span>开启后仅在你的设备上预览</span></template
            ><span v-else class="b-camera-label">本机预览 · 未进行识别</span>
          </div>
          <label class="b-checkbox"
            ><input
              v-model="cameraConsent"
              type="checkbox"
            />我同意仅开启本机摄像头预览；不采集、不上传、不登记。</label
          >
          <div class="b-two">
            <button
              class="button button-primary"
              :disabled="cameraBusy || cameraActive || !cameraConsent"
              @click="startCamera"
            >
              <VideoCamera />{{ cameraBusy ? '等待设备授权…' : '开启本机预览' }}</button
            ><button class="button button-secondary" :disabled="!cameraActive" @click="stopCamera">
              关闭摄像头
            </button>
          </div>
          <div v-if="cameraError" class="notice notice-error b-result" role="alert">
            {{ cameraError }}
          </div>
          <p class="b-small b-description">
            切换页面、撤回勾选或退出本页面时立即释放摄像头。预览不代表人脸检测、比对或活体检测成功。
          </p>
        </section>
        <div>
          <section class="b-card">
            <h2>人脸能力接入状态</h2>
            <div
              v-for="item in [
                { label: '人脸检测', key: 'detection' },
                { label: '身份比对', key: 'comparison' },
                { label: '活体检测', key: 'liveness' },
              ]"
              :key="item.key"
              class="b-capability-row"
            >
              <span>{{ item.label }}</span
              ><span class="tag" :class="face?.[item.key] ? 'tag-green' : 'tag-neutral'">{{
                face?.[item.key] ? '已接入' : '未接入'
              }}</span>
            </div>
            <p class="b-small b-description">
              {{ face?.message || '正在获取服务端状态；未知状态不会视为可用。' }}
            </p>
            <button class="button button-secondary b-full" disabled>登记与验证暂不可用</button>
          </section>
          <section class="b-card">
            <h2>接入后的验证流程</h2>
            <ol class="b-checklist">
              <li>
                <span>01</span>
                <div>
                  <b>明确授权与采集说明</b>
                  <p>支持登记、更新、删除及权限控制。</p>
                </div>
              </li>
              <li>
                <span>02</span>
                <div>
                  <b>后端调用真实 SDK</b>
                  <p>分别校验检测质量、身份比对与活体结果。</p>
                </div>
              </li>
              <li>
                <span>03</span>
                <div>
                  <b>绑定当前员工与挑战</b>
                  <p>不接受前端传入“验证成功”，失败则拒绝打卡。</p>
                </div>
              </li>
            </ol>
          </section>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.b-attendance {
  --b-line: #e5ebf4;
  --b-muted: #8290a5;
}
.b-heading {
  margin-bottom: 24px;
}
.b-tabs {
  display: flex;
  gap: 8px;
  border-bottom: 1px solid var(--b-line);
  margin-bottom: 24px;
  overflow-x: auto;
}
.b-tabs a {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 15px 20px;
  border-bottom: 3px solid transparent;
  color: #7c8a9f;
  white-space: nowrap;
}
.b-tabs svg {
  width: 17px;
  height: 17px;
}
.b-tabs a.selected {
  color: #2563eb;
  border-color: #2563eb;
  font-weight: 600;
}
.b-readiness {
  border: 1px solid #f0dfb8;
  background: #fffaf0;
  padding: 17px 20px;
  border-radius: 10px;
  margin-bottom: 24px;
  color: #8d7040;
  font-size: 12px;
  line-height: 1.8;
}
.b-readiness-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.b-readiness-title .text-button {
  margin-left: auto;
}
.b-readiness ul {
  padding-left: 25px;
  margin: 9px 0;
}
.b-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 24px;
  align-items: start;
}
.b-card {
  background: white;
  border: 1px solid var(--b-line);
  border-radius: 13px;
  padding: 26px;
  margin-bottom: 24px;
  min-width: 0;
}
.b-card-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}
.b-card h2 {
  font-size: 16px;
}
.b-clock {
  font-size: 52px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  letter-spacing: 2px;
  text-align: center;
  margin: 24px 0 5px;
  color: #223653;
}
.b-center {
  text-align: center;
}
.b-place {
  padding: 19px;
  border-radius: 9px;
  background: #f4f7fc;
  display: flex;
  gap: 13px;
  align-items: center;
  margin: 24px 0;
}
.b-place svg {
  color: #527bbd;
}
.b-place b {
  font-size: 13px;
}
.b-place p {
  font-size: 11px;
  color: #8b98aa;
  margin-top: 6px;
}
.b-field {
  display: block;
  margin: 17px 0;
}
.b-field > span {
  display: block;
  font-size: 12px;
  color: #65758e;
  margin-bottom: 9px;
}
.b-field input,
.b-field select,
.b-field textarea,
.b-location-result textarea {
  width: 100%;
  border: 1px solid #dce4ef;
  border-radius: 8px;
  padding: 11px 12px;
  background: #fff;
  color: #344763;
  min-height: 43px;
  font: inherit;
  max-width: 100%;
  outline-offset: 3px;
}
.b-field textarea {
  resize: vertical;
}
.b-field input:focus,
.b-field select:focus,
.b-field textarea:focus {
  border-color: #7fa5ed;
}
.b-field :disabled {
  background: #f6f8fb;
  color: #8f9db0;
}
.b-punch-button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border: 0;
  border-radius: 10px;
  width: 100%;
  padding: 17px;
  background: #2563eb;
  color: white;
  font-weight: 600;
  margin-top: 21px;
}
.b-punch-button:disabled {
  background: #edf1f7;
  color: #8191aa;
  opacity: 1;
}
.b-small {
  font-size: 11px;
  line-height: 1.9;
  color: var(--b-muted);
}
.b-punch-card > .b-small {
  margin: 12px 0 25px;
}
.b-two,
.b-fields {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 13px;
}
.b-shortcut {
  padding: 14px 10px;
  background: #f8faff;
  border: 1px solid #e6edf7;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  font-size: 12px;
  color: #5e779f;
}
.b-shortcut svg {
  width: 16px;
  height: 16px;
}
.b-shortcut svg:last-child {
  width: 12px;
}
.b-checklist {
  list-style: none;
  padding: 0;
  margin: 25px 0;
}
.b-checklist li {
  display: flex;
  gap: 14px;
  margin: 21px 0;
}
.b-checklist li > span {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: #edf3ff;
  color: #5c80b8;
  font-size: 11px;
  flex-shrink: 0;
}
.b-checklist b {
  font-size: 12px;
}
.b-checklist p {
  font-size: 11px;
  color: var(--b-muted);
  line-height: 1.9;
  margin-top: 6px;
}
.b-full {
  width: 100%;
  justify-content: center;
}
.b-description {
  margin: 16px 0;
  color: var(--b-muted);
  font-size: 12px;
  line-height: 1.9;
}
.b-location-result {
  background: #f3f8ff;
  padding: 15px;
  border-radius: 8px;
  margin-top: 17px;
  font-size: 12px;
}
.b-location-result p {
  font-size: 11px;
  color: var(--b-muted);
  margin: 6px 0;
}
.b-record-filters {
  display: flex;
  gap: 14px;
  align-items: end;
  flex-wrap: wrap;
}
.b-record-filters .b-field {
  min-width: 160px;
  flex: 1;
}
.b-record-filters > .button {
  margin-bottom: 17px;
}
.b-empty {
  padding: 60px 20px;
  text-align: center;
}
.b-empty > svg {
  width: 44px;
  height: 44px;
  padding: 10px;
  background: #f1f5fc;
  box-sizing: content-box;
  color: #90a7cc;
  border-radius: 18px;
  margin-bottom: 18px;
}
.b-empty h3 {
  font-size: 15px;
  color: #5e708b;
}
.b-empty p {
  font-size: 12px;
  color: var(--b-muted);
  margin: 12px auto 20px;
  max-width: 340px;
  line-height: 2;
}
.b-table-scroll {
  overflow-x: auto;
}
.b-pagination {
  display: flex;
  justify-content: end;
  align-items: center;
  gap: 12px;
  margin-top: 20px;
  font-size: 12px;
  color: var(--b-muted);
}
.b-pagination > span {
  margin-right: auto;
}
.b-rule-note {
  display: flex;
  gap: 12px;
  align-items: center;
  background: #edf6ff;
  padding: 18px 20px;
  border-radius: 10px;
  color: #4778aa;
  margin-bottom: 20px;
  font-size: 12px;
}
.b-rule-note p {
  font-size: 11px;
  margin-top: 5px;
  color: #7190b0;
}
.b-presets {
  display: flex;
  gap: 9px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 22px;
}
.b-presets > span {
  font-size: 12px;
  color: var(--b-muted);
  margin-right: 7px;
}
.b-presets .button {
  padding: 8px 13px;
  font-size: 12px;
  min-height: 36px;
}
.b-weekdays {
  border: 0;
  padding: 0;
  margin: 20px 0;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.b-weekdays legend {
  font-size: 12px;
  color: #65758e;
  margin-bottom: 12px;
}
.b-weekdays label {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #5c6f8a;
  font-size: 12px;
}
.b-weekdays input,
.b-checkbox input {
  accent-color: #2563eb;
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}
.b-checkbox {
  display: flex;
  gap: 9px;
  align-items: start;
  font-size: 11px;
  line-height: 1.9;
  color: #70819a;
  margin: 20px 0;
}
.b-checkbox input {
  margin-top: 3px;
}
.b-result {
  padding: 21px;
  border-radius: 10px;
  background: #f3f7ff;
  margin-top: 22px;
  overflow-wrap: anywhere;
}
.b-result ul {
  font-size: 11px;
  color: #7c8fa9;
  padding-left: 17px;
  line-height: 1.9;
  margin-bottom: 0;
}
.b-work-minutes {
  font-size: 38px;
  color: #2d5fa8;
  font-weight: 600;
}
.b-work-minutes small {
  font-size: 12px;
  margin: 0 10px 0 5px;
  font-weight: 400;
  color: #8597b0;
}
.b-result-stats {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin: 22px 0;
  font-size: 11px;
  color: var(--b-muted);
}
.b-result-stats b {
  display: block;
  font-size: 14px;
  color: #516b91;
  margin-top: 8px;
}
.b-result-placeholder {
  text-align: center;
  padding: 40px 15px;
  color: #91a5c3;
}
.b-result-placeholder p {
  font-size: 12px;
  margin: 10px 0;
}
.b-camera {
  position: relative;
  border-radius: 10px;
  background: #18283f;
  color: #c3d0e4;
  height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  overflow: hidden;
  margin-top: 20px;
}
.b-face-outline {
  width: 110px;
  height: 145px;
  border: 2px dashed #738aa9;
  border-radius: 50%;
  margin-bottom: 20px;
}
.b-camera p {
  font-size: 12px;
}
.b-camera > span {
  font-size: 10px;
  color: #839ab9;
  margin-top: 7px;
}
.b-camera video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scaleX(-1);
}
.b-camera .b-camera-label {
  position: absolute;
  bottom: 12px;
  background: #16253add;
  border-radius: 5px;
  padding: 5px 10px;
  color: white;
}
.b-capability-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 0;
  border-bottom: 1px solid var(--b-line);
  font-size: 12px;
}
.b-attendance input,
.b-attendance select {
  min-width: 0;
}
.b-card .notice {
  margin-bottom: 0;
}
@media (max-width: 1000px) {
  .b-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .b-clock {
    font-size: 45px;
  }
  .b-card {
    padding: 23px;
  }
}
@media (max-width: 600px) {
  .b-tabs {
    gap: 0;
  }
  .b-tabs a {
    padding: 13px 11px;
    font-size: 11px;
    gap: 5px;
  }
  .b-tabs svg {
    width: 14px;
    height: 14px;
  }
  .b-heading > .tag {
    display: none;
  }
  .b-heading h1 {
    font-size: 22px;
  }
  .b-card {
    padding: 20px 16px;
    margin-bottom: 18px;
  }
  .b-readiness {
    padding: 15px;
  }
  .b-readiness-title {
    gap: 7px;
  }
  .b-grid {
    gap: 0;
  }
  .b-clock {
    font-size: 42px;
  }
  .b-fields {
    gap: 10px;
  }
  .b-record-filters {
    gap: 0 10px;
  }
  .b-record-filters .b-field {
    min-width: 120px;
  }
  .b-record-filters > .button {
    width: 100%;
  }
  .b-two {
    gap: 10px;
  }
  .b-two .button {
    font-size: 11px;
    padding: 9px;
  }
  .b-shortcut {
    font-size: 10px;
    gap: 5px;
  }
  .b-presets {
    gap: 7px;
  }
  .b-presets > span {
    width: 100%;
  }
  .b-presets .button {
    padding: 8px 11px;
  }
  .b-result {
    padding: 17px;
  }
  .b-work-minutes {
    font-size: 33px;
  }
  .b-pagination {
    gap: 7px;
    flex-wrap: wrap;
  }
  .b-camera {
    height: 270px;
  }
}
</style>
