export const acceptanceCases = [
  ["https_session", "可信HTTPS、登录及会话恢复"],
  ["qr_location", "真实扫码、GPS及范围校验"],
  ["permission_deny", "摄像头/定位拒绝、超时与重试"],
  ["background", "切页/切后台取消旧操作"],
  ["offline_receipt", "断网及跨端只读回执恢复"],
  ["face_enrollment", "本人登记、更新与删除"],
  ["face_comparison", "本人/异人及照片/屏幕反例"],
  ["face_policy", "强制策略、失败/过期不回退"],
  ["face_punch", "同一笔真实人脸凭据打卡及原始记录"],
  ["records", "原始事件、日结果及审批来源一致"],
].map(([id, label]) => ({ id, label }));
export function buildAcceptanceReport(device, states, observations, platform) {
  const values = {};
  for (const key of ["model", "system", "browser"]) {
    values[key] = String(device[key] || "").trim();
    if (!values[key] || values[key].length > 120)
      throw new Error(
        "请填写实际设备型号、系统与浏览器/微信版本，每项不超过120字",
      );
  }
  values.revision = String(device.revision || "").trim();
  if (!/^[0-9a-f]{7,40}$/i.test(values.revision))
    throw new Error("请填写本次实测代码SHA（7至40位），可从构建包manifest查看");
  const checks = acceptanceCases.map((item) => ({
    ...item,
    result: ["PASS", "FAIL"].includes(states[item.id])
      ? states[item.id]
      : "NOT_RUN",
  }));
  const event = String(device.eventId || "").trim();
  if (event && !/^[1-9][0-9]{0,15}$/.test(event))
    throw new Error("原始记录编号须为正整数，不要填入令牌或坐标");
  if (
    checks.find((item) => item.id === "face_punch").result === "PASS" &&
    !event
  )
    throw new Error("标记真实人脸打卡通过时须填写对应原始记录编号，供A核对");
  const observed = {};
  for (const key of [
    "backendConnected",
    "faceAvailable",
    "faceEnrolled",
    "locationFresh",
  ])
    observed[key] = observations?.[key] === true;
  const passed = checks.filter((item) => item.result === "PASS").length,
    failed = checks.filter((item) => item.result === "FAIL").length;
  return {
    schemaVersion: 2,
    scope: "B移动端人工验收",
    evidenceType: "OPERATOR_REPORTED_NOT_SERVER_ATTESTATION",
    createdAt: new Date().toISOString(),
    platform: platform === "MP_WEIXIN" ? "MP_WEIXIN" : "H5",
    device: values,
    eventId: event || null,
    observations: observed,
    checks,
    summary: {
      passed,
      failed,
      notRun: checks.length - passed - failed,
      total: checks.length,
      overall: failed
        ? "FAIL"
        : passed === checks.length
          ? "PASS"
          : "NOT_COMPLETE",
    },
  };
}
