import { test } from "node:test";
import assert from "node:assert/strict";
import {
  acceptanceCases,
  buildAcceptanceReport,
} from "../src/lib/acceptance.js";
const device = {
  model: "自动化报告非手机",
  system: "测试系统",
  browser: "测试浏览器",
  revision: "1234567",
};
test("connected capabilities never mark unexecuted physical checks passed and extras are excluded", () => {
  const report = buildAcceptanceReport(
    { ...device, password: "secret" },
    {},
    {
      backendConnected: true,
      faceAvailable: true,
      token: "private",
      latitude: 31,
    },
    "H5",
  );
  assert.equal(report.summary.overall, "NOT_COMPLETE");
  assert.equal(report.summary.passed, 0);
  assert.equal(report.summary.notRun, acceptanceCases.length);
  assert.equal(
    report.checks.every((item) => item.result === "NOT_RUN"),
    true,
  );
  assert.equal(JSON.stringify(report).includes("secret"), false);
  assert.equal(JSON.stringify(report).includes("private"), false);
  assert.deepEqual(Object.keys(report.observations).sort(), [
    "backendConnected",
    "faceAvailable",
    "faceEnrolled",
    "locationFresh",
  ]);
});
test("manual real face-punch PASS requires event evidence, FAIL is preserved, blank device is rejected", () => {
  assert.throws(() => buildAcceptanceReport({}, {}, {}, "H5"), /填写实际设备/);
  assert.throws(
    () =>
      buildAcceptanceReport(device, { face_punch: "PASS" }, {}, "MP_WEIXIN"),
    /原始记录编号/,
  );
  const report = buildAcceptanceReport(
    { ...device, eventId: "12" },
    { face_punch: "PASS", offline_receipt: "FAIL" },
    {},
    "MP_WEIXIN",
  );
  assert.equal(report.eventId, "12");
  assert.equal(report.summary.overall, "FAIL");
  assert.equal(report.summary.failed, 1);
  assert.equal(report.summary.passed, 1);
});
