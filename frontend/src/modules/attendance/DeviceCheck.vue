<script setup>
import { ref, nextTick, onMounted, onUnmounted } from 'vue'
import http from '../../api'
import QrScanner from './QrScanner.vue'
import MobileAcceptance from './MobileAcceptance.vue'
const secure = window.isSecureContext
const https = window.location.protocol === 'https:'
const supportsCamera = !!navigator.mediaDevices?.getUserMedia
const supportsLocation = !!navigator.geolocation
const online = ref(navigator.onLine),
  connected = ref(null),
  networkBusy = ref(false),
  networkError = ref('')
const locationBusy = ref(false),
  locationStatus = ref('尚未检查'),
  cameraBusy = ref(false),
  cameraStatus = ref('尚未开启')
const cameraActive = ref(false),
  consent = ref(false),
  video = ref(null),
  qrStatus = ref('尚未检查')
let disposed = false,
  networkRevision = 0,
  locationRevision = 0,
  cameraRevision = 0,
  stream
async function connection() {
  const revision = ++networkRevision
  networkBusy.value = true
  networkError.value = ''
  try {
    const data = await http.get('/system/info')
    if (!disposed && revision === networkRevision) connected.value = data.name === '联智'
  } catch (e) {
    if (!disposed && revision === networkRevision) {
      connected.value = false
      networkError.value = e.message
    }
  } finally {
    if (!disposed && revision === networkRevision) networkBusy.value = false
  }
}
function locate() {
  const revision = ++locationRevision
  if (!secure || !supportsLocation) {
    locationStatus.value = '需要可信HTTPS和支持定位的浏览器'
    return
  }
  locationBusy.value = true
  locationStatus.value = '等待设备授权与定位…'
  navigator.geolocation.getCurrentPosition(
    (p) => {
      if (disposed || revision !== locationRevision) return
      locationBusy.value = false
      locationStatus.value = `已获取定位，精度约 ${Math.round(p.coords.accuracy)} 米；是否可打卡仍由场所范围和服务器校验`
    },
    (e) => {
      if (disposed || revision !== locationRevision) return
      locationBusy.value = false
      locationStatus.value =
        {
          1: '定位权限被拒绝，可在浏览器设置中允许后重试',
          2: '暂无法获取位置，请检查设备定位或移动到开阔处',
          3: '定位超时，请重试',
        }[e.code] || '定位失败，请重试'
    },
    { enableHighAccuracy: true, timeout: 12000, maximumAge: 0 },
  )
}
function stopCamera() {
  cameraRevision++
  stream?.getTracks().forEach((t) => t.stop())
  stream = null
  if (video.value) video.value.srcObject = null
  cameraBusy.value = false
  cameraActive.value = false
}
async function camera() {
  if (!secure || !supportsCamera || !consent.value) return
  stopCamera()
  const revision = cameraRevision
  cameraBusy.value = true
  cameraStatus.value = '等待摄像头授权…'
  try {
    const media = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'user' },
      audio: false,
    })
    if (disposed || revision !== cameraRevision) {
      media.getTracks().forEach((t) => t.stop())
      return
    }
    stream = media
    cameraActive.value = true
    await nextTick()
    if (disposed || revision !== cameraRevision) return
    video.value.srcObject = media
    await video.value.play()
    if (!disposed && revision === cameraRevision)
      cameraStatus.value = '本机预览正常；尚未进行人脸识别或上传图像'
  } catch (e) {
    if (disposed || revision !== cameraRevision) return
    stopCamera()
    cameraStatus.value =
      e.name === 'NotAllowedError'
        ? '摄像头权限被拒绝，请允许后重试'
        : '摄像头不可用，请检查是否被其他应用占用'
  } finally {
    if (!disposed && revision === cameraRevision) cameraBusy.value = false
  }
}
function closePreview() {
  stopCamera()
  cameraStatus.value = '预览已关闭'
}
function revoke() {
  if (!consent.value) {
    stopCamera()
    cameraStatus.value = '已撤回预览授权'
  }
}
function suspend() {
  stopCamera()
  locationRevision++
  locationBusy.value = false
  cameraStatus.value = '预览已关闭，返回后可重新检查'
}
function visibility() {
  if (document.hidden) suspend()
}
function network() {
  online.value = navigator.onLine
  connected.value = null
  networkError.value = ''
  networkRevision++
  networkBusy.value = false
}
onMounted(() => {
  connection()
  window.addEventListener('online', network)
  window.addEventListener('offline', network)
  window.addEventListener('pagehide', suspend)
  document.addEventListener('visibilitychange', visibility)
})
onUnmounted(() => {
  disposed = true
  networkRevision++
  locationRevision++
  stopCamera()
  window.removeEventListener('online', network)
  window.removeEventListener('offline', network)
  window.removeEventListener('pagehide', suspend)
  document.removeEventListener('visibilitychange', visibility)
})
</script>
<template>
  <div class="device">
    <div class="page-heading">
      <div>
        <span class="eyebrow">手机验收准备</span>
        <h1>检查当前设备</h1>
        <p>在准备打卡的手机浏览器中逐项检查；本页不提交打卡、不登记人脸。</p>
      </div>
      <RouterLink class="button button-secondary" to="/attendance">返回打卡工作台</RouterLink>
    </div>
    <section class="panel block">
      <h2>连接与安全环境</h2>
      <dl>
        <div>
          <dt>安全上下文</dt>
          <dd>
            {{ secure ? '已具备HTTPS或本机安全上下文' : '未具备，请使用已受信任的HTTPS入口' }}
          </dd>
        </div>
        <div>
          <dt>网络状态</dt>
          <dd>{{ online ? '设备报告在线' : '设备报告离线' }}</dd>
        </div>
        <div>
          <dt>服务连接</dt>
          <dd>{{ connected === null ? '尚未确认' : connected ? '已连接联智服务' : '暂未连接' }}</dd>
        </div>
      </dl>
      <button class="button button-secondary" :disabled="networkBusy" @click="connection">
        {{ networkBusy ? '检查中…' : '检查服务连接' }}
      </button>
      <p v-if="networkError" role="alert">{{ networkError }}</p>
      <p>浏览器显示在线不代表服务可达；更换网络或恢复连接后可重新检查。</p>
    </section>
    <div class="grid">
      <section class="panel block">
        <h2>定位权限</h2>
        <p>{{ supportsLocation ? '浏览器提供定位接口' : '浏览器没有可用的定位接口' }}</p>
        <button
          class="button button-primary"
          :disabled="locationBusy || !secure || !supportsLocation"
          @click="locate"
        >
          {{ locationBusy ? '定位中…' : '检查真实定位' }}
        </button>
        <p role="status">{{ locationStatus }}</p>
        <p>结果只在本页展示精度，不上传定位，不记录经纬度。</p>
      </section>
      <section class="panel block">
        <h2>摄像头预览</h2>
        <p>
          {{
            supportsCamera ? '浏览器提供摄像头接口' : '摄像头接口不可用，请检查HTTPS或浏览器支持'
          }}
        </p>
        <label
          ><input
            v-model="consent"
            type="checkbox"
            @change="revoke"
          />允许仅在本机预览，不上传、不登记</label
        >
        <div class="actions">
          <button
            class="button button-primary"
            :disabled="!consent || !secure || !supportsCamera || cameraBusy || cameraActive"
            @click="camera"
          >
            {{ cameraBusy ? '等待授权…' : '检查摄像头' }}</button
          ><button
            v-if="cameraBusy || cameraActive"
            class="button button-secondary"
            @click="closePreview"
          >
            关闭预览
          </button>
        </div>
        <video
          v-if="cameraActive"
          ref="video"
          autoplay
          muted
          playsinline
          aria-label="设备检查摄像头预览"
        />
        <p role="status">{{ cameraStatus }}</p>
      </section>
    </div>
    <section class="panel block">
      <h2>扫码识别</h2>
      <p>
        可识别管理员生成的考勤二维码。读取内容不代表签名或有效期已通过，实际打卡时仍由服务器校验。
      </p>
      <QrScanner @scanned="qrStatus = '已读取二维码内容，请返回打卡工作台完成服务端验证'" />
      <p role="status">{{ qrStatus }}</p>
    </section>
    <section class="panel block">
      <h2>下一步真机验收</h2>
      <ol>
        <li>使用自己的授权测试账号，确认正式排班和有效办公场所。</li>
        <li>扫码、获取真实定位，完成一次上下班并查看记录。</li>
        <li>验证拒绝授权、二维码到期、距离超限、断网重试及重复点击。</li>
        <li>记录手机型号、系统、浏览器和实际结果；本页检查通过不能代替真实打卡或人脸SDK验收。</li>
      </ol>
    </section>
    <MobileAcceptance
      :observations="{
        secureContext: secure,
        https,
        online,
        serviceConnected: connected,
        location: locationStatus,
        camera: cameraStatus,
        qr: qrStatus,
      }"
    />
  </div>
</template>
<style scoped>
.block {
  padding: 24px;
  margin-bottom: 22px;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 22px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  margin: 18px 0;
}
p,
li,
label,
dl {
  font-size: 12px;
  color: #72849e;
  line-height: 1.9;
}
p {
  margin: 15px 0;
}
label {
  display: flex;
  align-items: center;
  gap: 8px;
}
input {
  accent-color: #2563eb;
}
dl > div {
  display: flex;
  gap: 15px;
  padding: 10px 0;
}
dt {
  min-width: 90px;
}
dd {
  margin: 0;
}
video {
  width: 100%;
  max-height: 300px;
  object-fit: cover;
  border-radius: 8px;
}
@media (max-width: 700px) {
  .grid {
    grid-template-columns: 1fr;
    gap: 0;
  }
  .block {
    padding: 18px;
  }
  .page-heading {
    flex-wrap: wrap;
  }
}
</style>
