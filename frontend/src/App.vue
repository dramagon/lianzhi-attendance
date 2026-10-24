<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const expired = () => {
  if (!auth.user) return
  auth.discard()
  router.replace({ name: 'login', query: { expired: '1', redirect: route.fullPath } })
}
onMounted(() => window.addEventListener('session-expired', expired))
onUnmounted(() => window.removeEventListener('session-expired', expired))
</script>

<template><RouterView /></template>
