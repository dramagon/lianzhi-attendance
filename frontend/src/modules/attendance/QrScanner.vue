<script setup>
import { ref, nextTick, onUnmounted } from 'vue'
import jsQR from 'jsqr'
const emit = defineEmits(['scanned'])
const active = ref(false),
  busy = ref(false),
  error = ref(''),
  video = ref(null)
let stream,
  frame,
  revision = 0
function stop() {
  revision++
  cancelAnimationFrame(frame)
  stream?.getTracks().forEach((t) => t.stop())
  stream = null
  active.value = false
  busy.value = false
}
function accept(data) {
  if (!/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(data) || data.length > 512) {
    error.value = '图片中的二维码不是有效的考勤挑战，请确认来源。'
    return false
  }
  emit('scanned', data)
  stop()
  return true
}
function detect(source, width, height) {
  const canvas = document.createElement('canvas')
  const scale = Math.min(1, 1600 / Math.max(width, height))
  canvas.width = Math.round(width * scale)
  canvas.height = Math.round(height * scale)
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  ctx.drawImage(source, 0, 0, canvas.width, canvas.height)
  const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height)
  return jsQR(pixels.data, pixels.width, pixels.height)?.data
}
async function start() {
  stop()
  error.value = ''
  const current = revision
  if (!navigator.mediaDevices?.getUserMedia || !window.isSecureContext) {
    error.value = '扫码需要 HTTPS 与摄像头支持，也可选择本机二维码图片。'
    return
  }
  busy.value = true
  try {
    const media = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'environment' },
      audio: false,
    })
    if (current !== revision) {
      media.getTracks().forEach((t) => t.stop())
      return
    }
    stream = media
    active.value = true
    await nextTick()
    video.value.srcObject = stream
    await video.value.play()
    let last = 0
    const scan = (now) => {
      if (!active.value || current !== revision) return
      if (now - last > 250 && video.value?.videoWidth) {
        last = now
        const data = detect(video.value, video.value.videoWidth, video.value.videoHeight)
        if (data && accept(data)) return
      }
      frame = requestAnimationFrame(scan)
    }
    frame = requestAnimationFrame(scan)
  } catch (e) {
    stop()
    error.value =
      e.name === 'NotAllowedError'
        ? '摄像头权限被拒绝，请允许后重试，或识别本机图片。'
        : '摄像头不可用，请检查占用情况或选择二维码图片。'
  } finally {
    if (current === revision) busy.value = false
  }
}
async function image(event) {
  error.value = ''
  const file = event.target.files[0]
  event.target.value = ''
  if (!file) return
  if (file.size > 10 * 1024 * 1024) {
    error.value = '请选择10MB以内的二维码图片。'
    return
  }
  let bitmap
  try {
    bitmap = await createImageBitmap(file)
    if (bitmap.width > 6000 || bitmap.height > 6000) throw new Error()
    const data = detect(bitmap, bitmap.width, bitmap.height)
    if (!data) error.value = '没有识别到二维码，请选择清晰的完整图片。'
    else accept(data)
  } catch {
    error.value = '图片无法识别，请选择清晰的PNG或JPG二维码图片。'
  } finally {
    bitmap?.close()
  }
}
onUnmounted(stop)
</script>
<template>
  <div class="scanner">
    <div class="actions">
      <button
        type="button"
        class="button button-secondary"
        :disabled="busy || active"
        @click="start"
      >
        {{ busy ? '等待摄像头授权…' : '摄像头扫码' }}</button
      ><label class="button button-secondary"
        >识别二维码图片<input
          aria-label="识别二维码图片"
          type="file"
          accept="image/png,image/jpeg,image/webp"
          @change="image" /></label
      ><button v-if="active" type="button" class="button button-secondary" @click="stop">
        关闭扫码
      </button>
    </div>
    <video v-if="active" ref="video" autoplay muted playsinline aria-label="扫码摄像头" />
    <p>仅本机识别，不上传二维码图片；识别成功后自动关闭摄像头。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
  </div>
</template>
<style scoped>
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.actions > * {
  font-size: 12px;
}
.actions label {
  cursor: pointer;
  position: relative;
  overflow: hidden;
}
.actions input {
  position: absolute;
  inset: 0;
  opacity: 0;
  width: 100%;
  cursor: pointer;
}
video {
  width: 100%;
  max-height: 300px;
  object-fit: cover;
  border-radius: 10px;
  margin-top: 12px;
}
p {
  font-size: 11px;
  color: #7e8da3;
  margin: 12px 0;
}
.error {
  color: #b44949;
}
</style>
