<script setup>
import { ref, computed, nextTick, watch, onUnmounted } from 'vue'
import { write } from '../../api'
const props = defineProps({
  status: Object,
  context: Object,
  mode: { default: 'manage' },
  disabled: Boolean,
})
const emit = defineEmits(['changed', 'verified'])
const consent = ref(false),
  active = ref(false),
  cameraBusy = ref(false),
  busy = ref(false),
  error = ref(''),
  message = ref(''),
  video = ref(null),
  collectionSection = ref(null),
  consentInput = ref(null),
  cameraButton = ref(null),
  captureButton = ref(null),
  guided = ref(false)
const available = computed(() => props.status?.available === true && !props.disabled)
const collectionGuide = computed(() => {
  if (busy.value) return '正在处理本次采集，请稍候。'
  if (!consent.value) return '请先勾选下方采集同意，再开启采集摄像头。'
  if (cameraBusy.value) return '请在浏览器提示中允许使用摄像头。'
  if (!active.value) return '已勾选采集同意，请点击“开启采集摄像头”。'
  return '画面已就绪，请点击下方采集按钮进行服务端识别。'
})
async function focusCollection() {
  if (!available.value) return
  guided.value = true
  await nextTick()
  if (disposed || !available.value) return
  collectionSection.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  const target = busy.value
    ? collectionSection.value
    : !consent.value
      ? consentInput.value
      : active.value
        ? captureButton.value
        : cameraButton.value
  target?.focus({ preventScroll: true })
}
defineExpose({ focusCollection })
let stream,
  disposed = false,
  cameraRevision = 0,
  operation = 0
