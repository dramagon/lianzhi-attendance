<script setup>
import { onMounted, ref } from 'vue'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import http from '../api'
const auth = useAuthStore()
const user = ref(null)
const error = ref('')
async function load() {
  error.value = ''
  try {
    user.value = await http.get('/profile')
    auth.user = user.value
  } catch (e) {
    error.value = e.message
  }
}
onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">账号与身份</div>
      <h1>个人信息</h1>
      <p>查看你的账号资料与当前可访问的功能。</p>
    </div>
  </div>
  <div v-if="error" role="alert" class="notice notice-error">
    {{ error }}<button class="text-button" @click="load">重新加载</button>
  </div>
  <div v-else-if="user" class="profile-grid">
    <section class="panel profile-card">
      <span class="avatar avatar-profile">{{ user.displayName.slice(-2) }}</span>
      <h2>{{ user.displayName }}</h2>
      <span class="tag tag-blue">{{ user.roleName }}</span>
      <p class="profile-status"><CircleCheckFilled />账号正常启用</p>
      <p class="muted small-text">个人资料由管理员统一维护。</p>
    </section>
    <section class="panel">
      <div class="panel-heading"><h2>基础资料</h2></div>
      <dl class="profile-details">
        <div>
          <dt>登录账号</dt>
          <dd>{{ user.username }}</dd>
        </div>
        <div>
          <dt>员工工号</dt>
          <dd>{{ user.employeeNo }}</dd>
        </div>
        <div>
          <dt>所属部门</dt>
          <dd>{{ user.departmentName || '暂未分配' }}</dd>
        </div>
        <div>
          <dt>账号角色</dt>
          <dd>{{ user.roleName }}</dd>
        </div>
        <div>
          <dt>创建时间</dt>
          <dd>{{ user.createdAt?.replace('T', ' ').slice(0, 19) }}</dd>
        </div>
      </dl>
      <div class="profile-permissions">
        <h3>可访问功能</h3>
        <div class="permission-tags">
          <span class="tag tag-neutral">工作台</span
          ><span v-if="auth.can('accounts:read')" class="tag tag-neutral">系统账号查询</span
          ><span class="tag tag-neutral">个人信息</span
          ><span class="tag tag-neutral">建设进度</span>
        </div>
      </div>
    </section>
  </div>
  <p v-else class="muted">正在加载个人信息…</p>
</template>
