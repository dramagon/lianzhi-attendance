<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  House,
  User,
  UserFilled,
  List,
  OfficeBuilding,
  Calendar,
  ChatDotRound,
  SwitchButton,
  Menu,
  Close,
  ArrowRight,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import BrandMark from '../components/BrandMark.vue'
import UserAvatar from '../components/UserAvatar.vue'
import { useAuthStore } from '../stores/auth'
import http from '../api'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const open = ref(false)
const signingOut = ref(false)
const info = ref(null)
const navigation = [
  { path: '/workbench', name: '工作台', icon: House },
  { path: '/accounts', name: '系统账号', icon: UserFilled, permission: 'accounts:read' },
  { path: '/attendance', name: '考勤工作台', icon: Calendar },
  { path: '/knowledge', name: '企业知识库', icon: List },
  { path: '/assistant', name: '业务助手', icon: ChatDotRound },
  { path: '/reports', name: '统计报表', icon: List, permission: 'organization:manage' },
  {
    path: '/organization',
    name: '组织与场所',
    icon: OfficeBuilding,
    permission: 'organization:manage',
  },
  { path: '/profile', name: '个人信息', icon: User },
  { path: '/approvals', name: '我的申请', icon: Calendar, permission: 'approvals:read' },
  { path: '/approvals/review', name: '审批工作台', icon: List, permission: 'approvals:manage' },
  { path: '/roadmap', name: '建设进度', icon: List },
]
watch(
  () => route.path,
  () => {
    open.value = false
  },
)
onMounted(async () => {
  try {
    info.value = await http.get('/system/info')
  } catch (e) {
    ElMessage.error(e.message)
  }
})
async function signOut() {
  if (signingOut.value) return
  signingOut.value = true
  try {
    await auth.signOut()
    await router.replace('/login')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    signingOut.value = false
  }
}
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content">跳至主要内容</a>
    <button v-if="open" class="sidebar-scrim" aria-label="关闭菜单" @click="open = false"></button>
    <aside class="sidebar" :class="{ 'is-open': open }">
      <RouterLink class="brand sidebar-brand" to="/workbench"
        ><BrandMark />
        <div><strong>联智</strong><small>企业工作空间</small></div></RouterLink
      >
      <button class="mobile-close icon-button" aria-label="关闭菜单" @click="open = false">
        <Close />
      </button>
      <div class="workspace-pill">
        <span class="workspace-avatar">联</span>
        <div>
          <b>联智考勤</b><small>{{ info?.demo ? '本地演示工作空间' : '企业工作空间' }}</small>
        </div>
      </div>
      <nav aria-label="主导航">
        <p class="nav-label">工作空间</p>
        <template v-for="item in navigation" :key="item.path"
          ><RouterLink
            v-if="!item.permission || auth.can(item.permission)"
            :to="item.path"
            class="nav-item"
            active-class="is-active"
            ><component :is="item.icon" /><span>{{ item.name }}</span
            ><ArrowRight class="nav-arrow" /></RouterLink
        ></template>
      </nav>
      <div class="sidebar-bottom">
        <span class="status-dot"></span>第 1 阶段 · 系统基础<span>v0.1</span>
      </div>
    </aside>
    <div class="app-main">
      <header class="topbar">
        <div class="topbar-left">
          <button
            class="icon-button mobile-menu"
            aria-label="打开菜单"
            :aria-expanded="open"
            @click="open = true"
          >
            <Menu /></button
          ><span class="breadcrumb-root">工作空间</span><span class="breadcrumb-separator">/</span
          ><b>{{ route.meta.title }}</b>
        </div>
        <div class="topbar-right">
          <span v-if="info?.demo" class="tag tag-outline demo-badge">演示环境</span
          ><RouterLink to="/profile" class="topbar-user"
            ><UserAvatar :user="auth.user" /> <span>{{ auth.user?.displayName }}</span></RouterLink
          ><button
            class="icon-button logout-button"
            aria-label="退出登录"
            title="退出登录"
            :disabled="signingOut"
            @click="signOut"
          >
            <SwitchButton />
          </button>
        </div>
      </header>
      <main id="main-content" class="page-content" tabindex="-1"><RouterView /></main>
      <footer class="app-footer">
        联智 · 企业智能考勤与业务助手<span>按角色协作，让管理更有序</span>
      </footer>
    </div>
  </div>
</template>
