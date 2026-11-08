<script setup>
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { loadAMap } from './amap'
const props = defineProps({ latitude: Number, longitude: Number, coordinateSystem: String })
const emit = defineEmits(['pick'])
const container = ref(null)
const message = ref('')
const key = import.meta.env.VITE_AMAP_KEY?.trim()
const securityCode = import.meta.env.VITE_AMAP_SECURITY_CODE?.trim()
const configured = key && securityCode
const loading = ref(false)
const failed = ref(false)
const preview = ref(null)
let map, marker, sdk
let disposed = false
function clearPreview() {
  preview.value = null
  marker?.setMap(null)
  marker = null
}
function showSaved() {
  clearPreview()
  if (
    !map ||
    props.coordinateSystem !== 'GCJ02' ||
    !Number.isFinite(props.latitude) ||
    !Number.isFinite(props.longitude)
  )
    return
  const position = [props.longitude, props.latitude]
  marker = new sdk.Marker({ position, map })
  map.setCenter(position)
}
watch(() => [props.latitude, props.longitude, props.coordinateSystem], showSaved)
async function load() {
  if (loading.value || disposed) return
  if (!configured) {
    message.value =
      '地图选点尚未接入：请在本机配置高德Key和安全密钥。可手动录入坐标或使用设备定位。'
    return
  }
  loading.value = true
  failed.value = false
  message.value = '正在加载地图…'
  try {
    sdk = await loadAMap(key, securityCode)
    if (disposed) return
    map = new sdk.Map(container.value, { zoom: 12 })
    showSaved()
    map.on('click', (event) => {
      if (disposed) return
      const latitude = event.lnglat.getLat(),
        longitude = event.lnglat.getLng()
      if (
        ![latitude, longitude].every(Number.isFinite) ||
        Math.abs(latitude) > 90 ||
        Math.abs(longitude) > 180
      )
        return
      clearPreview()
      preview.value = {
        latitude: Number(latitude.toFixed(7)),
        longitude: Number(longitude.toFixed(7)),
        coordinateSystem: 'GCJ02',
      }
      marker = new sdk.Marker({ position: [longitude, latitude], map })
    })
    message.value = '点击地图预览位置，确认采用后才填入场所坐标。'
  } catch {
    if (disposed) return
    map?.destroy()
    map = null
    failed.value = true
    message.value = '地图加载失败或超时，请核对本机凭据、域名白名单和网络；也可手动录入坐标。'
  } finally {
    if (!disposed) loading.value = false
  }
}
function usePoint() {
  if (!preview.value) return
  const point = { ...preview.value }
  clearPreview()
  emit('pick', point)
}
onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  clearPreview()
  map?.destroy()
})
</script>
<template>
  <div class="map-picker">
    <div v-if="configured" ref="container" class="map-canvas" aria-label="办公场所地图"></div>
    <p class="muted small-text" role="status">{{ message }}</p>
    <button v-if="failed" type="button" class="button button-secondary" @click="load">
      重新加载地图
    </button>
    <div v-if="preview" class="notice" role="status">
      <p>地图位置预览（GCJ02）：{{ preview.latitude }}，{{ preview.longitude }}。</p>
      <p>采用后填入GCJ02坐标，保存并绑定员工后可排班。服务端换算坐标后校验打卡距离。</p>
      <button type="button" class="button button-secondary" @click="usePoint">采用地图位置</button>
      <button type="button" class="button button-secondary" @click="showSaved">放弃地图位置</button>
    </div>
  </div>
</template>
<style scoped>
.map-canvas {
  height: 260px;
  width: 100%;
}
.map-picker {
  grid-column: 1 / -1;
  min-width: 0;
}
</style>
