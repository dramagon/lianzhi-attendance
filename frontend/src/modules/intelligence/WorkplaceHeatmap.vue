<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { loadAMap } from '../organization/amap'
const props = defineProps({ places: { type: Array, required: true } })
const container = ref(null),
  message = ref('点击加载后，按办公场所登记坐标显示地图热力。'),
  loading = ref(false)
let generation = 0,
  map
function close() {
  generation++
  map?.destroy()
  map = null
  loading.value = false
}
function bounded(work) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('地图处理超时，可重试')), 12000)
    work(
      (value) => {
        clearTimeout(timer)
        resolve(value)
      },
      (error) => {
        clearTimeout(timer)
        reject(error)
      },
    )
  })
}
async function load() {
  close()
  const sequence = generation
  loading.value = true
  try {
    const key = import.meta.env.VITE_AMAP_KEY?.trim(),
      security = import.meta.env.VITE_AMAP_SECURITY_CODE?.trim()
    if (!key || !security)
      throw new Error('地图凭据未配置。当前场所表和相对分布仍可查看；地图需配置高德Key及安全码。')
    const places = props.places.filter((p) => p.attendedDays > 0)
    if (!places.length) throw new Error('当前范围没有有打卡的场所，不绘制热力。')
    if (places.length > 200) throw new Error('地图单次最多200个场所，请缩小筛选范围。')
    const sdk = await loadAMap(key, security)
    if (sequence !== generation) return
    await bounded((resolve) => sdk.plugin(['AMap.HeatMap'], () => resolve()))
    if (sequence !== generation) return
    if (!sdk.HeatMap) throw new Error('地图热力插件未就绪，可重试。')
    const points = []
    for (const system of ['GCJ02', 'WGS84', 'BD09']) {
      const group = places.filter((p) => p.coordinateSystem === system)
      for (let i = 0; i < group.length; i += 40) {
        const batch = group.slice(i, i + 40)
        let coordinates = batch.map((p) => [Number(p.longitude), Number(p.latitude)])
        if (system !== 'GCJ02')
          coordinates = await bounded((resolve, reject) =>
            sdk.convertFrom(coordinates, system === 'WGS84' ? 'gps' : 'baidu', (state, data) =>
              state === 'complete' && data.locations?.length === batch.length
                ? resolve(data.locations.map((p) => [p.getLng?.() ?? p.lng, p.getLat?.() ?? p.lat]))
                : reject(new Error('坐标转换失败，未把其他坐标系当作高德坐标。')),
            ),
          )
        if (sequence !== generation) return
        coordinates.forEach(([lng, lat], index) => {
          if (
            !Number.isFinite(lng) ||
            !Number.isFinite(lat) ||
            Math.abs(lng) > 180 ||
            Math.abs(lat) > 90
          )
            throw new Error('地图返回的坐标无效')
          points.push({ lng, lat, count: batch[index].attendedDays })
        })
      }
    }
    if (sequence !== generation) return
    map = new sdk.Map(container.value, {
      center: [points[0].lng, points[0].lat],
      zoom: 12,
      resizeEnable: true,
    })
    const heat = new sdk.HeatMap(map, { radius: 30, opacity: [0, 0.8] })
    heat.setDataSet({ data: points, max: Math.max(...points.map((p) => p.count)) })
    if (points.length > 1) {
      const xs = points.map((p) => p.lng),
        ys = points.map((p) => p.lat)
      if (Math.min(...xs) !== Math.max(...xs) && Math.min(...ys) !== Math.max(...ys))
        map.setBounds(
          new sdk.Bounds([Math.min(...xs), Math.min(...ys)], [Math.max(...xs), Math.max(...ys)]),
        )
    }
    message.value = `已显示${points.length}个场所，热度为有签到或签退的人天；非实时员工位置。`
  } catch (e) {
    if (sequence === generation) message.value = e.message || '地图热力加载失败，可重试。'
  } finally {
    if (sequence === generation) loading.value = false
  }
}
watch(
  () => props.places,
  () => {
    close()
    message.value = '范围已变化，请重新加载地图热力。'
  },
)
onBeforeUnmount(close)
</script>
<template>
  <section class="map-heat">
    <div class="heat-controls">
      <button class="button button-secondary" :disabled="loading" @click="load">
        {{ loading ? '加载地图热力中…' : '加载地图热力图' }}</button
      ><button v-if="loading" class="button button-secondary" @click="close">取消加载</button>
    </div>
    <p role="status">{{ message }}</p>
    <div
      ref="container"
      class="heat-map"
      role="img"
      aria-label="办公场所有打卡人天地图热力图"
    ></div>
  </section>
</template>
<style scoped>
.map-heat {
  margin: 22px 0;
}
.heat-map {
  width: 100%;
  height: 320px;
  max-width: 100%;
  background: #f8fafc;
  border-radius: 12px;
  overflow: hidden;
}
.heat-controls {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.map-heat p {
  line-height: 1.7;
  color: #64748b;
}
</style>
