<script setup>
import { computed, onMounted, ref } from 'vue'
import { Search, Refresh, Lock } from '@element-plus/icons-vue'
import http from '../api'

const accounts = ref([])
const keyword = ref('')
const role = ref('')
const loading = ref(true)
const error = ref('')
const filtered = computed(() =>
  accounts.value.filter(
    (u) =>
      (!role.value || u.role === role.value) &&
      `${u.username} ${u.displayName} ${u.employeeNo}`
        .toLowerCase()
        .includes(keyword.value.trim().toLowerCase()),
  ),
)
async function load() {
  loading.value = true
  error.value = ''
  try {
    accounts.value = await http.get('/admin/accounts')
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
      <div class="eyebrow">系统管理</div>
      <h1>系统账号</h1>
      <p>查看当前账号、角色与所属部门，掌握访问权限分配。</p>
    </div>
    <span class="tag tag-blue">管理员专属</span>
  </div>
  <div class="notice">
    <Lock /><span>当前提供账号查询。新增员工、修改资料和停用账号将在员工管理模块开放。</span>
  </div>
  <section class="panel">
    <div class="table-toolbar">
      <div class="filters">
        <div class="input-with-icon search-input">
          <Search /><input
            v-model="keyword"
            aria-label="搜索账号"
            placeholder="搜索姓名、账号或工号"
          />
        </div>
        <select v-model="role" aria-label="筛选角色">
          <option value="">全部角色</option>
          <option value="ADMIN">系统管理员</option>
          <option value="HR">人事专员</option>
          <option value="EMPLOYEE">员工</option>
        </select>
      </div>
      <button class="button button-secondary" :disabled="loading" @click="load">
        <Refresh />刷新
      </button>
    </div>
    <div v-if="error" class="notice notice-error" role="alert">{{ error }}</div>
    <div v-else class="table-scroll" :aria-busy="loading">
      <table>
        <thead>
          <tr>
            <th>姓名 / 账号</th>
            <th>工号</th>
            <th>角色</th>
            <th>所属部门</th>
            <th>状态</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td colspan="6" class="table-empty">正在加载账号…</td>
          </tr>
          <template v-else
            ><tr v-for="u in filtered" :key="u.id">
              <td>
                <div class="table-user">
                  <span class="avatar avatar-small">{{ u.displayName.slice(-2) }}</span>
                  <div>
                    <b>{{ u.displayName }}</b
                    ><small>{{ u.username }}</small>
                  </div>
                </div>
              </td>
              <td class="mono">{{ u.employeeNo }}</td>
              <td>
                <span
                  class="tag"
                  :class="
                    u.role === 'ADMIN' ? 'tag-purple' : u.role === 'HR' ? 'tag-blue' : 'tag-neutral'
                  "
                  >{{ u.roleName }}</span
                >
              </td>
              <td>{{ u.departmentName || '暂未分配' }}</td>
              <td>
                <span class="status-dot" :class="{ 'status-disabled': !u.enabled }"></span
                >{{ u.enabled ? '正常启用' : '已停用' }}
              </td>
              <td class="muted small-text">{{ u.createdAt?.replace('T', ' ').slice(0, 16) }}</td>
            </tr>
            <tr v-if="!filtered.length">
              <td colspan="6" class="table-empty">没有找到符合条件的账号</td>
            </tr></template
          >
        </tbody>
      </table>
    </div>
    <div class="table-footer">共 {{ filtered.length }} 个账号<span>数据来自当前数据库</span></div>
  </section>
</template>
