<script setup>
import { reactive, ref } from "vue";
import { acceptanceCases, buildAcceptanceReport } from "../lib/acceptance.js";
const props = defineProps({ observations: Object });
const device = reactive({
  model: "",
  system: "",
  browser: "",
  revision: "",
  eventId: "",
});
const states = reactive(
  acceptanceCases.reduce((all, item) => {
    all[item.id] = "NOT_RUN";
    return all;
  }, {}),
);
const result = ref(""),
  error = ref("");
const choices = ["未执行", "通过", "失败"],
  values = ["NOT_RUN", "PASS", "FAIL"];
let platform = "H5";
// #ifdef MP-WEIXIN
platform = "MP_WEIXIN";
// #endif
function observeDevice() {
  error.value = "";
  try {
    const info = uni.getSystemInfoSync();
    device.model = info.model || "";
    device.system = info.system || info.osName || "";
    // #ifdef H5
    device.browser = navigator.userAgent.slice(0, 120);
    // #endif
    // #ifdef MP-WEIXIN
    device.browser = `微信${info.version || ""} / 基础库${info.SDKVersion || ""}`;
    // #endif
    result.value = "已读取本机基本信息；这不代表任何验收项目通过，请核对填写。";
  } catch {
    error.value = "无法读取设备信息，请按实际设备手工填写";
  }
}
async function download() {
  error.value = result.value = "";
  try {
    const report = buildAcceptanceReport(
      device,
      states,
      props.observations,
      platform,
    );
    const text = JSON.stringify(report, null, 2),
      name = `lianzhi-acceptance-${Date.now()}.json`;
    // #ifdef H5
    const url = URL.createObjectURL(
      new Blob([text], { type: "application/json" }),
    );
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = name;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    result.value = "报告已导出；未执行项保留未执行，请交给A核对实际证据。";
    // #endif
    // #ifdef MP-WEIXIN
    const path = `${wx.env.USER_DATA_PATH}/${name}`;
    await new Promise((resolve, reject) =>
      uni.getFileSystemManager().writeFile({
        filePath: path,
        data: text,
        encoding: "utf8",
        success: resolve,
        fail: () => reject(new Error("报告写入失败，请检查小程序存储空间")),
      }),
    );
    if (typeof wx.shareFileMessage === "function") {
      try {
        await new Promise((resolve, reject) =>
          wx.shareFileMessage({
            filePath: path,
            fileName: name,
            success: resolve,
            fail: reject,
          }),
        );
        result.value = "已生成报告并完成本次分享操作，请由A核对实测证据。";
      } catch {
        result.value =
          "报告已生成在小程序本地，本次分享未完成；可在开发者工具导出，未宣称已交付。";
      }
    } else
      result.value =
        "报告已在小程序本地生成，请在开发者工具导出；当前版本不支持文件分享。";
    // #endif
  } catch (e) {
    error.value = e.message;
  }
}
</script>
<template>
  <view class="card audit" aria-label="设备现场验收">
    <text class="heading">设备现场验收</text>
    <text class="muted"
      >按当前手机实际操作逐项选择；设备观测不等于通过。不要填写密码、Cookie、二维码、坐标或人脸资料。本报告不会上传到服务器。</text
    >
    <button role="button" @click="observeDevice">读取本机基本信息</button>
    <input
      id="audit-model"
      v-model="device.model"
      placeholder="实际设备型号"
      maxlength="120"
    />
    <input
      id="audit-system"
      v-model="device.system"
      placeholder="实际系统版本"
      maxlength="120"
    />
    <input
      id="audit-browser"
      v-model="device.browser"
      placeholder="浏览器或微信版本"
      maxlength="120"
    />
    <input
      id="audit-revision"
      v-model="device.revision"
      placeholder="实测代码SHA"
      maxlength="40"
    />
    <input
      id="audit-event"
      v-model="device.eventId"
      placeholder="含人脸打卡的原始记录编号（可选）"
      maxlength="16"
    />
    <view v-for="item in acceptanceCases" :key="item.id" class="audit-case">
      <text>{{ item.label }}</text
      ><picker
        :range="choices"
        :value="values.indexOf(states[item.id])"
        @change="states[item.id] = values[Number($event.detail.value)]"
        ><view class="choice">{{
          choices[values.indexOf(states[item.id])]
        }}</view></picker
      >
    </view>
    <text class="muted"
      >未执行不得填通过；同一笔真实人脸打卡通过还须填写记录编号，由A对照服务器核实。H5自动化不等于手机或微信原生验收。</text
    >
    <view v-if="error" role="alert" class="notice error">{{ error }}</view>
    <view v-if="result" role="status" class="notice">{{ result }}</view>
    <button role="button" @click="download">导出或分享现场报告</button>
  </view>
</template>
<style scoped>
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
  font-size: 17px;
  font-weight: 600;
}
.muted {
  font-size: 12px;
  color: #65758e;
  line-height: 1.8;
}
button {
  margin: 0;
  width: 100%;
  font-size: 14px;
}
input {
  border: 1px solid #d7e0ee;
  padding: 12px;
  border-radius: 8px;
  height: 46px;
  font-size: 14px;
  min-width: 0;
}
.audit-case {
  font-size: 13px;
  line-height: 1.7;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.choice {
  border: 1px solid #d7e0ee;
  border-radius: 8px;
  padding: 8px;
  min-width: 65px;
  text-align: center;
}
.notice {
  padding: 12px;
  background: #edf4ff;
  border-radius: 8px;
  font-size: 13px;
}
.error {
  color: #b42318;
  background: #fff0ee;
}
</style>
