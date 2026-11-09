package cn.lianzhi.attendance.knowledge.retrieval;

import cn.lianzhi.attendance.knowledge.parse.*;
import cn.lianzhi.attendance.knowledge.provider.*;
import cn.lianzhi.attendance.knowledge.text.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static cn.lianzhi.attendance.knowledge.retrieval.DocumentAccess.Visibility.*;
import static cn.lianzhi.attendance.knowledge.retrieval.KnowledgeWorkspace.Mode.*;

class KnowledgeWorkspaceTest {
    final KnowledgeActor admin = new KnowledgeActor(1, KnowledgeActor.Role.ADMIN, null);
    final KnowledgeActor hr = new KnowledgeActor(2, KnowledgeActor.Role.HR, 10L);
    final KnowledgeActor employee = new KnowledgeActor(3, KnowledgeActor.Role.EMPLOYEE, 10L);
    final KnowledgeActor colleague = new KnowledgeActor(4, KnowledgeActor.Role.EMPLOYEE, 10L);
    final KnowledgeActor outsider = new KnowledgeActor(5, KnowledgeActor.Role.HR, 20L);
    final EmbeddingProvider missing = new OllamaEmbeddingProvider("", "");
    KnowledgeWorkspace workspace(EmbeddingProvider provider) {
        return new KnowledgeWorkspace(new DocumentParser(), new TextChunker(), provider, WorkspaceLimits.defaults());
    }
    byte[] doc(String text) throws Exception {
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text); doc.write(bytes); return bytes.toByteArray();
        }
    }
    KnowledgeWorkspace.Document upload(KnowledgeWorkspace w, KnowledgeActor actor, DocumentAccess.Visibility visibility, String text) throws Exception {
        return w.upload(actor, actor.companyId() == null ? 10L : actor.companyId(), visibility, "合成制度.docx", doc(text));
    }
    void error(Runnable operation, KnowledgeFailure.Code code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    @Test void realDocxParsesSearchesAndCitesUnicodeOffsets() throws Exception {
        var w = workspace(missing); var d = upload(w, employee, PRIVATE, "员工制度 😀 请假须审批。");
        assertThat(d.processing()).isEqualTo(KnowledgeWorkspace.Processing.PARSED);
        assertThat(d.indexing()).isEqualTo(KnowledgeWorkspace.Indexing.UNAVAILABLE);
        assertThat(d.sha256()).matches("[a-f0-9]{64}");
        var hit = w.search(employee, "请假", TEXT, 5, 0).get(0);
        var cited = w.cite(employee, d.id(), 1, hit.citation().chunk().sequence());
        assertThat(cited).isEqualTo(hit.citation());
        assertThat(cited.chunk().source().kind()).isEqualTo(SourceLocation.Kind.SECTION);
        assertThat(cited.chunk().endOffset()).isEqualTo(cited.chunk().content().codePointCount(0, cited.chunk().content().length()));
        assertThat(hit.mode()).isEqualTo(TEXT);
    }
    @Test void realPdfKeepsPhysicalPageIncludingBlankPage() throws Exception {
        var w = workspace(missing);
        byte[] pdf;
        try (var in = getClass().getResourceAsStream("/knowledge/unicode-three-pages.pdf")) { pdf = in.readAllBytes(); }
        var d = w.upload(hr, 10, COMPANY, "制度.pdf", pdf);
        var hit = w.search(employee, "第三页", TEXT, 5, 0).get(0);
        assertThat(hit.citation().chunk().source()).isEqualTo(SourceLocation.page(3));
        assertThat(w.cite(employee, d.id(), 1, hit.citation().chunk().sequence())).isEqualTo(hit.citation());
    }
    @Test void companyAndPrivateAclApplyToListSearchGetAndCitation() throws Exception {
        var w = workspace(missing);
        var shared = upload(w, hr, COMPANY, "制度共享");
        var own = upload(w, employee, PRIVATE, "制度私有");
        var foreign = upload(w, outsider, COMPANY, "制度外部");
        assertThat(w.list(employee, 1, 20).total()).isEqualTo(2);
        assertThat(w.list(colleague, 1, 20).items()).extracting(KnowledgeWorkspace.Document::id).containsExactly(shared.id());
        assertThat(w.search(colleague, "制度", TEXT, 20, 0)).hasSize(1);
        error(() -> w.get(colleague, own.id()), KnowledgeFailure.Code.NOT_FOUND);
        error(() -> w.cite(colleague, own.id(), 1, 1), KnowledgeFailure.Code.NOT_FOUND);
        error(() -> w.get(employee, foreign.id()), KnowledgeFailure.Code.NOT_FOUND);
        error(() -> w.get(employee, UUID.randomUUID()), KnowledgeFailure.Code.NOT_FOUND);
        assertThat(w.list(hr, 1, 20).total()).isEqualTo(2);
        assertThat(w.list(admin, 1, 20).total()).isEqualTo(3);
    }
    @Test void noCompanyAndChangedCompanyFailClosed() throws Exception {
        var w = workspace(missing); var d = upload(w, employee, PRIVATE, "制度");
        var moved = new KnowledgeActor(employee.userId(), KnowledgeActor.Role.EMPLOYEE, 20L);
        var unassigned = new KnowledgeActor(employee.userId(), KnowledgeActor.Role.EMPLOYEE, null);
        for (var actor : List.of(moved, unassigned)) {
            assertThat(w.search(actor, "制度", TEXT, 1, 0)).isEmpty();
            error(() -> w.get(actor, d.id()), KnowledgeFailure.Code.NOT_FOUND);
        }
    }
    @Test void employeesCannotPublishCompanyPolicyOrWriteAcrossCompanies() throws Exception {
        var w = workspace(missing); byte[] bytes = doc("制度");
        error(() -> w.upload(employee, 10, COMPANY, "a.docx", bytes), KnowledgeFailure.Code.FORBIDDEN);
        error(() -> w.upload(employee, 20, PRIVATE, "a.docx", bytes), KnowledgeFailure.Code.FORBIDDEN);
        var d = upload(w, hr, COMPANY, "制度");
        error(() -> w.delete(employee, d.id(), 1), KnowledgeFailure.Code.FORBIDDEN);
        error(() -> w.replace(employee, d.id(), 1, PRIVATE, "a.docx", bytes), KnowledgeFailure.Code.FORBIDDEN);
    }
    @Test void versionUpdateInvalidatesOldSourceAndOldWords() throws Exception {
        var w = workspace(missing); var d = upload(w, employee, PRIVATE, "旧的补卡规则");
        var updated = w.replace(employee, d.id(), 1, PRIVATE, "新制度.docx", doc("新的请假规则"));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(w.search(employee, "补卡", TEXT, 5, 0)).isEmpty();
        assertThat(w.search(employee, "请假", TEXT, 5, 0)).hasSize(1);
        error(() -> w.cite(employee, d.id(), 1, 1), KnowledgeFailure.Code.NOT_FOUND);
        error(() -> w.delete(employee, d.id(), 1), KnowledgeFailure.Code.CONFLICT);
    }
    @Test void failedReplacementNeverRestoresOldIndex() throws Exception {
        var w = workspace(missing); var d = upload(w, employee, PRIVATE, "旧内容");
        var failed = w.replace(employee, d.id(), 1, PRIVATE, "坏文件.docx", new byte[]{1,2,3});
        assertThat(failed.processing()).isEqualTo(KnowledgeWorkspace.Processing.FAILED);
        assertThat(w.search(employee, "旧内容", TEXT, 5, 0)).isEmpty();
        error(() -> w.cite(employee, d.id(), 1, 1), KnowledgeFailure.Code.NOT_FOUND);
        error(() -> w.cite(employee, d.id(), 2, 1), KnowledgeFailure.Code.NOT_FOUND);
    }
    @Test void deleteRemovesTextVectorsMetadataAndSourceAccess() throws Exception {
        var w = workspace(new FixtureVectors()); var d = upload(w, employee, PRIVATE, "制度");
        w.delete(employee, d.id(), 1);
        assertThat(w.search(employee, "制度", TEXT, 2, 0)).isEmpty();
        assertThat(w.search(employee, "制度", VECTOR, 2, -1)).isEmpty();
        assertThat(w.list(employee, 1, 20).total()).isZero();
        error(() -> w.get(employee, d.id()), KnowledgeFailure.Code.NOT_FOUND);
        error(() -> w.cite(employee, d.id(), 1, 1), KnowledgeFailure.Code.NOT_FOUND);
        error(() -> w.delete(employee, d.id(), 1), KnowledgeFailure.Code.NOT_FOUND);
    }
    @Test void reducingVisibilityRevokesOldReaders() throws Exception {
        var w = workspace(missing); var d = upload(w, hr, COMPANY, "共享制度");
        assertThat(w.search(colleague, "制度", TEXT, 5, 0)).hasSize(1);
        w.replace(hr, d.id(), 1, PRIVATE, "制度.docx", doc("私有制度"));
        assertThat(w.search(colleague, "制度", TEXT, 5, 0)).isEmpty();
        error(() -> w.cite(colleague, d.id(), 2, 1), KnowledgeFailure.Code.NOT_FOUND);
    }
    @Test void vectorTopKFiltersAclBeforeRankingAndUsesCosine() throws Exception {
        var w = workspace(new FixtureVectors());
        upload(w, outsider, COMPANY, "完美命中");
        var allowed = upload(w, hr, COMPANY, "可访问次优");
        var hits = w.search(employee, "完美命中", VECTOR, 1, 0);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).citation().documentId()).isEqualTo(allowed.id());
        assertThat(hits.get(0).score()).isCloseTo(0.8, within(1e-12));
        assertThat(w.search(employee, "完美命中", VECTOR, 1, .9)).isEmpty();
    }
    @Test void vectorReplacementRemovesOldVectorAndCitation() throws Exception {
        var w = workspace(new FixtureVectors()); var d = upload(w, hr, COMPANY, "完美命中");
        w.replace(hr, d.id(), 1, COMPANY, "制度.docx", doc("可访问次优"));
        assertThat(w.search(employee, "完美命中", VECTOR, 5, .9)).isEmpty();
        assertThat(w.search(employee, "完美命中", VECTOR, 5, .7).get(0).citation().version()).isEqualTo(2);
    }
    @Test void missingOrBrokenProviderNeverReturnsFakeVectors() throws Exception {
        var w = workspace(missing); upload(w, employee, PRIVATE, "制度");
        error(() -> w.search(employee, "制度", VECTOR, 5, 0), KnowledgeFailure.Code.UNAVAILABLE);
        EmbeddingProvider broken = new FixtureVectors() {
            @Override public ProviderResult<List<Embedding>> embed(List<String> texts) { throw new IllegalStateException("SECRET"); }
        };
        var bad = workspace(broken); var d = upload(bad, employee, PRIVATE, "制度");
        assertThat(d.indexing()).isEqualTo(KnowledgeWorkspace.Indexing.UNAVAILABLE);
        assertThat(d.message()).doesNotContain("SECRET");
        assertThat(bad.search(employee, "制度", TEXT, 1, 0)).hasSize(1);
    }
    @Test void providerPartialOutputCannotPublishPartialIndex() throws Exception {
        EmbeddingProvider partial = new FixtureVectors() {
            @Override public ProviderResult<List<Embedding>> embed(List<String> texts) {
                return new ProviderResult<>(ProviderResult.State.SUCCESS, "fixture only", List.of());
            }
        };
        var w = workspace(partial); var d = upload(w, employee, PRIVATE, "制度");
        assertThat(d.indexing()).isEqualTo(KnowledgeWorkspace.Indexing.UNAVAILABLE);
        error(() -> w.search(employee, "制度", VECTOR, 5, 0), KnowledgeFailure.Code.UNAVAILABLE);
    }
    @Test void deletedWhileProviderIsRunningCannotBeResurrected() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        EmbeddingProvider blocked = new FixtureVectors() {
            @Override public ProviderResult<List<Embedding>> embed(List<String> texts) {
                entered.countDown();
                try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return super.embed(texts);
            }
        };
        var w = workspace(blocked); var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> upload(w, employee, PRIVATE, "制度"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var processing = w.list(employee, 1, 20).items().get(0);
            assertThat(processing.processing()).isEqualTo(KnowledgeWorkspace.Processing.PROCESSING);
            w.delete(employee, processing.id(), 1); finish.countDown();
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(KnowledgeFailure.class);
            assertThat(w.list(employee, 1, 20).total()).isZero();
        } finally { finish.countDown(); executor.shutdownNow(); }
    }
    @Test void laterUpdateWinsAgainstInFlightOldProcessing() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1); var first = new AtomicBoolean(true);
        EmbeddingProvider blocked = new FixtureVectors() {
            @Override public ProviderResult<List<Embedding>> embed(List<String> texts) {
                if (first.getAndSet(false)) {
                    entered.countDown();
                    try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                return super.embed(texts);
            }
        };
        var w = workspace(blocked); var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> upload(w, employee, PRIVATE, "旧内容"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var d = w.list(employee, 1, 20).items().get(0);
            w.replace(employee, d.id(), 1, PRIVATE, "制度.docx", doc("新内容"));
            finish.countDown();
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(KnowledgeFailure.class);
            assertThat(w.search(employee, "旧内容", TEXT, 1, 0)).isEmpty();
            assertThat(w.search(employee, "新内容", TEXT, 1, 0)).hasSize(1);
        } finally { finish.countDown(); executor.shutdownNow(); }
    }
    @Test void budgetsAndPaginationAndInputValidation() throws Exception {
        var w = new KnowledgeWorkspace(new DocumentParser(), new TextChunker(), missing, new WorkspaceLimits(1, 1, 5, 5));
        var d = upload(w, employee, PRIVATE, "正文超过五个字的上限");
        assertThat(d.processing()).isEqualTo(KnowledgeWorkspace.Processing.FAILED);
        byte[] bytes = doc("新文档");
        error(() -> w.upload(employee, 10, PRIVATE, "a.docx", bytes), KnowledgeFailure.Code.LIMIT_EXCEEDED);
        error(() -> w.list(employee, 0, 20), KnowledgeFailure.Code.INVALID_INPUT);
        error(() -> w.list(employee, 1, 101), KnowledgeFailure.Code.INVALID_INPUT);
        error(() -> w.upload(employee, 10, PRIVATE, "../a.docx", bytes), KnowledgeFailure.Code.INVALID_INPUT);
        error(() -> w.search(employee, " ", TEXT, 1, 0), KnowledgeFailure.Code.INVALID_INPUT);
        error(() -> w.search(employee, "x", TEXT, 51, 0), KnowledgeFailure.Code.INVALID_INPUT);
        error(() -> w.search(employee, "x", VECTOR, 1, Double.NaN), KnowledgeFailure.Code.INVALID_INPUT);
        assertThat(w.list(employee, 2, 1).items()).isEmpty();
        assertThat(w.list(employee, 2, 1).total()).isEqualTo(1);
    }
    @Test void emptyAndNoTextAreExplicitFailureStates() throws Exception {
        var w = workspace(missing);
        var empty = w.upload(employee, 10, PRIVATE, "a.docx", new byte[0]);
        assertThat(empty.processing()).isEqualTo(KnowledgeWorkspace.Processing.FAILED);
        var blank = upload(w, employee, PRIVATE, "   ");
        assertThat(blank.processing()).isEqualTo(KnowledgeWorkspace.Processing.NO_TEXT);
        assertThat(blank.chunkCount()).isZero();
    }
    @Test void queryDoesNotReturnDeletedCandidatesAfterSlowEmbedding() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1); var blockQuery = new AtomicBoolean(false);
        EmbeddingProvider blocked = new FixtureVectors() {
            @Override public ProviderResult<List<Embedding>> embed(List<String> texts) {
                if (blockQuery.get()) {
                    entered.countDown();
                    try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                return super.embed(texts);
            }
        };
        var w = workspace(blocked); var d = upload(w, hr, COMPANY, "制度"); blockQuery.set(true);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> w.search(employee, "制度", VECTOR, 1, 0));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            w.delete(hr, d.id(), 1); finish.countDown();
            assertThat(pending.get(5, TimeUnit.SECONDS)).isEmpty();
        } finally { finish.countDown(); executor.shutdownNow(); }
    }
    @Test void modelDimensionDriftFailsExplicitlyRatherThanReturningMisleadingScores() throws Exception {
        var changed = new AtomicBoolean(false);
        EmbeddingProvider drift = new FixtureVectors() {
            @Override public ProviderResult<List<Embedding>> embed(List<String> texts) {
                if (!changed.get()) return super.embed(texts);
                return new ProviderResult<>(ProviderResult.State.SUCCESS, "synthetic changed model",
                        texts.stream().map(t -> new Embedding(List.of(1d, 0d, 0d))).toList());
            }
        };
        var w = workspace(drift); upload(w, hr, COMPANY, "制度"); changed.set(true);
        error(() -> w.search(employee, "制度", VECTOR, 1, 0), KnowledgeFailure.Code.UNAVAILABLE);
    }
    @Test void secondBatchFailureDiscardsAllPriorVectorsButKeepsParsedText() throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        EmbeddingProvider partialBatch = new FixtureVectors() {
            @Override public ProviderResult<List<Embedding>> embed(List<String> texts) {
                if (count.incrementAndGet() > 1) return new ProviderResult<>(ProviderResult.State.FAILURE, "synthetic failure", null);
                return super.embed(texts);
            }
        };
        // Avoid a highly compressible OOXML fixture triggering POI's ZIP-bomb guard before
        // reaching the embedding batches. Deterministic synthetic prose, no private documents.
        var random = new Random(20261028); var text = new StringBuilder("制度");
        for (int i = 0; i < 40_000; i++) text.append((char)('a' + random.nextInt(26)));
        var w = workspace(partialBatch); var d = upload(w, hr, COMPANY, text.toString());
        assertThat(d.processing()).isEqualTo(KnowledgeWorkspace.Processing.PARSED);
        assertThat(count).hasValue(2);
        assertThat(d.chunkCount()).isGreaterThan(32);
        assertThat(d.processing()).isEqualTo(KnowledgeWorkspace.Processing.PARSED);
        assertThat(d.indexing()).isEqualTo(KnowledgeWorkspace.Indexing.UNAVAILABLE);
        assertThat(w.search(employee, "制度", TEXT, 1, 0)).hasSize(1);
        error(() -> w.search(employee, "制度", VECTOR, 1, 0), KnowledgeFailure.Code.UNAVAILABLE);
    }
    /** Deliberately synthetic unit fixture, NEVER installed as a production provider. */
    static class FixtureVectors implements EmbeddingProvider {
        @Override public ProviderAvailability availability() { return ProviderAvailability.AVAILABLE; }
        @Override public ProviderResult<List<Embedding>> embed(List<String> texts) {
            return new ProviderResult<>(ProviderResult.State.SUCCESS, "synthetic unit fixture", texts.stream()
                    .map(t -> new Embedding(t.contains("次优") ? List.of(.8, .6) : List.of(1d, 0d))).toList());
        }
    }
}
