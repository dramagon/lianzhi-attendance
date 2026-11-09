<script setup>
import { computed, ref, watch, onUnmounted } from "vue";
import { onShow, onHide, onUnload } from "@dcloudio/uni-app";
import AcceptancePanel from "../../components/AcceptancePanel.vue";
import { createClient } from "../../lib/client.js";
import {
  uuid,
  validToken,
  freshLocation,
  freshProof,
  readPending,
  savePending,
  multipart,
  validRequestId,
} from "../../lib/punch.js";
// #ifdef H5
import {
  decodeImage,
  readImage,
  pickImage,
  releaseImage,
} from "../../lib/h5-image.js";
// #endif
const labels = {
  NORMAL: "正常出勤",
  ON_LEAVE: "请假",
  LATE: "迟到",
  EARLY: "早退",
  LATE_AND_EARLY: "迟到且早退",
  REST_DAY: "休息日",
  MISSING_PUNCH: "缺卡",
  ABSENT: "缺勤",
  PENDING: "待结算",
};
const base = import.meta.env.VITE_API_BASE_URL || "";
let nativeCookie = false;
// #ifndef H5
nativeCookie = true;
// #endif
const user = ref(null),
  profile = ref(null),
  face = ref(null),
  capability = ref(null),
  places = ref([]);
const username = ref(""),
  password = ref(""),
  busy = ref(false),
  error = ref(""),
  message = ref(""),
  view = ref("punch");
const token = ref(""),
  direction = ref("IN"),
  location = ref(null),
  requestId = ref(uuid()),
  proof = ref(null),
  optionalFace = ref(false),
  consent = ref(false),
  photo = ref(""),
  capturing = ref(false);
const receiptId = ref("");
const pending = ref(null),
  days = ref(null),
  records = ref(null),
  recordsPage = ref(1);
const from = ref(""),
  to = ref(""),
  now = ref(Date.now());
let sessionEpoch = 0,
  operation = 0,
  recordsRevision = 0,
  profileRevision = 0,
  captureRevision = 0,
  captureAbort = null,
  disposed = false;
const client = createClient(uni, {
  base,
  cookieHeader: nativeCookie,
  expired: () => resetSession(),
});
const required = computed(
  () =>
    face.value?.punchRequired ||
    places.value.some((place) => place.faceRequired),
);
const selectedFace = computed(() => required.value || optionalFace.value);
const canPunch = computed(
  () =>
    user.value &&
    capability.value?.available &&
    !busy.value &&
    !pending.value &&
    validToken(token.value.trim()) &&
    freshLocation(location.value, now.value) &&
    (!selectedFace.value || freshProof(proof.value, now.value)),
);
const canVerify = computed(
  () =>
    face.value?.available &&
    face.value.enrolled &&
    selectedFace.value &&
    validToken(token.value.trim()) &&
    freshLocation(location.value, now.value) &&
    !!photo.value &&
    consent.value &&
    !pending.value,
);
const dayText = (value) => (value ? String(value).replace("T", " ") : "暂无");
function businessDate(value = new Date()) {
  // Use a UTC-shifted date so it works in mini program engines without full Intl support.
  return new Date(value.getTime() + 8 * 3600000).toISOString().slice(0, 10);
}
function instantText(value) {
  const time = Date.parse(value);
  return Number.isFinite(time)
    ? new Date(time + 8 * 3600000)
        .toISOString()
        .slice(0, 19)
        .replace("T", " ") + "（北京时间）"
    : "暂无";
}
function invalidate() {
  operation++;
  proof.value = null;
  requestId.value = uuid();
}
function saveReceipt(value) {
  pending.value = value;
  if (user.value) savePending(uni, user.value.id, value);
}
function cancelCapture() {
  captureRevision++;
  captureAbort?.abort();
  captureAbort = null;
  capturing.value = false;
  photo.value = "";
}
function suspend() {
  profileRevision++;
  operation++;
  recordsRevision++;
  busy.value = false;
  proof.value = null;
  photo.value = "";
  location.value = null;
}
function resetSession() {
  sessionEpoch++;
  cancelCapture();
  suspend();
  user.value = profile.value = face.value = capability.value = null;
  places.value = [];
  pending.value = null;
  days.value = records.value = null;
  password.value = "";
  token.value = "";
  from.value = to.value = "";
  consent.value = false;
  optionalFace.value = false;
  client.clear();
}
function switchView(next) {
  if (next === view.value) return;
  cancelCapture();
  suspend();
  consent.value = false;
  view.value = next;
  if (next === "records") loadRecords();
}
async function action(task) {
  if (busy.value || disposed) return;
  const epoch = sessionEpoch,
    current = operation;
  busy.value = true;
  error.value = message.value = "";
  try {
    await task(epoch, current);
  } catch (e) {
    if (!disposed && epoch === sessionEpoch && current === operation)
      error.value = e.message;
  } finally {
    if (!disposed && epoch === sessionEpoch && current === operation)
      busy.value = false;
  }
}
const current = (epoch, op) =>
  !disposed && epoch === sessionEpoch && op === operation;
