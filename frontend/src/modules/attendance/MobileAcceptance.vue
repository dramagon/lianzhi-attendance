<script setup>
import { computed, reactive, ref } from 'vue'
const props = defineProps({ observations: Object })
const model = ref(''),
  system = ref(''),
  browser = ref(''),
  error = ref('')
const checks = reactive(
  [
    ['https', '可信HTTPS与登录会话'],
    ['location_allow', '定位允许与有效范围打卡'],
    ['location_deny', '定位拒绝、超时与恢复'],
    ['camera_qr', '真实摄像头扫码'],
    ['camera_deny', '摄像头拒绝、取消授权与重试'],
    ['background', '切页、切后台释放摄像头'],
    ['qr_expiry', '过期、篡改二维码被拒绝'],
    ['duplicate', '重复点击不产生重复记录'],
    ['offline', '断网不假成功，联网或刷新核对真实回执'],
    ['records', '上下班原始记录与日结果一致'],
  ].map(([id, label]) => ({ id, label, result: 'NOT_RUN' })),
)
const completed = computed(() => checks.filter((check) => check.result !== 'NOT_RUN').length)
function download() {
  error.value = ''
  if (!model.value.trim() || !system.value.trim() || !browser.value.trim()) {
    error.value = '请填写实际手机型号、系统版本和浏览器版本；未执行的项目保留“未执行”'
    return
  }
  const report = {
    schemaVersion: 1,
    scope: 'H5手机人工验收',
    createdAt: new Date().toISOString(),
    device: {
      model: model.value.trim(),
      system: system.value.trim(),
      browser: browser.value.trim(),
    },
    observations: { ...props.observations },
    checks: checks.map((check) => ({ ...check })),
    faceSdkAcceptance: 'NOT_TESTED',
  }
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }),
  )
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `lianzhi-mobile-${new Date().toISOString().slice(0, 10)}.json`
  anchor.click()
  // Keep the blob available until the browser has started consuming the download.
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>
<template>
  <section class="panel acceptance">
    <h2>保存本机验收结果</h2>
    <p>
      按实际手机操作逐项记录。设备检查结果不自动勾选业务通过；未做的保留“未执行”。报告仅下载到当前设备，不上传，不包含坐标、二维码、账号或人脸图像。
    </p>
    <div class="fields">
      <label>手机型号<input v-model="model" maxlength="80" placeholder="填写实际测试设备" /></label>
      <label
        >系统版本<input v-model="system" maxlength="80" placeholder="如实际Android或iOS版本"
      /></label>
      <label
        >浏览器版本<input v-model="browser" maxlength="80" placeholder="填写实际浏览器及版本"
      /></label>
    </div>
    <label v-for="check in checks" :key="check.id" class="check"
      >{{ check.label
      }}<select v-model="check.result" :aria-label="check.label">
        <option value="NOT_RUN">未执行</option>
        <option value="PASS">通过</option>
        <option value="FAIL">失败</option>
      </select></label
    >
    <p>
      已记录 {{ completed }}/{{ checks.length }}
      项。人脸SDK须单独实际验收，本报告不标记人脸识别完成。
    </p>
    <button class="button button-secondary" @click="download">下载本机验收报告</button>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>
<style scoped>
.acceptance {
  padding: 22px;
  margin-bottom: 22px;
}
h2 {
  font-size: 16px;
}
p,
label {
  font-size: 12px;
  color: #65758e;
  line-height: 1.9;
  margin: 14px 0;
}
.fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.fields label {
  display: block;
}
input,
select {
  display: block;
  width: 100%;
  min-width: 0;
  padding: 10px;
  border: 1px solid #dce4ef;
  border-radius: 8px;
  font: inherit;
}
.check {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.check select {
  flex: 0 0 110px;
}
@media (max-width: 700px) {
  .fields {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
  }
}
</style>
