<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import http, { write } from '../../api'
import { useAuthStore } from '../../stores/auth'
const auth = useAuthStore(),
  status = ref(null),
  documents = ref(null),
  page = ref(1),
  error = ref(''),
  message = ref('')
const busy = ref(false),
  searchBusy = ref(false),
  file = ref(null),
  fileInput = ref(null),
  replacement = ref(null)
const company = ref(auth.user?.companyId || ''),
  visibility = ref('PRIVATE'),
  question = ref(''),
  mode = ref('TEXT'),
  hits = ref(null),
  source = ref(null),
  history = ref(null)
const manager = computed(() => ['ADMIN', 'HR'].includes(auth.user?.role))
const states = {
  QUEUED: '等待解析',
  PROCESSING: '解析中',
  PARSED: '解析完成',
  NO_TEXT: '没有正文',
  DEPENDENCY_UNAVAILABLE: '外部服务未配置',
  FAILED: '处理失败',
}
let alive = true,
  listSequence = 0,
  searchSequence = 0,
  sourceSequence = 0,
  timer
const canManage = (doc) =>
  manager.value || (doc.access.ownerUserId === auth.user?.id && doc.access.visibility === 'PRIVATE')
const where = (s) =>
  s.kind === 'PAGE'
    ? `第${s.ordinal}页`
    : s.kind === 'SLIDE'
      ? `第${s.ordinal}张幻灯片`
      : s.kind === 'TIME_RANGE'
        ? `${(s.startMillis / 1000).toFixed(1)}–${(s.endMillis / 1000).toFixed(1)}秒`
        : s.name || '文件正文'
