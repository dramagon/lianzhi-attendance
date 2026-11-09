# 文档解析与来源定位

## Java 入口与业务边界

`knowledge.parse.DocumentParser` 是内部内存组件，不是上传接口。调用示例：

```java
ParseResult result = new DocumentParser().parse("policy.pdf", bytes);
if (result.code() == ParseCode.SUCCESS) {
    List<TextChunk> chunks = new TextChunker().chunk(result.sections());
}
```

`new DocumentParser(ParseOptions)` 接受每次解析的资源上限。`ParseResult` 包含 format/code/message/sections；正文列表不可变，所有失败（含 NO_TEXT）均无部分正文。文件名只用于类型校验，不作为磁盘路径使用；不读取服务器上同名文件。

结果类型：`DocumentFormat` 包含 UNKNOWN/PDF/DOCX/XLSX/PPTX/XLS/PPT/DOC，其中 DOC 仅用于表达类型，不支持解析。

| ParseCode | 含义 |
| --- | --- |
| SUCCESS | 提取到正文，可交给现有切片器 |
| INVALID_INPUT | 文件名为空/空白或 byte[] 为 null |
| EMPTY_INPUT | byte[] 长度为 0 |
| UNSUPPORTED | 未接入格式，包括 DOC、宏扩展名及图片/音视频 |
| FORMAT_MISMATCH | 扩展名与检测出的真实容器/主内容不一致 |
| MALFORMED | 文件损坏、结构无效或库无法正常解析 |
| ENCRYPTED | 加密 PDF 或 Office，不尝试猜测密码 |
| LIMIT_EXCEEDED | 输入、解压或提取预算超限 |
| NO_TEXT | 无可提取正文；扫描 PDF 等情况可能需要 OCR，不能算识别成功 |

`message` 为固定中文提示，不包含底层异常、路径、文件正文或凭据。结果码是本组件契约，尚未映射为业务 HTTP 状态。

## 格式与来源

| 格式 | 提取边界与定位 |
| --- | --- |
| PDF | 可提取文本及真实 PAGE 来源；空白页不改变后续页号。扫描图像不进行 OCR。 |
| DOCX | 正文段落和表格，按内容顺序以 SECTION 标明位置；不通过字符数猜测页码。 |
| XLS/XLSX | 工作表中的单元格，SHEET 来源并在正文保留行/列信息；使用缓存公式值或公式说明，不执行公式、外部引用或宏。 |
| PPT/PPTX | 幻灯片文本框及表格，SLIDE 从 1 开始；不将图片、动画或讲解音频算作已解析文本。 |
| DOC/宏文档/图片/音视频 | 明确未支持；DOCX 成功不代表 DOC 成功，H5 页面或适配接口也不代表 OCR/转写已接通。 |

提取范围包括文件中的物理工作表/幻灯片（含隐藏项）；不提取 Word 页眉页脚、演讲备注或嵌入对象，不将图表图片自动转成数据。XLS 主要覆盖合成验证的 BIFF8 文件，不能据此宣称所有历史 Office 版本和子格式均支持。公式缓存来自原文件，可能过期，解析器不保证其为重新计算后的结果。

来源结构继续沿用 `knowledge.text.SourceLocation`、`TextSection`，切片位置仍是规范化正文的码点偏移，不是原文件字节偏移。引用必须与文档版本和来源段落绑定。

## 默认限制

`ParseOptions` 的正数参数按文档生效，不修改全局上传或 ZIP 安全设置：

| 参数 | 默认值 |
| --- | --- |
| maxBytes | 10MiB |
| maxCodePoints | 2,000,000 |
| maxSections | 10,000 |
| maxPages | 1,000 |
| maxCells | 200,000 |
| maxZipEntries | 2,000 |
| maxZipEntryBytes | 16MiB |
| maxZipTotalBytes | 64MiB |

OOXML 加载前进行容器/条目检查，提取过程中执行正文和来源限额；保留 POI 自身的 ZIP 保护。没有通过设置 inflate ratio 为 0 等方式关闭全局保护。

宏识别覆盖 VBA 存储/PPT 宏标记、OOXML macroEnabled/vbaProject/macroSheet 内容类型，并在 XLS 工作簿中分别检查 BoundSheet8 的类型和 BOF 子流类型。旧 XLS 的字段依据 [Microsoft BoundSheet8 规范](https://learn.microsoft.com/en-us/openspecs/office_file_formats/ms-xls/b9ec509a-235d-424e-871d-f8e721106501)；即使两处元数据只有一处声明宏表，也拒绝并返回空正文。

这些限额不能保证任意恶意文件的 CPU/堆内存消耗均有硬上限。当前组件不直接向外开放；上传服务需结合进程隔离、超时、并发和存储控制使用。官方说明：[PDFBox 安全模型](https://pdfbox.apache.org/security.html)、[POI 安全处理](https://poi.apache.org/security.html)。

## 依赖变更

本次在 POM 显式增加 PDFBox 3.0.8、poi-ooxml/poi-scratchpad 6.0.0。版本已核对 [PDFBox 官方发布页](https://pdfbox.apache.org/download.cgi)、[POI 官方发布页](https://poi.apache.org/download.html)及 Maven Central；[POI 6.0.0 变更记录](https://poi.apache.org/changes.html)明确最低 Java 17，与项目目标一致。PDFBox 3 使用 Loader，依据 [官方迁移说明](https://pdfbox.apache.org/3.0/migration.html)。

解析库的必要日志/工具类版本对齐与最终有效依赖树在当天验证记录中单列；不替换 Spring Boot、MyBatis、前端栈或身份认证。PDFBox 的 commons-logging 由现有 Spring JCL 提供，避免重复日志发现。

## 未接入能力

OCR、音视频转写和 embedding 仅定义可替换 Java 服务契约及明确的可用性/失败结果，不调用网络，不返回固定 OCR 文字或伪向量。当前没有文档上传、持久化、权限检索、向量索引或 AI 回答。

提供方契约位于 `knowledge.provider`：`OcrProvider.recognize(fileName, imageBytes)`、`TranscriptionProvider.transcribe(fileName, mediaBytes)`、`EmbeddingProvider.embed(texts)`，均另有 `availability()`。

- `ProviderAvailability`：AVAILABLE / NOT_CONFIGURED / UNAVAILABLE，仅描述配置/健康，不代表请求成功。
- `ProviderResult<T>`：SUCCESS 必须有输出；UNAVAILABLE / FAILURE 不得包含部分值，message 非空。列表等深层不可变性由实现方遵守接口契约。
- `Embedding`：保存不可变的非空有限 Double 列表；契约要求实现方返回与输入顺序一一对应且维度一致的向量，不提供任何伪造实现。
- OCR 输出使用真实来源；转写来源使用实际 TIME_RANGE。以上只有接口和结果约束，没有配置完成任何外部服务。
