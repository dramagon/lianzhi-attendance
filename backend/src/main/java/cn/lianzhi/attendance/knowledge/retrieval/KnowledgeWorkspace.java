package cn.lianzhi.attendance.knowledge.retrieval;

import cn.lianzhi.attendance.knowledge.parse.*;
import cn.lianzhi.attendance.knowledge.provider.*;
import cn.lianzhi.attendance.knowledge.text.*;
import java.security.*;
import java.util.*;
import static cn.lianzhi.attendance.knowledge.retrieval.KnowledgeFailure.Code.*;

/** Ephemeral integration component, deliberately NOT a Spring bean or production repository.
 * No HTTP identity binding, original-file storage, database, durable jobs or automatic retries.
 * Actor is supplied only by a trusted server-side bridge; current application has no such bridge. */
public final class KnowledgeWorkspace {
    public enum Processing { PROCESSING, PARSED, NO_TEXT, FAILED, DELETED }
    public enum Indexing { NOT_INDEXED, READY, UNAVAILABLE, FAILED }
    public enum Mode { TEXT, VECTOR }
    public record Document(UUID id, long version, String name, DocumentAccess access, String sha256,
                           Processing processing, Indexing indexing, String message, int chunkCount) {}
    public record Page<T>(List<T> items, long total, int page, int size) {
        public Page { items = List.copyOf(items); }
    }
    public record Citation(UUID documentId, long version, TextChunk chunk) {}
    public record Hit(Citation citation, String documentName, Mode mode, double score) {}
    private record Entry(Document document, List<TextSection> sections, List<TextChunk> chunks, List<Embedding> vectors) {}
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final DocumentParser parser;
    private final TextChunker chunker;
    private final EmbeddingProvider provider;
    private final WorkspaceLimits limits;
    public KnowledgeWorkspace(DocumentParser parser, TextChunker chunker, EmbeddingProvider provider, WorkspaceLimits limits) {
        this.parser = Objects.requireNonNull(parser); this.chunker = Objects.requireNonNull(chunker);
        this.provider = Objects.requireNonNull(provider); this.limits = Objects.requireNonNull(limits);
    }
    public Document upload(KnowledgeActor actor, long companyId, DocumentAccess.Visibility visibility, String name, byte[] bytes) {
        Objects.requireNonNull(actor);
        var access = new DocumentAccess(companyId, actor.userId(), visibility);
        if (!access.manageableBy(actor)) throw fail(FORBIDDEN, "无权上传到该文档范围");
        String safeName = name(name);
        byte[] input = input(bytes);
        Document ticket;
        synchronized (this) {
            if (entries.size() >= limits.documents()) throw fail(LIMIT_EXCEEDED, "工作区文档数量超限");
            ticket = ticket(UUID.randomUUID(), 1, safeName, access, input);
            entries.put(ticket.id(), empty(ticket));
        }
        return process(ticket, input);
    }
    public Document replace(KnowledgeActor actor, UUID id, long expectedVersion,
                            DocumentAccess.Visibility visibility, String name, byte[] bytes) {
        String safeName = name(name);
        byte[] input = input(bytes);
        Document ticket;
        synchronized (this) {
            Entry old = managed(actor, id);
            version(old, expectedVersion);
            var access = new DocumentAccess(old.document.access().companyId(), old.document.access().ownerUserId(), visibility);
            if (!access.manageableBy(actor)) throw fail(FORBIDDEN, "无权更改文档范围");
            ticket = ticket(id, expectedVersion + 1, safeName, access, input);
            // Invalidate old text and vectors immediately, including if the replacement later fails.
            entries.put(id, empty(ticket));
        }
        return process(ticket, input);
    }
    public synchronized void delete(KnowledgeActor actor, UUID id, long expectedVersion) {
        Entry old = managed(actor, id);
        version(old, expectedVersion);
        Document d = old.document;
        entries.put(id, empty(new Document(id, d.version() + 1, d.name(), d.access(), d.sha256(),
                Processing.DELETED, Indexing.NOT_INDEXED, "文档已删除", 0)));
    }
    public synchronized Document get(KnowledgeActor actor, UUID id) { return readable(actor, id).document; }
    public synchronized Page<Document> list(KnowledgeActor actor, int page, int size) {
        Objects.requireNonNull(actor);
        if (page < 1 || page > 1_000_000 || size < 1 || size > 100) throw fail(INVALID_INPUT, "分页参数无效");
        List<Document> all = entries.values().stream().map(Entry::document)
                .filter(d -> d.processing() != Processing.DELETED && d.access().readableBy(actor))
                .sorted(Comparator.comparing(d -> d.id().toString())).toList();
        return new Page<>(all.stream().skip((long)(page - 1) * size).limit(size).toList(), all.size(), page, size);
    }
    public synchronized Citation cite(KnowledgeActor actor, UUID id, long version, int sequence) {
        Entry entry = readable(actor, id);
        if (entry.document.version() != version || entry.document.processing() != Processing.PARSED
                || sequence < 1 || sequence > entry.chunks.size()) throw fail(NOT_FOUND, "来源不存在或版本已失效");
        return new Citation(id, version, entry.chunks.get(sequence - 1));
    }
    public List<Hit> search(KnowledgeActor actor, String query, Mode mode, int topK, double minimumScore) {
        Objects.requireNonNull(actor); Objects.requireNonNull(mode);
        if (query == null || query.length() > 2000 || topK < 1 || topK > 50 || !Double.isFinite(minimumScore)
                || minimumScore < -1 || minimumScore > 1) throw fail(INVALID_INPUT, "检索参数无效");
        String text = TextNormalizer.normalize(query);
        if (text.isBlank() || text.codePointCount(0, text.length()) > 500) throw fail(INVALID_INPUT, "检索内容需为1～500字");
        Embedding needle = null;
        if (mode == Mode.VECTOR) {
            List<Embedding> vectors = embed(List.of(text));
            if (vectors == null) throw fail(UNAVAILABLE, "向量服务尚未配置或不可用");
            needle = vectors.get(0);
        }
        // Lock only for the local bounded scan, never over provider I/O. Current version and ACL are
        // checked here after provider returns; there is no cached stale candidate list or global top-K.
        synchronized (this) {
            List<Hit> hits = new ArrayList<>();
            for (Entry e : entries.values()) {
                Document d = e.document;
                if (d.processing() != Processing.PARSED || !d.access().readableBy(actor)) continue;
                if (mode == Mode.VECTOR && d.indexing() != Indexing.READY) continue;
                for (int i = 0; i < e.chunks.size(); i++) {
                    TextChunk chunk = e.chunks.get(i);
                    double score;
                    if (mode == Mode.TEXT) {
                        if (!chunk.content().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) continue;
                        score = 1; // Literal match, never presented as semantic relevance.
                    } else {
                        if (needle.values().size() != e.vectors.get(i).values().size())
                            throw fail(UNAVAILABLE, "向量模型维度已变化，需要重建索引");
                        score = VectorMath.cosine(needle, e.vectors.get(i));
                    }
                    if (score >= minimumScore) hits.add(new Hit(new Citation(d.id(), d.version(), chunk), d.name(), mode, score));
                }
            }
            return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()
                    .thenComparing(h -> h.citation().documentId().toString())
                    .thenComparingInt(h -> h.citation().chunk().sequence())).limit(topK).toList();
        }
    }
    private Document process(Document ticket, byte[] bytes) {
        ParseResult parsed = parser.parse(ticket.name(), bytes);
        Entry result;
        if (parsed.code() != ParseCode.SUCCESS) {
            result = empty(state(ticket, parsed.code() == ParseCode.NO_TEXT ? Processing.NO_TEXT : Processing.FAILED,
                    Indexing.NOT_INDEXED, parsed.message(), 0));
        } else {
            try {
                var sections = parsed.sections().stream().map(s -> new TextSection(s.source(), TextNormalizer.normalize(s.text()))).toList();
                var chunks = chunker.chunk(sections);
                // Prevent sending documents exceeding local workspace budgets to an external provider.
                result = new Entry(state(ticket, Processing.PARSED, Indexing.NOT_INDEXED, "解析完成", chunks.size()), sections, chunks, List.of());
                synchronized (this) { current(ticket); budget(result); }
                List<Embedding> vectors = embed(chunks.stream().map(TextChunk::content).toList());
                Indexing index = vectors == null ? Indexing.UNAVAILABLE : Indexing.READY;
                result = new Entry(state(ticket, Processing.PARSED, index,
                        vectors == null ? "解析完成，向量服务未配置或不可用" : "解析与向量索引完成", chunks.size()),
                        sections, chunks, vectors == null ? List.of() : vectors);
            } catch (KnowledgeFailure e) {
                if (e.code() == CONFLICT) throw e;
                result = empty(state(ticket, Processing.FAILED, Indexing.FAILED, "工作区处理资源超限", 0));
            } catch (IllegalArgumentException e) {
                result = empty(state(ticket, Processing.FAILED, Indexing.FAILED, "文档切片或向量数据无效", 0));
            }
        }
        synchronized (this) {
            current(ticket);
            try { budget(result); }
            catch (KnowledgeFailure e) { result = empty(state(ticket, Processing.FAILED, Indexing.FAILED, "工作区处理资源超限", 0)); }
            entries.put(ticket.id(), result);
            return result.document;
        }
    }
    /** All-or-nothing, bounded batches. Provider exceptions never leak service errors or partial data. */
    private List<Embedding> embed(List<String> texts) {
        try {
            if (provider.availability() != ProviderAvailability.AVAILABLE) return null;
            List<Embedding> all = new ArrayList<>(); int dimension = 0;
            for (int start = 0; start < texts.size(); start += 32) {
                List<String> batch = texts.subList(start, Math.min(start + 32, texts.size()));
                var response = provider.embed(List.copyOf(batch));
                if (response.state() != ProviderResult.State.SUCCESS || response.value().size() != batch.size()) return null;
                for (Embedding vector : response.value()) {
                    VectorMath.unit(vector);
                    if (dimension != 0 && vector.values().size() != dimension) return null;
                    dimension = vector.values().size();
                    if ((long)(all.size() + 1) * dimension > limits.vectorValues()) return null;
                    all.add(vector);
                }
            }
            return List.copyOf(all);
        } catch (RuntimeException e) { return null; }
    }
    private void budget(Entry candidate) {
        long chunks = candidate.chunks.size(), text = textSize(candidate), vectors = vectorSize(candidate);
        for (Entry entry : entries.values()) if (!entry.document.id().equals(candidate.document.id())) {
            chunks += entry.chunks.size(); text += textSize(entry); vectors += vectorSize(entry);
        }
        if (chunks > limits.chunks() || text > limits.textCodePoints() || vectors > limits.vectorValues())
            throw fail(LIMIT_EXCEEDED, "工作区容量超限");
    }
    private static long textSize(Entry e) { return e.sections.stream().mapToLong(s -> s.text().codePointCount(0, s.text().length())).sum(); }
    private static long vectorSize(Entry e) { return e.vectors.stream().mapToLong(v -> v.values().size()).sum(); }
    private void current(Document ticket) {
        Entry entry = entries.get(ticket.id());
        if (entry == null || entry.document.version() != ticket.version() || entry.document.processing() != Processing.PROCESSING)
            throw fail(CONFLICT, "处理结果已失效，文档已更新或删除");
    }
    private Entry readable(KnowledgeActor actor, UUID id) {
        Objects.requireNonNull(actor);
        Entry entry = entries.get(id);
        if (entry == null || entry.document.processing() == Processing.DELETED || !entry.document.access().readableBy(actor))
            throw fail(NOT_FOUND, "文档不存在或不可访问");
        return entry;
    }
    private Entry managed(KnowledgeActor actor, UUID id) {
        Entry entry = readable(actor, id);
        if (!entry.document.access().manageableBy(actor)) throw fail(FORBIDDEN, "无权维护该文档");
        return entry;
    }
    private static void version(Entry e, long expected) {
        if (expected < 1 || e.document.version() != expected || expected == Long.MAX_VALUE)
            throw fail(CONFLICT, "文档版本已变化，请刷新后重试");
    }
    private static String name(String name) {
        if (name == null || name.isBlank() || name.length() > 200 || name.codePoints().anyMatch(p -> Character.isISOControl(p) || p == '/' || p == '\\'))
            throw fail(INVALID_INPUT, "文件名无效，仅允许不含路径的展示名称");
        return name.strip();
    }
    private static byte[] input(byte[] bytes) {
        if (bytes == null || bytes.length > ParseOptions.defaults().maxBytes()) throw fail(INVALID_INPUT, "文件为空参数或超过10MiB");
        return bytes.clone();
    }
    private static Document ticket(UUID id, long version, String name, DocumentAccess access, byte[] bytes) {
        try { return new Document(id, version, name, access, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                Processing.PROCESSING, Indexing.NOT_INDEXED, "正在处理", 0); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Document state(Document d, Processing p, Indexing i, String message, int count) {
        return new Document(d.id(), d.version(), d.name(), d.access(), d.sha256(), p, i, message, count);
    }
    private static Entry empty(Document d) { return new Entry(d, List.of(), List.of(), List.of()); }
    private static KnowledgeFailure fail(KnowledgeFailure.Code code, String message) { return new KnowledgeFailure(code, message); }
}
