<script setup>
import { computed, ref, watch } from 'vue'
const props = defineProps({
  user: { type: Object, default: null },
  size: { type: String, default: 'small' },
})
const failed = ref(false)
watch(
  () => props.user?.avatarUrl,
  () => {
    failed.value = false
  },
)
const name = computed(() => props.user?.displayName?.trim() || props.user?.username || '用户')
const label = computed(() => {
  const shortName = name.value.replace(/^(演示|系统)(?=管理员|人事|员工)/, '')
  return Array.from(shortName).length <= 4 ? shortName : Array.from(shortName).slice(0, 2).join('')
})
</script>
<template>
  <span
    class="user-avatar"
    :class="[`user-avatar--${size}`, { 'user-avatar--compact': Array.from(label).length > 2 }]"
    :title="name"
    role="img"
    :aria-label="`${name}的头像`"
  >
    <img v-if="user?.avatarUrl && !failed" :src="user.avatarUrl" alt="" @error="failed = true" />
    <span v-else>{{ label }}</span>
  </span>
</template>
<style scoped>
.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
  border-radius: 50%;
  background: linear-gradient(145deg, #f0f3ff, #dfe7ff);
  border: 1px solid #d9e1fa;
  color: #40509a;
  font-weight: 650;
  letter-spacing: 0.02em;
  box-shadow:
    inset 0 1px 0 #fff,
    0 2px 6px #243b6b08;
  vertical-align: middle;
}
.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.user-avatar--small {
  width: 36px;
  height: 36px;
  font-size: 12px;
}
.user-avatar--large {
  width: 56px;
  height: 56px;
  font-size: 18px;
}
.user-avatar--profile {
  width: 88px;
  height: 88px;
  font-size: 28px;
}
.user-avatar--small.user-avatar--compact {
  font-size: 10px;
}
.user-avatar--large.user-avatar--compact {
  font-size: 14px;
}
.user-avatar--profile.user-avatar--compact {
  font-size: 21px;
}
</style>
