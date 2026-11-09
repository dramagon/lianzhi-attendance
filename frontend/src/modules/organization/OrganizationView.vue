<script setup>
import { computed, nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http, { write } from '../../api'
import { useAuthStore } from '../../stores/auth'
import MapPicker from './MapPicker.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const isAdmin = computed(() => auth.user?.role === 'ADMIN')
const accountFlow = computed(() => isAdmin.value && route.query.from === 'accounts')
const tabs = { companies: '公司', departments: '部门', employees: '员工', workplaces: '办公场所' }
const paths = {
  companies: '/organizations/companies',
  departments: '/organizations/departments',
  employees: '/employees',
  workplaces: '/workplaces',
}
const tab = ref('employees')
const companyId = ref('')
const query = ref('')
const page = ref(1)
const result = ref({ items: [], total: 0 })
const companies = ref([]),
  departments = ref([]),
  places = ref([])
const busy = ref(false),
  saving = ref(false),
  error = ref(''),
  formError = ref(''),
  success = ref('')
const dialog = ref(null)
const dialogVisible = ref(false)
const editingId = ref(null)
const dialogTitle = computed(() =>
  accountFlow.value && tab.value === 'employees'
    ? editingId.value
      ? '管理账号'
      : '分配账号'
    : `${editingId.value ? '编辑' : '新增'}${tabs[tab.value]}`,
)
const form = reactive({})
const locating = ref(false)
const locationPreview = ref(null)
let locationSequence = 0
let editSequence = 0
let alive = true
function discardLocation() {
  locationSequence++
  locating.value = false
  locationPreview.value = null
}
watch(() => [form.latitude, form.longitude, form.coordinateSystem], discardLocation, {
  flush: 'sync',
})
const formDepartments = computed(() =>
  departments.value.filter((d) => d.companyId === form.companyId && d.enabled),
)
const formPlaces = computed(() =>
  places.value.filter((p) => p.companyId === form.companyId && p.enabled),
)
const roles = { ADMIN: '系统管理员', HR: '人事专员', EMPLOYEE: '员工' }
let loadSequence = 0
async function all(path) {
  const items = []
  for (let p = 1; ; p++) {
    const response = await http.get(path, { params: { page: p, size: 100 } })
    items.push(...response.items)
    if (items.length >= response.total || !response.items.length) return items
  }
}
async function options() {
  const values = await Promise.all([
    all(paths.companies),
    all(paths.departments),
    all(paths.workplaces),
  ])
  ;[companies.value, departments.value, places.value] = values
}
async function load() {
  const sequence = ++loadSequence
  busy.value = true
  error.value = ''
  result.value = { items: [], total: 0 }
  try {
    const data = await http.get(paths[tab.value], {
      params: {
        page: page.value,
        size: 20,
        companyId: companyId.value || undefined,
        query: query.value,
      },
    })
    if (sequence === loadSequence) result.value = data
  } catch (e) {
    if (sequence === loadSequence) {
      error.value = e.message
      result.value = { items: [], total: 0 }
    }
  } finally {
    if (sequence === loadSequence) busy.value = false
  }
}
async function refresh() {
  await load()
  try {
    await options()
  } catch (e) {
    error.value = e.message
  }
}
function search() {
  page.value = 1
  load()
}
function changePage(delta) {
  page.value += delta
  load()
}
watch([tab, companyId], () => {
  editSequence++
  discardLocation()
  page.value = 1
  success.value = ''
  load()
})
function companyName(id) {
  return companies.value.find((c) => c.id === id)?.name || '暂未分配'
}
async function edit(row) {
  const current = ++editSequence
  discardLocation()
  formError.value = ''
  editingId.value = row?.userId || row?.id || null
  for (const key of Object.keys(form)) delete form[key]
  Object.assign(
    form,
    {
      name: '',
      username: '',
      displayName: '',
      employeeNo: '',
      role: 'EMPLOYEE',
      companyId: companyId.value || companies.value.find((c) => c.enabled)?.id || null,
      departmentId: null,
      enabled: true,
      password: '',
      workplaceIds: [],
      latitude: null,
      longitude: null,
      coordinateSystem: 'WGS84',
      radiusMeters: 100,
    },
    row || {},
  )
  if (tab.value === 'employees' && row) {
    try {
      const bindings = await http.get(`/employees/${row.userId}/workplaces`)
      if (!alive || current !== editSequence) return
      form.workplaceIds = bindings.map((p) => p.id)
    } catch (e) {
      if (alive && current === editSequence) error.value = e.message
      return
    }
  }
  await nextTick()
  if (!alive || current !== editSequence) return
  dialog.value.showModal()
  dialogVisible.value = true
}
function changeCompany() {
  form.departmentId = null
  form.workplaceIds = []
}
function dialogClosed() {
  editSequence++
  discardLocation()
  dialogVisible.value = false
  if (accountFlow.value) router.replace('/accounts')
}
async function save() {
  if (saving.value) return
  saving.value = true
  formError.value = ''
  try {
    const url = paths[tab.value] + (editingId.value ? `/${editingId.value}` : '')
    const payload =
      tab.value === 'employees'
        ? {
            username: form.username,
            displayName: form.displayName,
            employeeNo: form.employeeNo,
            role: form.role,
            companyId: form.companyId,
            departmentId: form.departmentId,
            enabled: form.enabled,
            password: form.password,
            workplaceIds: form.workplaceIds,
          }
        : tab.value === 'companies'
          ? { name: form.name, enabled: form.enabled }
          : tab.value === 'departments'
            ? { name: form.name, companyId: form.companyId, enabled: form.enabled }
            : {
                name: form.name,
                companyId: form.companyId,
                enabled: form.enabled,
                latitude: form.latitude,
                longitude: form.longitude,
                coordinateSystem: form.coordinateSystem,
                radiusMeters: form.radiusMeters,
              }
    await write(editingId.value ? 'put' : 'post', url, payload)
    dialog.value.close()
    if (accountFlow.value) return
    success.value = '已保存到数据库。员工资料或权限变更后，原有会话需重新登录。'
    await refresh()
  } catch (e) {
    formError.value = e.message
  } finally {
    saving.value = false
  }
}
function locate() {
  discardLocation()
  formError.value = ''
  if (!navigator.geolocation) {
    formError.value = '当前浏览器不支持定位，请手动录入坐标'
    return
  }
  const current = locationSequence
  locating.value = true
  const isCurrent = () => alive && dialogVisible.value && current === locationSequence
  try {
    navigator.geolocation.getCurrentPosition(
      (position) => {
        if (!isCurrent()) return
        locating.value = false
        const { latitude, longitude, accuracy } = position.coords
        if (
          ![latitude, longitude, accuracy].every(Number.isFinite) ||
          Math.abs(latitude) > 90 ||
          Math.abs(longitude) > 180 ||
          accuracy <= 0
        ) {
          formError.value = '设备返回的位置无效，请重新采集或手动录入'
          return
        }
        locationPreview.value = {
          latitude: Number(latitude.toFixed(7)),
          longitude: Number(longitude.toFixed(7)),
          accuracy,
        }
      },
      () => {
        if (!isCurrent()) return
        locating.value = false
        formError.value = '定位失败，请检查浏览器授权或手动录入坐标'
      },
      { enableHighAccuracy: true, timeout: 15000 },
    )
  } catch {
    if (isCurrent()) {
      locating.value = false
      formError.value = '定位失败，请检查浏览器授权或手动录入坐标'
    }
  }
}
function useLocation() {
  if (!locationPreview.value) return
  const { latitude, longitude } = locationPreview.value
  Object.assign(form, { latitude, longitude, coordinateSystem: 'WGS84' })
  discardLocation()
}
onBeforeUnmount(() => {
  alive = false
  loadSequence++
  editSequence++
  discardLocation()
})
onMounted(async () => {
  await refresh()
  if (!accountFlow.value || error.value) return
  if (route.query.action === 'assign') await edit(null)
  else if (route.query.action === 'edit') {
    const userId = Number(route.query.userId)
    if (!Number.isSafeInteger(userId) || userId < 1) {
      error.value = '账号标识无效'
      return
    }
    try {
      await edit(await http.get(`/employees/${userId}`))
    } catch (e) {
      error.value = e.message
    }
  }
})
</script>

<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">组织档案</div>
      <h1>组织与场所</h1>
      <p>维护公司、员工归属和可用办公场所。数据来自当前数据库。</p>
    </div>
  </div>
  <div class="notice">
    {{ isAdmin ? '管理员可维护全部公司和角色。' : '人事仅可维护所属公司的部门、场所及普通员工。' }}
    删除采用停用方式；有有效引用时须先调整关联。
  </div>
  <section class="panel organization-panel">
    <div class="organization-tabs" aria-label="组织管理分类">
      <button
        v-for="(label, key) in tabs"
        :key="key"
        class="button"
        :class="tab === key ? 'button-primary' : 'button-secondary'"
        :aria-pressed="tab === key"
        @click="tab = key"
      >
        {{ label }}
      </button>
    </div>
    <div class="table-toolbar">
      <div class="filters">
        <select v-model="companyId" aria-label="筛选公司">
          <option value="">{{ isAdmin ? '全部公司' : '所属公司' }}</option>
          <option v-for="company in companies" :key="company.id" :value="company.id">
            {{ company.name }}
          </option>
        </select>
        <form v-if="tab === 'employees'" @submit.prevent="search">
          <input
            v-model="query"
            aria-label="搜索员工"
            placeholder="姓名、账号或工号"
            maxlength="100"
          /><button class="button button-secondary">搜索</button>
        </form>
      </div>
      <div class="filters">
        <button class="button button-secondary" :disabled="busy" @click="refresh">刷新</button
        ><button
          v-if="tab !== 'companies' || isAdmin"
          class="button button-primary"
          @click="edit(null)"
        >
          新增{{ tabs[tab] }}
        </button>
      </div>
    </div>
    <p v-if="success" class="notice" role="status">{{ success }}</p>
    <p v-if="error" class="notice notice-error" role="alert">
      {{ error }}。旧版账号尚未分配公司时，请由管理员先建公司并调整员工归属。
    </p>
    <div class="table-scroll" :aria-busy="busy">
      <table>
        <thead>
          <tr>
            <th>{{ tab === 'employees' ? '姓名 / 账号' : '名称' }}</th>
            <th v-if="tab !== 'companies'">公司</th>
            <template v-if="tab === 'employees'"
              ><th>工号</th>
              <th>部门</th>
              <th>角色</th></template
            ><template v-if="tab === 'workplaces'"
              ><th>经纬度 / 坐标系</th>
              <th>半径</th></template
            >
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in result.items" :key="row.userId || row.id">
            <td>
              <b>{{ row.displayName || row.name }}</b
              ><small v-if="row.username" class="org-account">{{ row.username }}</small>
            </td>
            <td v-if="tab !== 'companies'">{{ companyName(row.companyId) }}</td>
            <template v-if="tab === 'employees'"
              ><td>{{ row.employeeNo }}</td>
              <td>{{ row.departmentName || '暂未分配' }}</td>
              <td>{{ roles[row.role] }}</td></template
            ><template v-if="tab === 'workplaces'"
              ><td>{{ row.latitude }}, {{ row.longitude }} / {{ row.coordinateSystem }}</td>
              <td>{{ row.radiusMeters }}米</td></template
            >
            <td>{{ row.enabled ? '启用' : '停用' }}</td>
            <td>
              <button
                v-if="
                  (tab !== 'companies' || isAdmin) &&
                  (tab !== 'employees' || isAdmin || row.role === 'EMPLOYEE')
                "
                class="text-button"
                @click="edit(row)"
              >
                编辑</button
              ><span v-else class="muted">只读</span>
            </td>
          </tr>
          <tr v-if="!result.items.length">
            <td colspan="8" class="table-empty">{{ busy ? '正在加载…' : '暂无记录' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="table-footer">
      共 {{ result.total }} 条，第 {{ page }} 页
      <div class="filters">
        <button
          class="button button-secondary"
          :disabled="page === 1 || busy"
          @click="changePage(-1)"
        >
          上一页</button
        ><button
          class="button button-secondary"
          :disabled="page * 20 >= result.total || busy"
          @click="changePage(1)"
        >
          下一页
        </button>
      </div>
    </div>
  </section>
  <dialog
    ref="dialog"
    class="org-dialog"
    @close="dialogClosed"
    :aria-label="dialogTitle"
    @cancel="!saving || $event.preventDefault()"
  >
    <form @submit.prevent="save">
      <h2>{{ dialogTitle }}</h2>
      <p v-if="tab === 'employees'" class="muted small-text">
        {{
          editingId
            ? '可调整账号角色、重设密码或停用账号。密码留空则保持原密码。'
            : '同时建立登录账号与人员档案。选择角色并设置初始密码，保存后即可用该账号登录。'
        }}
      </p>
      <p v-if="accountFlow && !editingId" class="muted small-text">
        员工和人事账号需选择公司；如果尚未建立公司，请先在“组织与场所”添加公司。
      </p>
      <p v-if="formError" class="notice" role="alert">{{ formError }}</p>
      <div class="org-form">
        <template v-if="tab === 'employees'"
          ><label
            >登录账号<input
              v-model="form.username"
              required
              minlength="3"
              maxlength="50"
              :disabled="!!editingId"
              autocomplete="off" /></label
          ><label>姓名<input v-model="form.displayName" required maxlength="100" /></label
          ><label>工号<input v-model="form.employeeNo" required maxlength="30" /></label
          ><label
            >角色<select v-model="form.role">
              <option
                v-for="(name, value) in roles"
                v-show="isAdmin || value === 'EMPLOYEE'"
                :key="value"
                :value="value"
              >
                {{ name }}
              </option>
            </select></label
          ><label>
            {{ editingId ? '重设密码（留空保留）' : '初始密码（至少4位）'
            }}<input
              v-model="form.password"
              type="password"
              :required="!editingId"
              minlength="4"
              maxlength="72"
              autocomplete="new-password" /></label
        ></template>
        <label v-else>名称<input v-model="form.name" required maxlength="100" /></label>
        <label v-if="tab !== 'companies'"
          >公司<select
            v-model="form.companyId"
            :required="tab !== 'employees' || form.role !== 'ADMIN'"
            :disabled="!!editingId && tab !== 'employees'"
            @change="changeCompany"
          >
            <option :value="null">暂未分配</option>
            <option
              v-for="company in companies.filter((c) => c.enabled || c.id === form.companyId)"
              :key="company.id"
              :value="company.id"
            >
              {{ company.name }}
            </option>
          </select></label
        >
        <template v-if="tab === 'employees'"
          ><label
            >部门<select v-model="form.departmentId">
              <option :value="null">暂未分配</option>
              <option
                v-for="department in formDepartments"
                :key="department.id"
                :value="department.id"
              >
                {{ department.name }}
              </option>
            </select></label
          >
          <fieldset class="org-bindings">
            <legend>绑定办公场所</legend>
            <label v-for="place in formPlaces" :key="place.id"
              ><input v-model="form.workplaceIds" type="checkbox" :value="place.id" />{{
                place.name
              }}</label
            >
            <p v-if="!formPlaces.length" class="muted">该公司暂无启用场所，可先创建场所。</p>
          </fieldset></template
        >
        <template v-if="tab === 'workplaces'"
          ><label
            >纬度<input
              v-model.number="form.latitude"
              type="number"
              required
              min="-90"
              max="90"
              step="0.0000001" /></label
          ><label
            >经度<input
              v-model.number="form.longitude"
              type="number"
              required
              min="-180"
              max="180"
              step="0.0000001" /></label
          ><label
            >坐标系<select v-model="form.coordinateSystem" aria-label="坐标系">
              <option>WGS84</option>
              <option>GCJ02</option>
              <option>BD09</option>
            </select></label
          ><label
            >打卡半径（米）<input
              v-model.number="form.radiusMeters"
              type="number"
              required
              min="10"
              max="10000"
              step="1" /></label
          ><button
            class="button button-secondary"
            type="button"
            :disabled="locating || saving"
            @click="locate"
          >
            {{ locating ? '正在采集位置…' : '采集设备位置' }}</button
          ><MapPicker
            v-if="dialogVisible"
            :latitude="form.latitude"
            :longitude="form.longitude"
            :coordinate-system="form.coordinateSystem"
            @pick="Object.assign(form, $event)"
          />
          <div v-if="locationPreview" class="notice org-bindings" role="status">
            <p>
              设备位置预览（WGS84）：{{ locationPreview.latitude }}，{{
                locationPreview.longitude
              }}；精度约 {{ Math.ceil(locationPreview.accuracy) }} 米。
            </p>
            <p v-if="locationPreview.accuracy > form.radiusMeters">
              定位精度超过打卡半径，建议重新采集或核对准确坐标。
            </p>
            <p>确认这是办公场所的位置后，再采用坐标。当前位置尚未填入或保存。</p>
            <button type="button" class="button button-secondary" @click="useLocation">
              采用此位置
            </button>
            <button type="button" class="button button-secondary" @click="discardLocation">
              放弃此位置
            </button>
          </div>
          <p class="muted org-bindings">
            设备位置为WGS84，高德选点为GCJ02，两者均可用于排班打卡。GCJ02由服务端换算后校验距离，请核对真实办公位置。BD09场所请先提供WGS84或GCJ02坐标。
          </p></template
        >
        <label
          >状态<select v-model="form.enabled">
            <option :value="true">启用</option>
            <option :value="false">停用</option>
          </select></label
        >
      </div>
      <div class="org-actions">
        <button
          type="button"
          class="button button-secondary"
          :disabled="saving"
          @click="dialog.close()"
        >
          取消</button
        ><button class="button button-primary" :disabled="saving">
          {{ saving ? '正在保存…' : accountFlow && !editingId ? '分配账号' : '保存' }}
        </button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.organization-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 20px 24px 0;
}
.org-account {
  display: block;
  color: var(--text-muted);
  margin-top: 4px;
}
.org-dialog {
  width: min(720px, calc(100% - 32px));
  max-height: 90vh;
  overflow: auto;
  border: 1px solid #dce3ee;
  border-radius: 16px;
  padding: 24px;
  color: inherit;
}
.org-dialog::backdrop {
  background: #172b4d70;
}
.org-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 20px;
}
.org-form > label {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.org-form input:not([type='checkbox']),
.org-form select {
  width: 100%;
  min-width: 0;
  padding: 10px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
}
.org-bindings {
  grid-column: 1 / -1;
  border: 0;
  padding: 0;
}
.org-bindings label {
  display: inline-flex;
  gap: 6px;
  margin: 10px 16px 0 0;
}
.org-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}
.filters form {
  display: flex;
  gap: 8px;
}
@media (max-width: 600px) {
  .org-form {
    grid-template-columns: 1fr;
  }
  .table-toolbar,
  .table-footer {
    flex-wrap: wrap;
    gap: 12px;
  }
  .filters {
    flex-wrap: wrap;
  }
}
</style>
