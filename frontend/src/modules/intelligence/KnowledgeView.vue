<script setup>
import { onMounted, ref } from 'vue'
import http from '../../api'

const status = ref(null)
const loading = ref(false)
const error = ref('')
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  status.value = null
  try {
    status.value = await http.get('/knowledge/status')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">制度与资料</div>
      <h1>企业知识库</h1>
      <p>检索有权阅读的资料，并查看答案对应的文档来源。</p>
    </div>
    <button class="button button-secondary" :disabled="loading" @click="load">刷新状态</button>
  </div>
  <p v-if="loading" role="status" class="muted">正在检查知识库状态…</p>
  <div v-else-if="error" role="alert" class="notice notice-error">
    {{ error }}<button class="text-button" @click="load">重新加载</button>
  </div>
  <template v-else-if="status">
    <section class="panel knowledge-status" aria-labelledby="knowledge-state-title">
      <span class="tag tag-neutral">尚未开放</span>
      <h2 id="knowledge-state-title">知识库正在接入</h2>
      <p class="muted">接入完成后，你可以上传资料、查看处理进度并检索文档内容。</p>
      <ul v-if="status.blockers?.length" class="muted">
        <li v-for="blocker in status.blockers" :key="blocker">{{ blocker }}</li>
      </ul>
    </section>
    <section class="panel knowledge-actions" aria-label="知识库操作">
      <div class="panel-heading"><h2>文档检索</h2></div>
      <label for="knowledge-query">想查找的制度或问题</label>
      <div class="knowledge-search">
        <input id="knowledge-query" placeholder="知识库开放后可检索" disabled />
        <button class="button button-primary" disabled>检索</button>
        <button class="button button-secondary" disabled>上传文档</button>
      </div>
      <p class="muted small-text">文档列表、处理进度、来源查看、更新和删除尚未开放。</p>
    </section>
  </template>
</template>

<style scoped>
.knowledge-status,
.knowledge-actions {
  margin-bottom: 20px;
  padding: 24px;
}
.page-heading > button {
  flex-shrink: 0;
  white-space: nowrap;
}
.knowledge-status h2 {
  margin-top: 14px;
}
.knowledge-status li {
  margin-bottom: 8px;
}
.knowledge-search {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 10px;
}
.knowledge-search input {
  flex: 1 1 220px;
  min-width: 0;
  border: 1px solid #d6dce5;
  border-radius: 8px;
  padding: 10px 12px;
}
@media (max-width: 600px) {
  .knowledge-status,
  .knowledge-actions {
    padding: 18px;
  }
}
</style>