async function loadProfile() {
  const epoch = sessionEpoch,
    revision = ++profileRevision;
  const [person, status, faceStatus, workplaces] = await Promise.all([
    client.request("/employees/me"),
    client.request("/attendance/status"),
    client.request("/face/status"),
    client.request("/attendance/me/workplaces"),
  ]);
  if (disposed || epoch !== sessionEpoch || revision !== profileRevision)
    return;
  profile.value = person;
  capability.value = status;
  face.value = faceStatus;
  places.value = workplaces;
  const date = businessDate(new Date(status.serverTime));
  if (!from.value) from.value = `${date.slice(0, 8)}01`;
  if (!to.value) to.value = date;
}
async function signIn() {
  if (busy.value) return;
  sessionEpoch++;
  await action(async (epoch, op) => {
    if (nativeCookie && !base)
      throw new Error("小程序须配置VITE_API_BASE_URL为合法HTTPS服务地址");
    const account = await client.login(username.value.trim(), password.value);
    password.value = "";
    if (!current(epoch, op)) return;
    user.value = account;
    pending.value = readPending(uni, account.id);
    await loadProfile();
    if (!current(epoch, op)) return;
    message.value = "登录成功，已读取本人档案和服务状态";
  });
}
async function signOut() {
  await action(async () => {
    await client.write("POST", "/auth/logout");
    resetSession();
  });
}
async function locate() {
  invalidate();
  location.value = null;
  await action(async (epoch, op) => {
    const position = await new Promise((resolve, reject) =>
      uni.getLocation({
        type: "wgs84",
        isHighAccuracy: true,
        success: resolve,
        fail: () =>
          reject(
            new Error("定位失败或权限被拒绝，请检查HTTPS、定位权限与GPS后重试"),
          ),
      }),
    );
    if (!current(epoch, op)) return;
    const value = {
      latitude: position.latitude,
      longitude: position.longitude,
      accuracy: position.accuracy,
      timestamp: Date.now(),
    };
    if (!freshLocation(value))
      throw new Error("定位精度不足，请到开阔处重新定位（需100米以内）");
    location.value = value;
    message.value = "已获取本次WGS84定位，60秒内有效";
  });
}
function chooseImage(cameraOnly = false, signal) {
  // #ifdef H5
  return pickImage(cameraOnly, signal);
  // #endif
  // #ifndef H5
  return new Promise((resolve, reject) =>
    uni.chooseImage({
      count: 1,
      sizeType: ["compressed"],
      sourceType: cameraOnly ? ["camera"] : ["camera", "album"],
      success: (result) => resolve(result.tempFilePaths[0]),
      fail: () => reject(new Error("采集已取消或权限被拒绝，请重试")),
    }),
  );
  // #endif
}
async function scan() {
  invalidate();
  token.value = "";
  await action(async (epoch, op) => {
    let value;
    // #ifdef H5
    const imagePath = await chooseImage();
    try {
      value = await decodeImage(imagePath);
    } finally {
      releaseImage(imagePath);
    }
    // #endif
    // #ifndef H5
    value = await new Promise((resolve, reject) =>
      uni.scanCode({
        onlyFromCamera: true,
        scanType: ["qrCode"],
        success: (result) => resolve(result.result),
        fail: () => reject(new Error("扫码已取消或摄像头权限被拒绝")),
      }),
    );
    // #endif
    if (!current(epoch, op)) return;
    if (!validToken(value))
      throw new Error("这不是有效考勤二维码，请重新扫描管理人员签发的二维码");
    token.value = value;
    message.value = "已识别二维码；仍须由服务端校验签名、时效和场所";
  });
}
async function capturePhoto() {
  if (!consent.value || !face.value?.available || busy.value || capturing.value)
    return;
  cancelCapture();
  const epoch = sessionEpoch,
    revision = captureRevision,
    screen = view.value;
  invalidate();
  capturing.value = true;
  error.value = message.value = "";
  // #ifdef H5
  captureAbort = new AbortController();
  // #endif
  try {
    const value = await chooseImage(true, captureAbort?.signal);
    if (
      disposed ||
      epoch !== sessionEpoch ||
      revision !== captureRevision ||
      !consent.value ||
      view.value !== screen ||
      !user.value
    ) {
      // #ifdef H5
      releaseImage(value);
      // #endif
      return;
    }
    photo.value = value;
    message.value = "已取得本次采集图像，尚未上传；请核对授权后提交";
  } catch (e) {
    if (!disposed && epoch === sessionEpoch && revision === captureRevision)
      error.value = e.message;
  } finally {
    if (revision === captureRevision) {
      capturing.value = false;
      captureAbort = null;
    }
  }
}
async function photoBytes() {
  let bytes;
  // #ifdef H5
  bytes = await readImage(photo.value);
  // #endif
  // #ifndef H5
  bytes = await new Promise((resolve, reject) =>
    uni.getFileSystemManager().readFile({
      filePath: photo.value,
      success: (result) => resolve(result.data),
      fail: () => reject(new Error("图像读取失败，请重新拍摄")),
    }),
  );
  // #endif
  if (!bytes?.byteLength || bytes.byteLength > 2 * 1024 * 1024)
    throw new Error("请拍摄不超过2MB的JPEG或PNG图像");
  return bytes;
}
async function submitPhoto(verify = false) {
  if (
    !consent.value ||
    !photo.value ||
    !face.value?.available ||
    (verify && !canVerify.value)
  )
    return;
  await action(async (epoch, op) => {
    proof.value = null;
    let bytes, body;
    try {
      let challenge;
      if (verify)
        challenge = await client.write("POST", "/face/challenges", {
          token: token.value.trim(),
          requestId: requestId.value,
          type: direction.value,
        });
      if (!current(epoch, op)) return;
      bytes = await photoBytes();
      if (!current(epoch, op)) return;
      body = multipart(
        {
          consent: "true",
          ...(verify
            ? { challengeId: challenge.id }
            : face.value.enrolled
              ? { expectedRevision: String(face.value.revision) }
              : {}),
        },
        bytes,
      );
      const result = await client.write(
        verify || !face.value.enrolled ? "POST" : "PUT",
        verify ? "/face/verification" : "/face/enrollment",
        body.data,
        { "Content-Type": body.type },
      );
      if (!current(epoch, op)) return;
      photo.value = "";
      if (verify) {
        if (result.verified !== true || !freshProof(result))
          throw new Error(
            result.message ||
              "实际活体或本人比对未通过，请重新采集；未提交打卡",
          );
        proof.value = {
          verificationId: result.verificationId,
          expiresAt: result.expiresAt,
        };
        message.value =
          "服务端人脸验证通过，尚未打卡；请在有效期内提交对应请求";
      } else {
        await loadProfile();
        message.value = "登记已由服务端确认，旧验证凭据已失效";
      }
    } finally {
      if (bytes) new Uint8Array(bytes).fill(0);
      if (body) new Uint8Array(body.data).fill(0);
    }
  });
}
async function removeEnrollment() {
  if (!face.value?.deletionAvailable) return;
  const confirmed = await new Promise((resolve) =>
    uni.showModal({
      confirmText: "确认",
      cancelText: "取消",
      title: "删除本人登记",
      content: "撤销本人登记及未用验证，原始考勤保留。确认删除？",
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false),
    }),
  );
  if (!confirmed) return;
  invalidate();
  await action(async () => {
    await client.write(
      "DELETE",
      `/face/enrollment?expectedRevision=${face.value.revision}`,
    );
    await loadProfile();
    message.value = "已删除本人特征并撤销未用验证";
  });
}
async function punch() {
  if (!canPunch.value) return;
  const confirmed = await new Promise((resolve) =>
    uni.showModal({
      confirmText: "确认",
      cancelText: "取消",
      title: "确认打卡",
      content: `确认${direction.value === "IN" ? "上班" : "下班"}打卡？服务器记录实际时间。`,
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false),
    }),
  );
  if (!confirmed || !canPunch.value) return;
  await action(async (epoch, op) => {
    const receipt = {
      userId: user.value.id,
      requestId: requestId.value,
      type: direction.value,
    };
    const usedFace = selectedFace.value;
    saveReceipt(receipt);
    try {
      const event = await client.write("POST", "/attendance/me/punches", {
        type: direction.value,
        token: token.value.trim(),
        requestId: requestId.value,
        location: {
          latitude: location.value.latitude,
          longitude: location.value.longitude,
          coordinateSystem: "WGS84",
        },
        accuracyMeters: location.value.accuracy,
        faceVerificationId: usedFace ? proof.value.verificationId : null,
      });
      if (!current(epoch, op)) return;
      saveReceipt(null);
      token.value = "";
      proof.value = null;
      photo.value = "";
      message.value = `服务器已确认${usedFace ? "含人脸凭据的" : ""}打卡 · 记录#${event.id} · ${instantText(event.occurredAt)}`;
    } catch (e) {
      if (current(epoch, op) && e.status && e.status < 500) saveReceipt(null);
      throw e;
    }
  });
}
async function checkReceipt() {
  if (!pending.value) return;
  await action(async (epoch, op) => {
    const id = pending.value.requestId;
    const receipt = await client.request(
      `/attendance/me/punches/requests/${id}`,
    );
    if (!current(epoch, op)) return;
    if (receipt.found) {
      saveReceipt(null);
      token.value = "";
      proof.value = null;
      message.value = `已从服务器核对记录#${receipt.event.id}，没有重新提交打卡`;
    } else
      message.value =
        "暂未查到记录，请稍后再核对；不会自动重发。确认历史后可明确放弃待核对请求。";
  });
}
async function lookupOtherReceipt() {
  if (!validRequestId(receiptId.value.trim())) {
    error.value = "请输入其他端显示的完整请求编号";
    return;
  }
  await action(async (epoch, op) => {
    const id = receiptId.value.trim();
    const result = await client.request(
      `/attendance/me/punches/requests/${id}`,
    );
    if (!current(epoch, op)) return;
    if (result.found) {
      if (pending.value?.requestId === id) saveReceipt(null);
      message.value = `已核对其他端原始记录#${result.event.id}，本端没有补发打卡`;
    } else
      message.value =
        "当前账号未查到该编号，请核对身份及编号；未自动提交任何打卡";
  });
}
async function abandonReceipt() {
  const result = await new Promise((resolve) =>
    uni.showModal({
      confirmText: "确认",
      cancelText: "取消",
      title: "放弃待核对请求",
      content:
        "原请求可能已提交。请先核对本人记录；放弃不会删除服务器数据，也不会自动重新打卡。",
      success: resolve,
      fail: () => resolve({ confirm: false }),
    }),
  );
  if (result.confirm) {
    saveReceipt(null);
    invalidate();
    token.value = "";
    message.value = "已清理本地待核对编号；后台数据保留，请重新扫码和定位";
  }
}
async function loadRecords(page = 1) {
  const seq = ++recordsRevision,
    epoch = sessionEpoch;
  days.value = records.value = null;
  error.value = "";
  try {
    if (
      !from.value ||
      !to.value ||
      to.value < from.value ||
      (Date.parse(to.value) - Date.parse(from.value)) / 86400000 > 31
    )
      throw new Error("查询日期须有效，移动端每次最多32天");
    const range = `from=${from.value}&to=${to.value}`;
    const [events, daily] = await Promise.all([
      client.request(`/attendance/me/records?${range}&page=${page}&size=20`),
      client.request(`/attendance/me/days?${range}`),
    ]);
    if (!disposed && epoch === sessionEpoch && seq === recordsRevision) {
      records.value = events;
      days.value = daily;
      recordsPage.value = page;
    }
  } catch (e) {
    if (!disposed && epoch === sessionEpoch && seq === recordsRevision)
      error.value = e.message;
  }
}
watch(
  [token, direction, selectedFace],
  () => {
    proof.value = null;
    photo.value = "";
    requestId.value = uuid();
  },
  { flush: "sync" },
);
// #ifdef H5
watch(
  photo,
  (value, previous) => {
    if (previous && previous !== value) releaseImage(previous);
  },
  { flush: "sync" },
);
// #endif
watch(consent, (value) => {
  if (!value) {
    cancelCapture();
    invalidate();
  }
});
watch([from, to], () => {
  recordsRevision++;
  records.value = days.value = null;
});
const timer = setInterval(() => {
  now.value = Date.now();
}, 1000);
onShow(async () => {
  if (user.value) {
    await action(loadProfile);
    if (user.value && view.value === "records") await loadRecords();
    return;
  }
  if (!user.value && !nativeCookie) {
    const epoch = sessionEpoch;
    try {
      const account = await client.request("/auth/session");
      if (epoch !== sessionEpoch) return;
      user.value = account;
      pending.value = readPending(uni, account.id);
      await loadProfile();
    } catch (e) {
      if (!disposed && epoch === sessionEpoch && user.value)
        error.value = e.message;
    }
  }
});
onHide(suspend);
// #ifdef H5
function visibilityChanged() {
  if (document.hidden) suspend();
}
window.addEventListener("pagehide", suspend);
document.addEventListener("visibilitychange", visibilityChanged);
// #endif
onUnload(() => {
  cancelCapture();
  disposed = true;
  suspend();
  clearInterval(timer);
});
onUnmounted(() => {
  cancelCapture();
  // #ifdef H5
  window.removeEventListener("pagehide", suspend);
  document.removeEventListener("visibilitychange", visibilityChanged);
  // #endif
  disposed = true;
  clearInterval(timer);
});
</script>
<template>
  <view class="page">
    <view class="hero"
      ><text class="title">联智员工移动端</text
      ><text class="muted">独立 uni-app · 本人考勤与身份验证</text></view
    >
    <view v-if="error" class="notice error" role="alert">{{ error }}</view>
    <view v-if="message" class="notice" role="status">{{ message }}</view>
    <view v-if="!user" class="card">
      <text class="heading">登录员工账号</text>
      <text class="muted">使用现有系统账号；不在移动端创建另一套身份。</text>
      <input
        id="mobile-account"
        v-model="username"
        placeholder="账号"
        :disabled="busy"
        maxlength="100"
      />
      <input
        id="mobile-password"
        v-model="password"
        placeholder="密码"
        :password="true"
        :disabled="busy"
        maxlength="200"
      />
      <button
        role="button"
        type="primary"
        :aria-disabled="busy || !username.trim() || !password"
        :disabled="busy || !username.trim() || !password"
        @click="signIn"
      >
        {{ busy ? "正在登录…" : "登录" }}
      </button>
      <button role="button" @click="view = 'acceptance'">
        离线填写设备验收
      </button>
      <text class="muted"
        >如无法登录，请核对服务地址与网络；摄像头和定位需可信HTTPS。</text
      >
    </view>
    <template v-else>
      <view class="card identity"
        ><text
          >{{ profile?.displayName || user.displayName }} ·
          {{ user.roleName }}</text
        ><text class="muted"
          >工号 {{ profile?.employeeNo || "待读取" }} ·
          {{ places.length }}个授权场所</text
        ><button
          role="button"
          size="mini"
          :aria-disabled="busy"
          :disabled="busy"
          @click="signOut"
        >
          退出登录
        </button></view
      >
      <view class="tabs"
        ><button
          role="button"
          v-for="item in [
            { id: 'punch', title: '打卡' },
            { id: 'records', title: '本人记录' },
            { id: 'face', title: '人脸登记' },
            { id: 'acceptance', title: '设备验收' },
          ]"
          :key="item.id"
          :class="{ active: view === item.id }"
          :aria-disabled="busy"
          :disabled="busy"
          @click="switchView(item.id)"
        >
          {{ item.title }}
        </button></view
      >
      <view v-if="capturing" class="notice">
        <text>正在等待系统拍摄；尚未上传图像。</text>
        <button role="button" @click="cancelCapture">取消本次采集</button>
      </view>
      <view v-if="pending" class="card pending">
        <text class="heading">有一笔打卡待核对</text
        ><text class="muted"
          >编号
          {{ pending.requestId }}。不要重复提交；核对会读取原始回执。</text
        >
        <button
          role="button"
          :aria-disabled="busy"
          :disabled="busy"
          @click="checkReceipt"
        >
          核对原始回执</button
        ><button
          role="button"
          :aria-disabled="busy"
          :disabled="busy"
          @click="abandonReceipt"
        >
          确认历史后放弃本地待核对编号
        </button>
      </view>
      <view v-if="view === 'punch'" class="card">
        <text class="heading">上下班打卡</text
        ><text class="muted">{{
          capability?.available
            ? "服务已就绪，请扫码、定位并确认提交"
            : capability?.blockers?.join("；") || "请刷新本人档案与服务状态"
        }}</text>
        <view class="actions"
          ><button
            role="button"
            :aria-disabled="busy || !!pending"
            :disabled="busy || !!pending"
            :type="direction === 'IN' ? 'primary' : 'default'"
            @click="direction = 'IN'"
          >
            上班</button
          ><button
            role="button"
            :aria-disabled="busy || !!pending"
            :disabled="busy || !!pending"
            :type="direction === 'OUT' ? 'primary' : 'default'"
            @click="direction = 'OUT'"
          >
            下班
          </button></view
        >
        <button
          role="button"
          :aria-disabled="busy || !!pending"
          :disabled="busy || !!pending"
          @click="scan"
        >
          扫码或识别二维码
        </button>
        <input
          v-model="token"
          placeholder="考勤二维码内容"
          :disabled="busy || !!pending"
          maxlength="512"
        />
        <button
          role="button"
          :aria-disabled="busy || !!pending"
          :disabled="busy || !!pending"
          @click="locate"
        >
          获取本次定位
        </button>
        <text class="muted">{{
          freshLocation(location, now)
            ? `定位已就绪，精度约${Math.round(location.accuracy)}米`
            : "定位尚未就绪或已过期"
        }}</text>
        <view class="face-option">
          <text v-if="required" class="muted"
            >本次策略要求真实人脸，客户端不能取消。</text
          >
          <label v-else-if="face?.available && face.enrolled"
            ><switch
              :checked="optionalFace"
              :disabled="busy || !!pending"
              @change="optionalFace = $event.detail.value"
            />本次使用人脸验证</label
          >
          <text v-else class="muted"
            >人脸能力：{{ face?.message || "尚未读取" }}</text
          >
          <template v-if="selectedFace">
            <text class="muted"
              >先在下方授权拍摄，再定位和验证；验证通过仍需提交打卡。</text
            >
            <text v-if="!face?.enrolled" class="muted"
              >请先在人脸登记页完成本人登记。</text
            >
            <checkbox-group
              @change="consent = $event.detail.value.includes('yes')"
              ><label
                ><checkbox
                  value="yes"
                  :checked="consent"
                  :disabled="busy"
                />同意本次实时人脸采集及服务端检测、活体和比对</label
              ></checkbox-group
            >
            <button
              role="button"
              :aria-disabled="
                capturing ||
                busy ||
                !consent ||
                !face?.available ||
                !face.enrolled ||
                !!pending
              "
              :disabled="
                capturing ||
                busy ||
                !consent ||
                !face?.available ||
                !face.enrolled ||
                !!pending
              "
              @click="capturePhoto"
            >
              拍摄本次人脸
            </button>
            <text v-if="photo" class="muted"
              >已取得本次临时图像，尚未上传；切后台或撤回授权会清除引用。</text
            >
            <button
              role="button"
              :aria-disabled="busy || !canVerify"
              :disabled="busy || !canVerify"
              @click="submitPhoto(true)"
            >
              提交本次人脸验证
            </button>
            <text class="muted">{{
              freshProof(proof, now)
                ? "服务端验证通过，凭据仍有效"
                : "尚无有效人脸凭据；失败或过期不会退回普通打卡"
            }}</text>
          </template>
        </view>
        <button
          role="button"
          type="primary"
          :aria-disabled="!canPunch"
          :disabled="!canPunch"
          @click="punch"
        >
          确认打卡
        </button>
        <text class="muted"
          >打卡时间与结果以服务器为准。失败不会生成成功记录；结果未知时查回执。</text
        >
      </view>
      <view v-if="view === 'records'" class="card">
        <text class="heading">本人记录与日结果</text>
        <text class="muted"
          >日期按北京时间；每次最多32天，无排班记录不等于缺勤。</text
        >
        <picker mode="date" :value="from" @change="from = $event.detail.value"
          ><view class="date-field">开始日期 {{ from }}</view></picker
        >
        <picker mode="date" :value="to" @change="to = $event.detail.value"
          ><view class="date-field">结束日期 {{ to }}</view></picker
        >
        <button
          role="button"
          :aria-disabled="busy"
          :disabled="busy"
          @click="loadRecords(1)"
        >
          查询本人记录
        </button>
        <template v-if="records">
          <text class="heading">原始事件 · 共{{ records.total }}条</text>
          <text v-if="!records.items.length" class="muted"
            >此范围没有原始打卡事件，不推算出勤。</text
          >
          <view v-for="event in records.items" :key="event.id" class="entry"
            ><text
              >{{ event.type === "IN" ? "上班" : "下班" }} · #{{ event.id }} ·
              {{ event.businessDate }}</text
            ><text class="muted"
              >{{ instantText(event.occurredAt) }} · 场所{{
                event.workplaceId
              }}</text
            ></view
          >
          <view class="actions"
            ><button
              role="button"
              :aria-disabled="recordsPage <= 1"
              :disabled="recordsPage <= 1"
              @click="loadRecords(recordsPage - 1)"
            >
              上一页</button
            ><button
              role="button"
              :disabled="recordsPage * 20 >= records.total"
              @click="loadRecords(recordsPage + 1)"
            >
              下一页
            </button></view
          >
          <text class="heading">日考勤结果</text
          ><text v-if="!days?.length" class="muted">没有正式排班日记录。</text>
          <view v-for="day in days" :key="day.businessDate" class="entry"
            ><text
              >{{ day.businessDate }} ·
              {{ labels[day.attendance.status] || day.attendance.status }}</text
            ><text class="muted"
              >签到{{ dayText(day.checkIn) }} · 签退{{
                dayText(day.checkOut)
              }}</text
            ><text class="muted"
              >工时{{ day.attendance.workMinutes }}分钟 · 请假{{
                day.leaveMinutes
              }}分钟 · 加班{{ day.overtimeMinutes }}分钟</text
            ><text class="muted"
              >审批来源：{{ day.approvalIds.join("、") || "无审批调整" }}</text
            ></view
          >
        </template>
      </view>
      <view v-if="view === 'face'" class="card">
        <text class="heading">本人登记管理</text
        ><text class="muted">{{ face?.message }}</text
        ><text
          >登记状态：{{
            face?.enrolled ? `已登记 · 版本${face.revision}` : "尚未登记"
          }}</text
        >
        <checkbox-group @change="consent = $event.detail.value.includes('yes')"
          ><label
            ><checkbox
              value="yes"
              :checked="consent"
              :disabled="busy"
            />同意本次实时人脸采集与服务端处理</label
          ></checkbox-group
        >
        <button
          role="button"
          :aria-disabled="capturing || busy || !consent || !face?.available"
          :disabled="capturing || busy || !consent || !face?.available"
          @click="capturePhoto"
        >
          拍摄登记图像
        </button>
        <text v-if="photo" class="muted"
          >已取得本次图像，未上传。撤回授权或切后台会清除。</text
        >
        <button
          role="button"
          :aria-disabled="busy || !photo || !consent || !face?.available"
          :disabled="busy || !photo || !consent || !face?.available"
          @click="submitPhoto(false)"
        >
          {{ face?.enrolled ? "确认更新本人登记" : "确认保存本人登记" }}
        </button>
        <button
          role="button"
          :aria-disabled="busy || !face?.deletionAvailable"
          :disabled="busy || !face?.deletionAvailable"
          @click="removeEnrollment"
        >
          删除本人登记
        </button>
        <text class="muted"
          >登记成功不等于本次身份验证或打卡成功；删除保留原始考勤。</text
        >
      </view>
      <view class="card">
        <text class="heading">跨端回执核对</text>
        <text class="muted"
          >可粘贴另一客户端显示的待核对编号，只查询当前账号原始回执，不自动补发。</text
        >
        <input
          id="mobile-receipt"
          v-model="receiptId"
          placeholder="其他端的待核对请求编号"
          maxlength="36"
          :disabled="busy"
        />
        <button
          role="button"
          :aria-disabled="busy || !validRequestId(receiptId.trim())"
          :disabled="busy || !validRequestId(receiptId.trim())"
          @click="lookupOtherReceipt"
        >
          核对其他端请求
        </button>
      </view>
      <AcceptancePanel
        v-show="view === 'acceptance'"
        :observations="{
          backendConnected: capability?.available === true,
          faceAvailable: face?.available === true,
          faceEnrolled: face?.enrolled === true,
          locationFresh: freshLocation(location, now) === true,
        }"
      />
      <button
        role="button"
        :aria-disabled="busy"
        :disabled="busy"
        @click="action(loadProfile)"
      >
        刷新本人档案与服务状态
      </button>
    </template>
    <AcceptancePanel v-if="!user && view === 'acceptance'" :observations="{}" />
    <text class="footer"
      >人脸识别由服务端已授权引擎执行；客户端不判断“本人”。H5及小程序的真机授权和HTTPS仍需现场验收。</text
    >
  </view>
