<script setup>
import { onMounted, ref } from 'vue'
import { Check, Right } from '@element-plus/icons-vue'
import http from '../api'
const modules = ref([])
const error = ref('')
async function load() {
  error.value = ''
  try {
    modules.value = await http.get('/system/modules')
  } catch (e) {
    error.value = e.message
  }
}
onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">从基础开始，逐步完善</div>
      <h1>建设进度</h1>
      <p>每个阶段完成后，验证对应业务流程，再继续接入下一块能力。</p>
    </div>
    <span class="tag tag-blue">第一阶段</span>
  </div>
  <div v-if="error" role="alert" class="notice notice-error">
    {{ error }}<button class="text-button" @click="load">重新加载</button>
  </div>
  <template v-else
    ><section class="progress-banner">
      <div>
        <span class="eyebrow">当前已完成</span>
        <h2>系统基础与登录权限</h2>
        <p>角色登录、会话管理、后台框架与员工首页已接通。</p>
      </div>
      <div class="progress-count">
        <b>{{ modules.filter((m) => m.status === 'COMPLETED').length }}</b
        ><span>/ {{ modules.length || '—' }} 个阶段</span>
      </div>
    </section>
    <div class="roadmap-list">
      <article
        v-for="module in modules"
        :key="module.order"
        class="panel roadmap-item"
        :class="{ complete: module.status === 'COMPLETED' }"
      >
        <span class="roadmap-number"
          ><Check v-if="module.status === 'COMPLETED'" /><template v-else>{{
            String(module.order).padStart(2, '0')
          }}</template></span
        >
        <div>
          <h2>{{ module.name }}</h2>
          <p>{{ module.description }}</p>
        </div>
        <span class="tag" :class="module.status === 'COMPLETED' ? 'tag-green' : 'tag-neutral'">{{
          module.status === 'COMPLETED' ? '已完成' : '待开发'
        }}</span>
      </article>
    </div>
    <p class="roadmap-footnote">
      <Right />下一步：建立公司、部门、员工和办公场所之间的关联。
    </p></template
  >
</template>
