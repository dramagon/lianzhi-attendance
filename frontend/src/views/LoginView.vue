<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Lock, User, Right, CircleCheckFilled, View, Hide } from '@element-plus/icons-vue'
import BrandMark from '../components/BrandMark.vue'
import { useAuthStore } from '../stores/auth'
import http from '../api'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const form = reactive({ username: '', password: '' })
const info = ref(null)
const loading = ref(false)
const error = ref('')
const showPassword = ref(false)
onMounted(async () => {
  try {
    info.value = await http.get('/system/info')
  } catch (e) {
    error.value = e.message
  }
})
function chooseAccount(username) {
  form.username = username
  form.password = 'Lianzhi@2026'
  error.value = ''
}
async function submit() {
  if (loading.value) return
  error.value = ''
  if (!form.username.trim() || !form.password) {
    error.value = '请输入账号和密码'
    return
  }
  loading.value = true
  try {
    await auth.signIn(form.username.trim(), form.password)
    form.password = ''
    const next = route.query.redirect
    await router.replace(
      typeof next === 'string' &&
        next.startsWith('/') &&
        !next.startsWith('//') &&
        !next.startsWith('/login')
        ? next
        : '/workbench',
    )
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <aside class="login-story">
      <div class="brand">
        <BrandMark />
        <div><strong>联智</strong><small>企业智能考勤与业务助手</small></div>
      </div>
      <div class="story-content">
        <span class="eyebrow">让日常管理，井然有序</span>
        <h1>连接每一位员工，<br />照顾每一个工作日。</h1>
        <p>从员工与场所管理开始，逐步连接考勤、审批与企业知识，构建清晰可靠的工作空间。</p>
        <div class="story-diagram" aria-hidden="true">
          <div class="diagram-main">
            <BrandMark />
            <div><b>联智工作空间</b><span>统一身份 · 按角色协作</span></div>
            <CircleCheckFilled />
          </div>
          <div class="diagram-line"></div>
          <div class="diagram-roles"><span>管理员</span><span>人事专员</span><span>员工</span></div>
        </div>
        <div class="story-notes">
          <span><CircleCheckFilled />身份验证</span><span><CircleCheckFilled />角色权限</span
          ><span><CircleCheckFilled />数据留存</span>
        </div>
      </div>
      <p class="story-footer">联智 Lianzhi · 工程实践项目</p>
    </aside>
    <main class="login-main">
      <div class="login-mobile-brand"><BrandMark /><b>联智</b></div>
      <div class="login-form-wrap">
        <span class="tag tag-blue">企业工作空间</span>
        <h2>欢迎回来</h2>
        <p class="muted login-subtitle">登录账号，进入你的工作台</p>
        <p v-if="route.query.expired" class="notice notice-warning">登录已过期，请重新登录。</p>
        <form @submit.prevent="submit">
          <label for="username">账号</label>
          <div class="input-with-icon">
            <User /><input
              id="username"
              v-model="form.username"
              name="username"
              autocomplete="username"
              placeholder="请输入账号"
              maxlength="50"
              :disabled="loading"
              required
            />
          </div>
          <label for="password">密码</label>
          <div class="input-with-icon">
            <Lock /><input
              id="password"
              v-model="form.password"
              name="password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="请输入密码"
              maxlength="100"
              :disabled="loading"
              required
            /><button
              class="password-toggle"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <component :is="showPassword ? Hide : View" />
            </button>
          </div>
          <p v-if="error" role="alert" class="form-error">{{ error }}</p>
          <button class="button button-primary login-submit" type="submit" :disabled="loading">
            {{ loading ? '正在登录…' : '登录工作台' }}<Right v-if="!loading" />
          </button>
        </form>
        <div v-if="info?.demo" class="demo-panel">
          <div class="demo-title">选择账号<span>本机快捷登录</span></div>
          <div class="demo-accounts">
            <button type="button" @click="chooseAccount('admin')">管理员</button
            ><button type="button" @click="chooseAccount('hr')">人事专员</button
            ><button type="button" @click="chooseAccount('employee')">员工</button>
          </div>
          <p>初始密码：<code>Lianzhi@2026</code></p>
        </div>
        <p class="login-help">账号由管理员分配。遇到登录问题，请联系系统管理员。</p>
      </div>
      <footer class="login-footer">联智 · v{{ info?.version || '0.1.0' }}</footer>
    </main>
  </div>
</template>