function stop() {
  cameraRevision++
  operation++
  stream?.getTracks().forEach((track) => track.stop())
  stream = null
  if (video.value) video.value.srcObject = null
  active.value = false
  cameraBusy.value = false
  busy.value = false
}
function reset() {
  stop()
  message.value = ''
  emit('verified', null)
}
function current(revision) {
  return !disposed && revision === operation && available.value && consent.value
}
async function start() {
  if (!available.value || !consent.value || active.value || cameraBusy.value) return
  stop()
  const revision = cameraRevision
  error.value = ''
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    error.value = '采集需要可信HTTPS和摄像头支持'
    return
  }
  cameraBusy.value = true
  try {
    const media = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'user' },
      audio: false,
    })
    if (disposed || revision !== cameraRevision || !consent.value || !available.value) {
      media.getTracks().forEach((track) => track.stop())
      return
    }
    stream = media
    active.value = true
    await nextTick()
    if (disposed || revision !== cameraRevision || !video.value) return
    video.value.srcObject = media
    await video.value.play()
  } catch (e) {
    if (disposed || revision !== cameraRevision) return
    stop()
    error.value =
      e.name === 'NotAllowedError'
        ? '摄像头权限被拒绝，请允许后重试'
        : '摄像头不可用，请检查设备或其他应用占用'
  } finally {
    if (revision === cameraRevision) cameraBusy.value = false
  }
}
async function capture() {
  const source = video.value
  if (!active.value || !source?.videoWidth || !source.videoHeight)
    throw new Error('请等待摄像头画面准备好，再重新采集')
  const canvas = document.createElement('canvas'),
    scale = Math.min(1, 1280 / Math.max(source.videoWidth, source.videoHeight))
  canvas.width = Math.floor(source.videoWidth * scale)
  canvas.height = Math.floor(source.videoHeight * scale)
  canvas.getContext('2d').drawImage(source, 0, 0, canvas.width, canvas.height)
  try {
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.9))
    if (!blob || blob.size > 2 * 1024 * 1024) throw new Error('采集失败或图像超过2MB，请重新采集')
    return blob
  } finally {
    canvas.width = canvas.height = 0
  }
}
async function submit() {
  if (!available.value || !consent.value || !active.value || busy.value) return
  const revision = ++operation
  const context = props.context ? { ...props.context } : null
  const enrollment = { enrolled: props.status.enrolled, revision: props.status.revision }
  busy.value = true
  error.value = message.value = ''
  emit('verified', null)
  try {
    let challenge
    if (props.mode === 'verify') {
      if (!context?.token || !context.requestId)
        throw new Error('请先扫码并获取定位，确认本次打卡方向')
      challenge = await write('post', '/face/challenges', context)
      if (!current(revision)) return
    }
    const image = await capture()
    if (!current(revision)) return
    const data = new FormData()
    data.append('image', image, 'capture.jpg')
    data.append('consent', 'true')
    if (props.mode === 'verify') {
      data.append('challengeId', challenge.id)
      const result = await write('post', '/face/verification', data)
      if (!current(revision)) return
      if (
        result.verified !== true ||
        !result.verificationId ||
        Date.parse(result.expiresAt) <= Date.now()
      )
        throw new Error(result.message || '未通过验证，请重新采集')
      message.value = '服务端验证通过，请在有效期内提交对应打卡'
      emit('verified', { verificationId: result.verificationId, expiresAt: result.expiresAt })
      stop()
    } else {
      if (enrollment.enrolled) data.append('expectedRevision', String(enrollment.revision))
      await write(enrollment.enrolled ? 'put' : 'post', '/face/enrollment', data)
      if (!current(revision)) return
      message.value = enrollment.enrolled ? '人脸特征已更新，旧验证已失效' : '人脸登记已保存'
      stop()
      consent.value = false
      emit('changed')
    }
  } catch (e) {
    if (!current(revision)) return
    error.value =
      e.request && !e.response ? '结果暂未确认，请恢复连接并刷新登记状态后再操作' : e.message
    emit('verified', null)
  } finally {
    if (revision === operation) busy.value = false
  }
}
async function remove() {
  if (!props.status?.deletionAvailable || busy.value || props.disabled) return
  if (!window.confirm('删除本人的人脸特征并撤销未使用的验证？已有考勤记录会保留。')) return
  reset()
  const revision = ++operation,
    expected = props.status.revision
  busy.value = true
  error.value = message.value = ''
  try {
    await write('delete', `/face/enrollment?expectedRevision=${expected}`)
    if (disposed || revision !== operation) return
    consent.value = false
    message.value = '人脸特征已删除，未使用的验证已撤销'
    emit('changed')
  } catch (e) {
    if (!disposed && revision === operation) error.value = e.message
  } finally {
    if (revision === operation) busy.value = false
  }
}
watch(consent, (allowed) => {
  if (!allowed) reset()
})
watch(
  [
    () => props.context?.token,
    () => props.context?.requestId,
    () => props.context?.type,
    () => props.status?.revision,
  ],
  () => {
    reset()
    message.value = ''
  },
)
watch(
  () => props.disabled,
  (value) => {
    if (value) reset()
  },
)
function visibility() {
  if (document.hidden) reset()
}
window.addEventListener('pagehide', reset)
document.addEventListener('visibilitychange', visibility)
onUnmounted(() => {
  disposed = true
  stop()
  window.removeEventListener('pagehide', reset)
  document.removeEventListener('visibilitychange', visibility)
})
</script>
<template>
  <section
    ref="collectionSection"
    class="face-actions"
    :class="{ 'collection-highlight': guided }"
    tabindex="-1"
  >
    <h2>{{ mode === 'verify' ? '本次打卡人脸验证' : '本人登记与授权管理' }}</h2>
    <p>{{ status?.enrolled ? `已登记 · 版本 ${status.revision}` : '尚未登记本人特征' }}</p>
    <p>
      主动采集后，图像会通过安全连接发送到服务器，由实际SDK校验单人、质量、活体与身份；原图仅用于本次处理，不作业务留存，特征加密保存。可删除特征并撤销未使用的验证。
    </p>
    <p v-if="!status?.available">{{ status?.message || '人脸服务未就绪，采集不可用' }}</p>
    <p v-if="guided && available" class="collection-guide" role="status">{{ collectionGuide }}</p>
    <label
      ><input
        type="checkbox"
        ref="consentInput"
        v-model="consent"
        :disabled="!available || busy"
      />我同意本次采集、服务端识别和加密特征处理</label
    >
    <div class="actions">
      <button
        ref="cameraButton"
        class="button button-secondary"
        :disabled="!available || !consent || cameraBusy || active || busy"
        @click="start"
      >
        {{ cameraBusy ? '等待采集授权…' : '开启采集摄像头' }}
      </button>
      <button v-if="cameraBusy || active" class="button button-secondary" @click="reset">
        取消采集 / 关闭摄像头
      </button>
    </div>
    <video v-if="active" ref="video" muted autoplay playsinline aria-label="人脸采集画面" />
    <div class="actions">
      <button
        ref="captureButton"
        class="button button-primary"
        :disabled="
          !available || !consent || !active || busy || (mode === 'verify' && !status?.enrolled)
        "
        @click="submit"
      >
        {{
          busy
            ? '服务端处理中…'
            : mode === 'verify'
              ? '采集并验证本次打卡'
              : status?.enrolled
                ? '采集并更新人脸'
                : '采集并登记人脸'
        }}
      </button>
      <button
        v-if="mode === 'manage'"
        class="button button-secondary"
        :disabled="!status?.deletionAvailable || busy || disabled"
        @click="remove"
      >
        删除本人特征
      </button>
      <button
        v-if="mode === 'manage'"
        class="button button-secondary"
        :disabled="busy"
        @click="emit('changed')"
      >
        刷新登记状态
      </button>
    </div>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="mode === 'verify'">
      验证只绑定当前员工、二维码、打卡方向和请求，30秒内有效；更换输入、撤回授权或切后台后请重新验证。
    </p>
  </section>
</template>
<style scoped>
.face-actions {
  padding: 22px;
  border: 1px solid #e5ebf4;
  border-radius: 12px;
  background: white;
  margin-bottom: 22px;
  min-width: 0;
}
.collection-highlight {
  outline: 3px solid #2563eb;
  outline-offset: 3px;
  scroll-margin: 90px;
}
.collection-guide {
  padding: 12px 16px;
  border-radius: 8px;
  background: #eff6ff;
  color: #1d4ed8;
  font-weight: 600;
}
h2 {
  font-size: 16px;
}
p,
label {
  font-size: 12px;
  line-height: 1.9;
  color: #65758e;
  margin: 14px 0;
  overflow-wrap: anywhere;
}
label {
  display: flex;
  gap: 8px;
  align-items: start;
}
.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin: 15px 0;
}
video {
  width: 100%;
  max-height: 320px;
  border-radius: 8px;
  object-fit: cover;
}
</style>