</template>
<style scoped>
.page {
  max-width: 720px;
  padding: 20px 16px 40px;
  margin: auto;
}
.hero {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 12px 0 22px;
}
.title {
  font-size: 24px;
  font-weight: 700;
}
.card {
  background: white;
  border: 1px solid #e2e8f2;
  border-radius: 16px;
  padding: 18px;
  margin: 16px 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.heading {
  display: block;
  font-size: 17px;
  font-weight: 600;
}
.muted,
.footer {
  display: block;
  font-size: 12px;
  color: #65758e;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.notice {
  background: #edf4ff;
  padding: 14px;
  border-radius: 10px;
  margin-bottom: 12px;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.error {
  color: #b42318;
  background: #fff0ee;
}
input {
  border: 1px solid #d7e0ee;
  padding: 12px;
  border-radius: 8px;
  height: 46px;
  min-width: 0;
  font-size: 14px;
}
button {
  margin: 0;
  width: 100%;
}
.tabs,
.actions {
  display: flex;
  gap: 8px;
}
.tabs button {
  flex: 1;
  width: auto;
  min-width: 0;
  padding-left: 4px;
  padding-right: 4px;
  font-size: 13px;
}
.active {
  color: #215be2;
  background: #eaf1ff;
}
.entry {
  border-top: 1px solid #e4eaf3;
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 13px;
}
.date-field {
  padding: 12px;
  border: 1px solid #d7e0ee;
  border-radius: 8px;
  font-size: 14px;
}
label {
  font-size: 13px;
  line-height: 1.8;
  display: block;
}
.face-option {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.footer {
  margin-top: 22px;
}
</style>
