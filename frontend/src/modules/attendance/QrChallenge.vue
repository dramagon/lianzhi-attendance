<script setup>
import { ref, watch, onUnmounted } from 'vue'
import QRCode from 'qrcode'
const props = defineProps({ token: String, expiresAt: String })
const src = ref(''),
  seconds = ref(0),
  error = ref('')
let timer
watch(
  () => props.token,
  async (value) => {
    src.value = ''
    error.value = ''
    try {
      const image = await QRCode.toDataURL(value, {
        width: 300,
        margin: 3,
        errorCorrectionLevel: 'M',
      })
      if (value === props.token) src.value = image
    } catch {
      error.value = '二维码生成失败，请刷新挑战。'
    }
  },
  { immediate: true },
)
function tick() {
  seconds.value = Math.max(0, Math.ceil((new Date(props.expiresAt).getTime() - Date.now()) / 1000))
}
tick()
timer = setInterval(tick, 1000)
onUnmounted(() => clearInterval(timer))
</script>
<template>
  <div class="qr">
    <img v-if="src && seconds" :src="src" alt="办公场所打卡二维码" />
    <p role="status">{{ seconds ? `有效期剩余 ${seconds} 秒` : '二维码已过期，请重新生成' }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.qr {
  text-align: center;
}
img {
  width: 240px;
  max-width: 100%;
  height: auto;
  background: white;
  border-radius: 8px;
}
p {
  font-size: 12px;
  color: #6882a5;
  margin: 10px 0;
}
</style>
