// H5-only implementation; the mini program calls its native scan/file APIs.
import jsQR from "jsqr";
export async function decodeImage(path) {
  const image = new Image();
  const canvas = document.createElement("canvas");
  try {
    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = () => reject(new Error("二维码图片无法读取"));
      image.src = path;
    });
    const scale = Math.min(1, 1600 / Math.max(image.width, image.height));
    canvas.width = Math.round(image.width * scale);
    canvas.height = Math.round(image.height * scale);
    const context = canvas.getContext("2d");
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    const data = context.getImageData(0, 0, canvas.width, canvas.height);
    const result = jsQR(data.data, data.width, data.height);
    if (!result) throw new Error("未识别到二维码，请使用清晰的考勤二维码图片");
    return result.data;
  } finally {
    canvas.width = canvas.height = 0;
    image.src = "";
  }
}
export async function readImage(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error("采集图像读取失败，请重新拍摄");
  const bytes = await response.arrayBuffer();
  if (!bytes.byteLength || bytes.byteLength > 2 * 1024 * 1024)
    throw new Error("图像须为1字节至2MB");
  return bytes;
}

export function pickImage(cameraOnly = false, signal) {
  return new Promise((resolve, reject) => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = "image/jpeg,image/png";
    input.style.display = "none";
    if (cameraOnly) input.setAttribute("capture", "user");
    let settled = false;
    function finish() {
      signal?.removeEventListener("abort", cancel);
      input.removeEventListener("change", changed);
      input.removeEventListener("cancel", cancel);
      input.value = "";
      input.remove();
    }
    function cancel() {
      if (settled) return;
      settled = true;
      finish();
      reject(new Error("已取消图像采集"));
    }
    function changed() {
      if (settled) return;
      const file = input.files?.[0];
      settled = true;
      finish();
      if (!file) reject(new Error("未取得图像，请重新拍摄"));
      else if (file.size > 2 * 1024 * 1024)
        reject(new Error("图像不能超过2MB，请重新拍摄"));
      else resolve(URL.createObjectURL(file));
    }
    if (signal?.aborted) {
      cancel();
      return;
    }
    signal?.addEventListener("abort", cancel, { once: true });
    input.addEventListener("change", changed, { once: true });
    input.addEventListener("cancel", cancel, { once: true });
    document.body.appendChild(input);
    input.click();
  });
}
export function releaseImage(path) {
  if (path?.startsWith("blob:")) URL.revokeObjectURL(path);
}
