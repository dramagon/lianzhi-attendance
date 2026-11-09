<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import http, { write } from '../api'
import UserAvatar from '../components/UserAvatar.vue'
import { useRouter } from 'vue-router'
const router = useRouter()
const auth = useAuthStore()
const user = ref(null)
const employee = ref(null)
const workplaces = ref([])
const error = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordBusy = ref(false)
const passwordError = ref('')
async function changePassword() {
  if (passwordBusy.value || avatarBusy.value) return
  passwordError.value = ''
  if (newPassword.value !== confirmPassword.value) {
    passwordError.value = '两次新密码输入不一致'
    return
  }
  passwordBusy.value = true
  try {
    await write('post', '/profile/password', {
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
      confirmPassword: confirmPassword.value,
    })
    currentPassword.value = newPassword.value = confirmPassword.value = ''
    auth.discard()
    await router.replace('/login?passwordChanged=1')
  } catch (e) {
    passwordError.value = e.message
  } finally {
    passwordBusy.value = false
  }
}
const avatarInput = ref(null)
const avatarFile = ref(null)
const avatarPreview = ref('')
const avatarError = ref('')
const avatarSuccess = ref('')
const avatarBusy = ref(false)
const previewUser = computed(() =>
  avatarPreview.value ? { ...user.value, avatarUrl: avatarPreview.value } : user.value,
)
function clearPreview() {
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
  avatarPreview.value = ''
  avatarFile.value = null
  if (avatarInput.value) avatarInput.value.value = ''
}
function chooseAvatar(event) {
  avatarError.value = ''
  avatarSuccess.value = ''
  const file = event.target.files?.[0]
  if (!file) return
  clearPreview()
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 2 * 1024 * 1024) {
    avatarError.value = '请选择不超过2MB的JPG或PNG图片'
    return
  }
  avatarFile.value = file
  avatarPreview.value = URL.createObjectURL(file)
}
async function saveAvatar(remove = false) {
  if (passwordBusy.value || avatarBusy.value || (!remove && !avatarFile.value)) return
  avatarBusy.value = true
  avatarError.value = ''
  avatarSuccess.value = ''
  try {
    const form = new FormData()
    if (!remove) form.append('file', avatarFile.value)
    user.value = await write(
      remove ? 'delete' : 'post',
      '/profile/avatar',
      remove ? undefined : form,
    )
    auth.user = user.value
    clearPreview()
    avatarSuccess.value = remove ? '已恢复默认名称头像' : '头像已更新'
  } catch (e) {
    avatarError.value = e.message
  } finally {
    avatarBusy.value = false
  }
}
async function load() {
  error.value = ''
  try {
    user.value = await http.get('/profile')
    auth.user = user.value
    employee.value = await http.get('/employees/me')
    workplaces.value = await http.get(`/employees/${employee.value.userId}/workplaces`)
  } catch (e) {
    error.value = e.message
  }
}
onMounted(load)
onUnmounted(clearPreview)
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
      <UserAvatar :user="previewUser" size="profile" />
      <h2>{{ user.displayName }}</h2>
      <span class="tag tag-blue">{{ user.roleName }}</span>
      <p class="profile-status"><CircleCheckFilled />账号正常启用</p>
      <p class="muted small-text">姓名与组织归属由管理员维护，头像可自行设置。</p>
      <div class="profile-avatar-actions">
        <input
          ref="avatarInput"
          class="avatar-file-input"
          type="file"
          accept="image/jpeg,image/png"
          aria-label="选择头像文件"
          :disabled="avatarBusy || passwordBusy"
          @change="chooseAvatar"
        />
        <button
          class="button button-secondary"
          :disabled="avatarBusy || passwordBusy"
          @click="avatarInput.click()"
        >
          选择头像
        </button>
        <template v-if="avatarFile"
          ><button
            class="button button-primary"
            :disabled="avatarBusy || passwordBusy"
            @click="saveAvatar(false)"
          >
            {{ avatarBusy ? '正在上传…' : '保存头像' }}</button
          ><button class="text-button" :disabled="avatarBusy || passwordBusy" @click="clearPreview">
            取消
          </button></template
        >
        <button
          v-else-if="user.avatarUrl"
          class="text-button"
          :disabled="avatarBusy || passwordBusy"
          @click="saveAvatar(true)"
        >
          恢复默认头像
        </button>
      </div>
      <p class="muted small-text">JPG / PNG，最大2MB，保存时自动居中裁剪。</p>
      <p v-if="avatarError" class="notice notice-error" role="alert">{{ avatarError }}</p>
      <p v-if="avatarSuccess" class="muted small-text" role="status">{{ avatarSuccess }}</p>
    </section>
    <section class="panel">
      <div class="panel-heading"><h2>基础资料</h2></div>
      <dl class="profile-details">
        <div>
          <dt>所属公司</dt>
          <dd>{{ employee?.companyName || '暂未分配' }}</dd>
        </div>
        <div>
          <dt>办公场所</dt>
          <dd>
            {{
              workplaces
                .filter((p) => p.enabled)
                .map((p) => p.name)
                .join('、') || '暂未绑定'
            }}
          </dd>
        </div>
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
          ><span class="tag tag-neutral">个人信息</span>
        </div>
      </div>
    </section>
  </div>
  <p v-else class="muted">正在加载个人信息…</p>
  <section v-if="user" class="panel password-panel" aria-label="修改密码">
    <h2>修改密码</h2>
    <p class="muted">修改后需要重新登录，其他设备的登录也会失效。</p>
    <p v-if="passwordError" class="notice notice-error" role="alert">{{ passwordError }}</p>
    <form class="password-form" @submit.prevent="changePassword">
      <label
        >当前密码<input
          v-model="currentPassword"
          type="password"
          autocomplete="current-password"
          required
          maxlength="72"
          :disabled="passwordBusy"
      /></label>
      <label
        >新密码<input
          v-model="newPassword"
          type="password"
          autocomplete="new-password"
          required
          minlength="4"
          maxlength="72"
          :disabled="passwordBusy"
      /></label>
      <label
        >确认新密码<input
          v-model="confirmPassword"
          type="password"
          autocomplete="new-password"
          required
          minlength="4"
          maxlength="72"
          :disabled="passwordBusy"
      /></label>
      <button class="button button-primary" :disabled="passwordBusy || avatarBusy">
        {{ passwordBusy ? '正在修改…' : '修改密码并重新登录' }}
      </button>
    </form>
  </section>
</template>
<style scoped>
.password-panel {
  padding: 24px;
  margin-top: 24px;
}
.password-form {
  display: grid;
  gap: 16px;
  max-width: 420px;
  margin-top: 16px;
}
.password-form label {
  display: grid;
  gap: 8px;
}
.password-form input {
  width: 100%;
  min-width: 0;
  padding: 10px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
}
.profile-avatar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: center;
  margin: 18px 0 10px;
}
.avatar-file-input {
  display: none;
}
</style>
