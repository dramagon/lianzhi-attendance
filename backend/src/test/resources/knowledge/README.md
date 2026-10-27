# 知识库合成测试资料

`unicode-three-pages.pdf` 是本项目 2026-10-27 用 PyMuPDF 生成的 1764 字节测试文件，不含真实员工、公司、图片或凭据，不是用户的实训 PDF。

- 第 1 页：`合成制度样例：员工考勤规则。`
- 第 2 页：空白，用于验证后续来源页码不因跳过空白页而错位。
- 第 3 页：`第三页：请假审批完成后更新考勤。`

字体为 PDF 内置 CJK 字体引用（`china-s`），不附带本机字体文件。已用 PyMuPDF 渲染检查文字，并用 pypdf 独立检查文本及空白页；项目测试仍必须通过真实 Java PDFBox 读取它。

重建方式（仅维护夹具，不是应用运行依赖）：

```python
import pymupdf
doc = pymupdf.open()
for text in ['合成制度样例：员工考勤规则。', '', '第三页：请假审批完成后更新考勤。']:
    page = doc.new_page(width=595, height=842)
    if text:
        page.insert_text((48, 90), text, fontname='china-s', fontsize=16)
doc.set_metadata({'title': 'Synthetic knowledge parsing fixture', 'author': 'lianzhi synthetic tests'})
doc.save('unicode-three-pages.pdf', garbage=4, deflate=True, no_new_id=True)
doc.close()
```

Office 等其他样例由 Java 测试在内存中合成，不提交本机生成物。
