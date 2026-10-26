<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  UserFilled,
  CircleCheck,
  Collection,
  OfficeBuilding,
  ArrowRight,
  Calendar,
  Clock,
  Location,
  Document,
  ChatDotRound,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import http from '../api'
import UserAvatar from '../components/UserAvatar.vue'

const auth = useAuthStore()
const overview = ref(null)
const loading = ref(true)
const error = ref('')
const currentDate = computed(() =>
  new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(new Date()),
)
const stats = computed(() => [
  {
    label: '系统账号',
    value: overview.value?.totalAccounts,
    suffix: '个',
    icon: UserFilled,
    color: 'blue',
    note: '当前已建立的账号',
  },
  {
    label: '启用账号',
    value: overview.value?.enabledAccounts,
    suffix: '个',
    icon: CircleCheck,
    color: 'green',
    note: '具备登录资格的账号',
  },
  {
    label: '已配置角色',
    value: overview.value?.roleCount,
    suffix: '类',
    icon: Collection,
    color: 'purple',
    note: '按职责分配访问权限',
  },
  {
    label: '已建部门',
    value: overview.value?.departmentCount,
    suffix: '个',
    icon: OfficeBuilding,
    color: 'orange',
    note: '已录入的部门数量',
  },
])
async function load() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await http.get(auth.isEmployee ? '/employee/overview' : '/management/overview')
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
      <div class="eyebrow">{{ currentDate }}</div>
      <h1>{{ auth.user.displayName }}，欢迎回来</h1>
      <p>这里是你的{{ auth.isEmployee ? '员工' : '管理' }}工作台，常用信息与工作入口都在这里。</p>
    </div>
    <span class="tag tag-green">账号已验证</span>
  </div>
  <div v-if="error" role="alert" class="notice notice-error">
    {{ error }}<button class="text-button" @click="load">重新加载</button>
  </div>
  <template v-else>
    <div v-if="!auth.isEmployee" class="stats-grid" :aria-busy="loading">
      <article v-for="stat in stats" :key="stat.label" class="stat-card">
        <div class="stat-top">
          <span>{{ stat.label }}</span
          ><span class="stat-icon" :class="stat.color"><component :is="stat.icon" /></span>
        </div>
        <div class="stat-number">
          {{ loading ? '—' : stat.value }}<small>{{ stat.suffix }}</small>
        </div>
        <p>{{ stat.note }}</p>
      </article>
    </div>
    <div class="workbench-grid">
      <section class="panel attendance-panel">
        <div class="panel-heading">
          <h2>{{ auth.isEmployee ? '今日考勤' : '考勤概览' }}</h2>
          <span class="tag tag-neutral">待接入</span>
        </div>
        <div class="empty-attendance">
          <div class="empty-icon"><Calendar /></div>
          <h3>{{ auth.isEmployee ? '打卡功能即将接入' : '考勤数据将在打卡功能上线后展示' }}</h3>
          <p>当前已完成登录与权限基础。二维码打卡、人脸验证和考勤统计将按阶段接入。</p>
          <RouterLink to="/roadmap" class="text-link">查看建设进度<ArrowRight /></RouterLink>
        </div>
      </section>
      <section class="panel identity-panel">
        <div class="panel-heading">
          <h2>我的身份</h2>
          <RouterLink to="/profile" class="text-link">查看详情<ArrowRight /></RouterLink>
        </div>
        <div class="identity-summary">
          <UserAvatar :user="auth.user" size="large" />
          <div>
            <h3>{{ auth.user.displayName }}</h3>
            <span class="tag tag-blue">{{ auth.user.roleName }}</span>
          </div>
        </div>
        <dl class="compact-details">
          <div>
            <dt>工号</dt>
            <dd>{{ auth.user.employeeNo }}</dd>
          </div>
          <div>
            <dt>所属部门</dt>
            <dd>{{ auth.user.departmentName || '暂未分配' }}</dd>
          </div>
          <div>
            <dt>账号状态</dt>
            <dd><span class="status-dot"></span>正常启用</dd>
          </div>
        </dl>
      </section>
    </div>
    <section class="panel quick-panel">
      <div class="panel-heading">
        <h2>常用入口</h2>
        <span class="muted small-text">依据当前角色开放</span>
      </div>
      <div class="quick-links">
        <RouterLink v-if="auth.can('accounts:read')" to="/accounts"
          ><span class="quick-icon blue"><UserFilled /></span>
          <div>
            <b>系统账号</b>
            <p>查看账号与角色分配</p>
          </div>
          <ArrowRight /></RouterLink
        ><RouterLink v-if="auth.can('organization:manage')" to="/organization"
          ><span class="quick-icon blue"><Location /></span>
          <div>
            <b>组织与场所</b>
            <p>维护员工与办公地点</p>
          </div>
          <ArrowRight /></RouterLink
        ><RouterLink to="/profile"
          ><span class="quick-icon purple"><Document /></span>
          <div>
            <b>个人信息</b>
            <p>查看自己的基础资料</p>
          </div>
          <ArrowRight /></RouterLink
        ><RouterLink to="/roadmap"
          ><span class="quick-icon green"><Clock /></span>
          <div>
            <b>建设进度</b>
            <p>了解已完成与后续模块</p>
          </div>
          <ArrowRight
        /></RouterLink>
      </div>
    </section>
    <div class="section-caption">
      <h2>逐步连接每个工作环节</h2>
      <span>以下能力正在按计划建设</span>
    </div>
    <div class="upcoming-grid">
      <article
        v-for="item in [
          { name: '组织与场所', description: '连接部门、员工与办公地点', icon: Location },
          { name: '考勤与审批', description: '打卡、请假、补卡和加班', icon: Calendar },
          { name: 'AI 业务助手', description: '企业制度问答与考勤查询', icon: ChatDotRound },
        ]"
        :key="item.name"
      >
        <component :is="item.icon" />
        <div>
          <h3>{{ item.name }}</h3>
          <p>{{ item.description }}</p>
        </div>
        <span>{{ item.name === '组织与场所' ? '基础已接通' : '待开发' }}</span>
      </article>
    </div>
  </template>
</template>
