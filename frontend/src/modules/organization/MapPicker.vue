<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
const emit = defineEmits(['pick'])
const container = ref(null)
const message = ref('')
const key = import.meta.env.VITE_AMAP_KEY
let map
let disposed = false
onMounted(async () => {
  if (!key) {
    message.value = '地图选点尚未接入：未配置高德地图凭据。可手动录入坐标或使用设备定位。'
    return
  }
  try {
    if (!window.AMap) {
      window._AMapSecurityConfig = { securityJsCode: import.meta.env.VITE_AMAP_SECURITY_CODE || '' }
      await new Promise((resolve, reject) => {
        const script = document.createElement('script')
        script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`
        script.onload = resolve
        script.onerror = reject
        document.head.appendChild(script)
      })
    }
    if (disposed) return
    map = new window.AMap.Map(container.value, { zoom: 12 })
    map.on('click', (event) => {
      emit('pick', {
        latitude: Number(event.lnglat.getLat().toFixed(7)),
        longitude: Number(event.lnglat.getLng().toFixed(7)),
        coordinateSystem: 'GCJ02',
      })
      message.value = '已采集高德地图坐标（GCJ02），请核对后保存。'
    })
    message.value = '点击地图采集坐标（GCJ02）。'
  } catch {
    message.value = '地图加载失败，请检查本机凭据、域名白名单和网络。可手动录入坐标。'
  }
})
onBeforeUnmount(() => {
  disposed = true
  map?.destroy()
})
</script>
<template>
  <div class="map-picker">
    <div v-if="key" ref="container" class="map-canvas" aria-label="办公场所地图"></div>
    <p class="muted small-text" role="status">{{ message }}</p>
  </div>
</template>
<style scoped>
.map-canvas {
  height: 260px;
  width: 100%;
}
.map-picker {
  grid-column: 1 / -1;
}
</style>