function clearResults() {
  searchSequence++
  sourceSequence++
  hits.value = null
  source.value = null
  history.value = null
}
watch([question, mode], clearResults)
async function load(target = page.value) {
  const sequence = ++listSequence
  try {
    const next = await http.get('/knowledge/status')
    if (!alive || sequence !== listSequence) return
    status.value = next
    if (next.available) {
      const result = await http.get('/knowledge/documents', { params: { page: target, size: 10 } })
      if (alive && sequence === listSequence) {
        documents.value = result
        page.value = target
      }
    }
  } catch (e) {
    if (alive && sequence === listSequence) error.value = e.message
  }
}
function selectFile(event) {
  file.value = event.target.files?.[0] || null
  message.value = ''
}
function cancelReplace() {
  replacement.value = null
  file.value = null
  if (fileInput.value) fileInput.value.value = ''
}
function prepareReplace(doc) {
  replacement.value = doc
  company.value = doc.access.companyId
  visibility.value = doc.access.visibility
  file.value = null
  if (fileInput.value) fileInput.value.value = ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
async function upload() {
  if (busy.value) return
  error.value = ''
  message.value = ''
  if (!file.value || !company.value) {
    error.value = '请选择文件和公司。'
    return
  }
  if (file.value.size === 0 || file.value.size > 10 * 1024 * 1024) {
    error.value = '文件须为1字节至10MiB。'
    return
  }
  busy.value = true
  clearResults()
  const data = new FormData()
  data.append('file', file.value)
  try {
    const params = new URLSearchParams({ visibility: visibility.value })
    if (replacement.value) params.set('expectedVersion', replacement.value.version)
    else params.set('companyId', company.value)
    const result = await write(
      replacement.value ? 'put' : 'post',
      '/knowledge/documents' + (replacement.value ? '/' + replacement.value.id : '') + '?' + params,
      data,
    )
    if (!alive) return
    message.value = `已保存第 ${result.version} 版，等待后台处理。`
    cancelReplace()
    await load(1)
  } catch (e) {
    if (alive) error.value = e.message
  } finally {
    if (alive) busy.value = false
  }
}
async function remove(doc) {
  if (!window.confirm(`确认删除“${doc.name}”？原文件、切片和索引会失效。`)) return
  busy.value = true
  clearResults()
  error.value = ''
  try {
    await write('delete', `/knowledge/documents/${doc.id}?expectedVersion=${doc.version}`)
    if (!alive) return
    message.value = '文档已删除，旧来源已失效。'
    await load(1)
  } catch (e) {
    if (alive) error.value = e.message
  } finally {
    if (alive) busy.value = false
  }
}
async function retry(doc) {
  busy.value = true
  clearResults()
  error.value = ''
  try {
    await write('post', `/knowledge/documents/${doc.id}/retry?expectedVersion=${doc.version}`)
    if (alive) {
      message.value = '已创建新版本，等待重新解析及索引。'
      await load()
    }
  } catch (e) {
    if (alive) error.value = e.message
  } finally {
    if (alive) busy.value = false
  }
}
async function search() {
  if (searchBusy.value) return
  const sequence = ++searchSequence
  searchBusy.value = true
  hits.value = null
  source.value = null
  error.value = ''
  try {
    const result = await http.get('/knowledge/search', {
      params: { query: question.value, mode: mode.value, limit: 10 },
    })
    if (alive && sequence === searchSequence) hits.value = result
  } catch (e) {
    if (alive && sequence === searchSequence) error.value = e.message
  } finally {
    if (alive) searchBusy.value = false
  }
}
async function showSource(hit) {
  const sequence = ++sourceSequence
  source.value = null
  try {
    const c = hit.citation
    const result = await http.get(
      `/knowledge/documents/${c.documentId}/sources/${c.chunk.sequence}`,
      { params: { version: c.version } },
    )
    if (alive && sequence === sourceSequence) source.value = result
  } catch (e) {
    if (alive && sequence === sourceSequence) error.value = e.message
  }
}
async function showHistory(doc) {
  const sequence = ++sourceSequence
  history.value = null
  try {
    const items = await http.get(`/knowledge/documents/${doc.id}/revisions`)
    if (alive && sequence === sourceSequence) history.value = { name: doc.name, items }
  } catch (e) {
    if (alive && sequence === sourceSequence) error.value = e.message
  }
}
onMounted(async () => {
  try {
    const profile = await http.get('/employees/me')
    if (alive) company.value = profile.companyId || ''
  } catch (e) {
    if (alive) error.value = e.message
  }
  if (!alive) return
  load(1)
  timer = window.setInterval(() => {
    if (
      !document.hidden &&
      documents.value?.items.some((d) => ['QUEUED', 'PROCESSING'].includes(d.processing))
    )
      load()
  }, 3000)
})
onBeforeUnmount(() => {
  alive = false
  listSequence++
  searchSequence++
  sourceSequence++
  window.clearInterval(timer)
})
</script>

<template>
  <div class="knowledge-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">制度与资料</p>
        <h1>企业知识库</h1>
        <p>上传资料、跟踪解析、检索正文并回查版本和来源。</p>
      </div>
      <button class="button button-secondary" :disabled="busy" @click="load()">刷新状态</button>
    </header>
    <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <section v-if="status" class="panel">
      <h2>{{ status.available ? '知识库已接入' : '知识库尚未启用' }}</h2>
      <ul v-if="status.blockers?.length">
        <li v-for="item in status.blockers" :key="item">{{ item }}</li>
      </ul>
      <p>
        文字检索按关键词匹配，向量检索需要已配置且可用的服务。公司资料与私人资料分别校验访问范围。
      </p>
    </section>
    <template v-if="status?.available">
      <section class="panel" aria-label="文档上传与更新">
        <h2>
          {{
            replacement ? `更新“${replacement.name}” · 当前第${replacement.version}版` : '上传文档'
          }}
        </h2>
        <form class="knowledge-controls" @submit.prevent="upload">
          <label
            >公司 ID<input
              v-model="company"
              type="number"
              min="1"
              required
              :disabled="busy || !!replacement || auth.user?.role !== 'ADMIN'"
          /></label>
          <label
            >阅读范围<select v-model="visibility" :disabled="busy">
              <option value="PRIVATE">私人资料（本人及公司管理人员）</option>
              <option v-if="manager" value="COMPANY">公司资料（本公司成员）</option>
            </select></label
          >
          <label class="file-label"
            >选择文档<input
              ref="fileInput"
              type="file"
              accept=".pdf,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg,.wav,.mp3,.mp4,.webm,.m4a"
              :disabled="busy"
              @change="selectFile"
          /></label>
          <button class="button button-primary" :disabled="busy || !file" type="submit">
            {{ busy ? '正在保存…' : replacement ? '保存新版本' : '上传文档' }}</button
          ><button
            v-if="replacement"
            type="button"
            class="button button-secondary"
            :disabled="busy"
            @click="cancelReplace"
          >
            取消更新
          </button>
        </form>
        <p>
          单文件最多10MiB；支持文本型PDF、DOCX、XLS/XLSX、PPT/PPTX。图片、扫描PDF及音视频依赖对应OCR或转写服务；旧版DOC、宏文件不接收。每文档最多200片，超限会明确失败。
        </p>
      </section>
      <section class="panel" aria-label="文档检索">
        <h2>文档检索</h2>
        <form class="knowledge-controls" @submit.prevent="search">
          <label class="query-label"
            >想查找的制度或问题<input
              v-model="question"
              maxlength="500"
              required
              placeholder="例如：年假规定" /></label
          ><label
            >检索方式<select v-model="mode">
              <option value="TEXT">文字关键词</option>
              <option value="VECTOR" :disabled="!status.vectorSearchAvailable">向量相似度</option>
            </select></label
          ><button class="button button-primary" :disabled="searchBusy || !question.trim()">
            {{ searchBusy ? '检索中…' : '检索' }}
          </button>
        </form>
        <p v-if="hits && !hits.length">没有匹配的可访问来源。</p>
        <article
          v-for="hit in hits || []"
          :key="`${hit.citation.documentId}:${hit.citation.version}:${hit.citation.chunk.sequence}`"
          class="knowledge-hit"
        >
          <h3>{{ hit.documentName }} · 第{{ hit.citation.version }}版</h3>
          <p>
            {{ where(hit.citation.chunk.source) }} ·
            {{ hit.mode === 'TEXT' ? '关键词匹配' : '向量相似度' }} {{ hit.score.toFixed(3) }}
          </p>
          <blockquote>{{ hit.citation.chunk.content }}</blockquote>
          <button class="button button-secondary" @click="showSource(hit)">核对当前来源</button>
        </article>
        <section v-if="source" class="knowledge-source" aria-label="来源详情">
          <h3>来源详情 · 第{{ source.version }}版</h3>
          <p>
            {{ where(source.chunk.source) }} · 正文码点偏移 {{ source.chunk.startOffset }}–{{
              source.chunk.endOffset
            }}
          </p>
          <pre>{{ source.chunk.content }}</pre>
          <button class="button button-secondary" @click="source = null">关闭来源</button>
        </section>
      </section>
      <section class="panel">
        <h2>有权访问的文档</h2>
        <p v-if="documents && !documents.total">还没有可访问的文档，可以先上传一份合成资料验证。</p>
        <article v-for="doc in documents?.items || []" :key="doc.id" class="knowledge-document">
          <div>
            <h3>{{ doc.name }} · 第{{ doc.version }}版</h3>
            <p>
              {{ states[doc.processing] || doc.processing }} · {{ doc.chunkCount }}片 ·
              {{ doc.access.visibility === 'COMPANY' ? '公司资料' : '私人资料' }} · 公司#{{
                doc.access.companyId
              }}
            </p>
            <p>{{ doc.message }}</p>
            <small>{{
              new Date(doc.updatedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
            }}</small>
          </div>
          <div class="knowledge-controls">
            <a class="button button-secondary" :href="`/api/knowledge/documents/${doc.id}/file`"
              >下载原文件</a
            ><button class="button button-secondary" :disabled="busy" @click="showHistory(doc)">
              版本记录</button
            ><template v-if="canManage(doc)"
              ><button
                class="button button-secondary"
                :disabled="busy"
                @click="prepareReplace(doc)"
              >
                准备更新</button
              ><button
                class="button button-secondary"
                :disabled="busy || ['QUEUED', 'PROCESSING'].includes(doc.processing)"
                @click="retry(doc)"
              >
                重新解析与索引</button
              ><button class="button button-secondary" :disabled="busy" @click="remove(doc)">
                删除文档
              </button></template
            >
          </div>
        </article>
        <div v-if="documents" class="knowledge-controls">
          <button
            class="button button-secondary"
            :disabled="page === 1 || busy"
            @click="load(page - 1)"
          >
            上一页</button
          ><span>第{{ page }}页 · 共{{ documents.total }}份</span
          ><button
            class="button button-secondary"
            :disabled="page * 10 >= documents.total || busy"
            @click="load(page + 1)"
          >
            下一页
          </button>
        </div>
        <section v-if="history" aria-label="版本记录">
          <h3>{{ history.name }} · 版本记录</h3>
          <ul>
            <li v-for="entry in history.items" :key="entry.version">
              第{{ entry.version }}版 · {{ entry.action }} · 操作人#{{ entry.actorId }} ·
              {{ entry.name }}
            </li>
          </ul>
          <p>记录保留版本、操作人及摘要；旧版正文和索引已失效，不作为当前回答来源。</p>
          <button class="button button-secondary" @click="history = null">关闭记录</button>
        </section>
      </section>
    </template>
  </div>
</template>

<style scoped>
.knowledge-page {
  display: grid;
  gap: 20px;
  min-width: 0;
}
.knowledge-page .panel {
  padding: 24px;
  min-width: 0;
}
.knowledge-page h2 {
  margin: 0 0 18px;
  font-size: 18px;
}
.knowledge-page h3 {
  margin: 12px 0;
  font-size: 16px;
  overflow-wrap: anywhere;
}
.knowledge-page p,
.knowledge-page li {
  color: #64748b;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.knowledge-controls {
  display: flex;
  gap: 12px;
  align-items: end;
  flex-wrap: wrap;
}
.knowledge-controls label {
  display: grid;
  gap: 8px;
  font-size: 13px;
  max-width: 100%;
}
.knowledge-controls input,
.knowledge-controls select {
  box-sizing: border-box;
  max-width: 100%;
  min-width: 0;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 10px;
  background: white;
}
.query-label {
  flex: 1 1 250px;
}
.file-label {
  flex: 1 1 240px;
}
.knowledge-document,
.knowledge-hit {
  padding: 18px 0;
  border-bottom: 1px solid #e2e8f0;
  margin-bottom: 18px;
}
.knowledge-hit blockquote,
.knowledge-source pre {
  margin: 14px 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f8fafc;
  padding: 16px;
  border-radius: 8px;
  line-height: 1.7;
}
.knowledge-source {
  border: 1px solid #cbd5e1;
  padding: 18px;
  border-radius: 12px;
  margin-top: 20px;
}
.knowledge-document .knowledge-controls {
  margin-top: 15px;
}
.knowledge-page small {
  color: #64748b;
}
@media (max-width: 600px) {
  .knowledge-page .panel {
    padding: 18px;
  }
  .knowledge-controls label,
  .knowledge-controls input,
  .knowledge-controls select {
    width: 100%;
  }
  .knowledge-controls {
    align-items: stretch;
  }
  .knowledge-controls .button {
    white-space: normal;
  }
  .page-heading {
    gap: 16px;
    flex-wrap: wrap;
  }
}
</style>
