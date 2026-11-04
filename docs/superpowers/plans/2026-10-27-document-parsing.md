# Document Parsing Implementation Plan

> **Execution tracking:** Follow the C team contract and the user's end-of-day single-commit requirement. Initial implementation/spec review used separate agents before the pause; recovery verifies the saved work and review findings in the current session.

**Goal:** Parse PDF and Office documents into source-aware text and reuse the existing chunker.

**Architecture:** A bounded parser facade validates the input container, dispatches to PDF/Office handlers, and returns explicit result codes with TextSection values. Chunking stays in the existing knowledge.text package. External provider interfaces express availability and failures without fake implementations.

**Tech Stack:** Java 17, existing Spring Boot 3.5.16, PDFBox 3.0.8, Apache POI 6.0.0, JUnit/AssertJ.

## Task 1: Parsing, limits and provider contracts

Files: modify `backend/pom.xml`; create focused classes in `backend/src/main/java/cn/lianzhi/attendance/knowledge/parse/` and `knowledge/provider/`; tests in corresponding `backend/src/test/java/cn/lianzhi/attendance/knowledge/` packages. The approved boundary is [the design](../specs/2026-10-27-document-parsing-design.md).

- [x] Add explicit POM properties/dependencies for PDFBox, poi-ooxml and poi-scratchpad. Keep the existing Java/Spring stack.
- [x] Write real synthetic-document tests first. The facade follows `parser.parse("policy.pdf", bytes)` and returns `format()`, `code()`, `message()` and `sections()`; choose final type names consistently and document them. Cover PDF pages, DOCX paragraphs/tables, XLS/XLSX sheets/cells, PPT/PPTX slides/text/tables, and legacy DOC unsupported status.
- [x] Run `mvnw.cmd -B -ntp "-Dtest=*ParserTest" test`; verify new behavior tests fail for missing parsing, not fixture failures. Contract scaffolding may explicitly throw UnsupportedOperationException until implemented, never commit the scaffold as complete.
- [x] Implement signature/container validation and bounded PDF/Office extraction. Catch expected parse failures into safe result codes. Do not globally relax ZIP checks or evaluate formulas/macros.
- [x] Add and run boundary tests for empty, malformed, encrypted, renamed files, file byte limits, output text/source/cell/page budgets and ZIP expansion. Result failures must contain no partial正文. Use constants/options for the budgets.
- [x] Exercise the existing pipeline with `new TextChunker().chunk(result.sections())`; verify preserved sources and every offset against `TextNormalizer.normalize(section.text())`.
- [x] Define OCR/transcription/embedding interfaces and availability/result contracts, test their invariants. No HTTP client, fake vectors, OCR text or configured-service claims.
- [x] Run targeted tests to green, self-review the change and report test commands/results without committing. Controller coordinates spec and quality reviews before sign-off.

## Task 2: Evidence, regression and daily delivery

Files: update `docs/modules/c-api.md`, `docs/modules/c-knowledge-design.md`; create `docs/verification/member-c-day2.md`; complete plan checkboxes with actual evidence. Controller owns these docs and final Git actions.

- [x] Record exact supported formats, source mapping, limits, failure codes and provider contract status. Include official dependency sources and pending V6/A/B integration.
- [x] Review the independent pre-pause spec findings, reproduce/fix the macro gap, then review code quality. Record the recovery review and fresh verification evidence.
- [x] Run full `mvnw.cmd -B -ntp test` (all old tests retained), `npm.cmd run build`, plus backend package/startup smoke. Run existing browser regression if authentication/navigation changes; do not change those merely to expose this internal component.
- [x] Run `git diff --cached --check`, check document relative links and confirm changed files contain no employee data, credentials or generated database files.
Daily delivery after the checklist: create one commit on C, update existing PR #1, and verify the remote SHA. This final publication action is evidenced by the PR and delivery report rather than a self-referential commit SHA in this file. No merge to main; A controls final merge. Report local/CI results separately.
