<script setup>
import { useRoute } from 'vue-router'
const route = useRoute()
</script>

<template>
  <section class="status-page panel">
    <span class="status-code">{{ route.meta.status }}</span>
    <h1>{{ route.meta.status === '403' ? '当前账号没有访问权限' : '这个页面暂时找不到' }}</h1>
    <p class="muted">
      {{
        route.meta.status === '403'
          ? '请使用已获授权的账号，或联系系统管理员。'
          : '请检查页面地址，或返回工作台继续。'
      }}
    </p>
    <RouterLink to="/workbench" class="button button-primary">返回工作台</RouterLink>
  </section>
</template>
