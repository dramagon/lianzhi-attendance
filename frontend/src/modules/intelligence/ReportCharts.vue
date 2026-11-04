<script setup>
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  AriaComponent,
} from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
echarts.use([
  LineChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  AriaComponent,
  SVGRenderer,
])
const props = defineProps({ trend: { type: Array, required: true } })
const element = ref(null),
  error = ref('')
let chart, observer
function render() {
  if (!chart) return
  try {
    chart.setOption(
      {
        animation: false,
        aria: { enabled: true },
        tooltip: { trigger: 'axis', renderMode: 'richText' },
        legend: { data: ['有效工时', '请假', '加班'] },
        grid: { left: 58, right: 20, top: 45, bottom: 75 },
        xAxis: { type: 'category', data: props.trend.map((d) => d.date) },
        yAxis: { type: 'value', name: '分钟' },
        dataZoom: [{ type: 'inside' }, { type: 'slider', bottom: 5 }],
        series: [
          {
            name: '有效工时',
            type: 'line',
            data: props.trend.map((d) => d.totals.workMinutes),
            showSymbol: props.trend.length < 32,
          },
          { name: '请假', type: 'bar', data: props.trend.map((d) => d.totals.leaveMinutes) },
          { name: '加班', type: 'bar', data: props.trend.map((d) => d.totals.overtimeMinutes) },
        ],
      },
      { notMerge: true },
    )
  } catch {
    error.value = '图表暂不可用，请查看下方逐日数据表。'
  }
}
onMounted(() => {
  chart = echarts.init(element.value, null, { renderer: 'svg' })
  render()
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(element.value)
})
watch(() => props.trend, render, { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>
<template>
  <div>
    <p v-if="error" role="status">{{ error }}</p>
    <div
      ref="element"
      class="echart"
      role="img"
      aria-label="ECharts日工时、请假与加班趋势，可切换图例及缩放，逐日数值见下方表格"
    ></div>
  </div>
</template>
<style scoped>
.echart {
  width: 100%;
  height: 320px;
  min-width: 0;
}
@media (max-width: 600px) {
  .echart {
    height: 280px;
  }
}
</style>
