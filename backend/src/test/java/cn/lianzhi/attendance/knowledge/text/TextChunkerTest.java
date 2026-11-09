package cn.lianzhi.attendance.knowledge.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TextChunkerTest {
    private static TextChunker chunker(int size, int overlap) {
        return new TextChunker(new ChunkingOptions(size, overlap, 100_000, 100, 10_000));
    }

    @Test void blankDocumentsHaveNoChunks() {
        assertThat(new TextChunker().chunk(List.of())).isEmpty();
        assertThat(new TextChunker().chunk(List.of(new TextSection(SourceLocation.file(), " \r\n\t")))).isEmpty();
    }

    @Test void preservesNormalizedOffsetsAndNeverSplitsSupplementaryCharacters() {
        TextSection section = new TextSection(SourceLocation.page(7), "\uFEFF 标题\r\n\r\n𠮷😀内容。".repeat(20));
        List<TextChunk> chunks = chunker(25, 6).chunk(List.of(section));
        assertCoverage(section, chunks, 25, 6);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.source()).isEqualTo(SourceLocation.page(7));
            assertThat(c.sectionIndex()).isEqualTo(1);
        });
    }

    @Test void prefersParagraphBoundaryBeforeSentenceBoundary() {
        List<TextChunk> chunks = chunker(12, 2).chunk(List.of(
                new TextSection(SourceLocation.file(), "甲甲甲甲甲\n\n乙乙。丙丙丙丙丙丙")));
        assertThat(chunks.get(0).content()).isEqualTo("甲甲甲甲甲\n\n");
    }

    @Test void prefersSentenceBoundaryWhenNoParagraphFits() {
        List<TextChunk> chunks = chunker(12, 2).chunk(List.of(
                new TextSection(SourceLocation.file(), "甲甲甲甲甲甲。乙乙乙乙乙乙乙乙")));
        assertThat(chunks.get(0).content()).isEqualTo("甲甲甲甲甲甲。");
    }

    @Test void longUnpunctuatedTextHasNoGapsAndExactOverlap() {
        TextSection section = new TextSection(SourceLocation.file(), "甲乙𠮷😀".repeat(200));
        assertCoverage(section, chunker(31, 7).chunk(List.of(section)), 31, 7);
    }

    @Test void highOverlapStillAdvancesAndTerminates() {
        TextSection section = new TextSection(SourceLocation.file(), "甲。乙！丙？丁；".repeat(20));
        assertCoverage(section, chunker(8, 7).chunk(List.of(section)), 8, 7);
    }

    @Test void handlesSmallSizesAndManyUnicodeBoundaryPositions() {
        TextSection section = new TextSection(SourceLocation.section("制度"), "甲😀。乙\n\n𠮷e\u0301！丙".repeat(12));
        for (int size = 1; size <= 20; size++) {
            for (int overlap = 0; overlap < size; overlap++) {
                assertCoverage(section, chunker(size, overlap).chunk(List.of(section)), size, overlap);
            }
        }
    }

    @Test void doesNotMergeSourcesOrDropOriginalSectionIndexes() {
        List<TextSection> sections = List.of(new TextSection(SourceLocation.page(1), ""),
                new TextSection(SourceLocation.page(2), "第二页"),
                new TextSection(SourceLocation.sheet("人员"), "工作表"),
                new TextSection(SourceLocation.timeRange(1000, 2000), "音频片段"));
        List<TextChunk> chunks = new TextChunker().chunk(sections);
        assertThat(chunks).extracting(TextChunk::sequence).containsExactly(1, 2, 3);
        assertThat(chunks).extracting(TextChunk::sectionIndex).containsExactly(2, 3, 4);
        assertThat(chunks).extracting(TextChunk::source).containsExactly(
                SourceLocation.page(2), SourceLocation.sheet("人员"), SourceLocation.timeRange(1000, 2000));
        assertThat(chunks).allSatisfy(c -> assertThat(c.startOffset()).isZero());
    }

    @Test void repeatedProcessingIsDeterministicAndImmutable() {
        List<TextSection> input = new ArrayList<>(List.of(new TextSection(SourceLocation.file(), "正文。".repeat(20))));
        List<TextChunk> first = chunker(12, 3).chunk(input);
        assertThat(chunker(12, 3).chunk(input)).isEqualTo(first);
        input.clear();
        assertThat(first).isNotEmpty();
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(first::clear);
    }

    @Test void exactLengthDoesNotCreateAnExtraTail() {
        assertThat(chunker(8, 3).chunk(List.of(new TextSection(SourceLocation.file(), "😀".repeat(8)))))
                .singleElement().satisfies(c -> assertThat(c.endOffset()).isEqualTo(8));
    }

    @Test void rejectsWholeDocumentBudgetOverflowIncludingWhitespace() {
        TextChunker small = new TextChunker(new ChunkingOptions(4, 1, 8, 10, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> small.chunk(List.of(
                new TextSection(SourceLocation.page(1), "甲".repeat(5)),
                new TextSection(SourceLocation.page(2), " ".repeat(4))))).withMessageContaining("正文长度");
        assertThat(small.chunk(List.of(new TextSection(SourceLocation.file(), "😀".repeat(8))))).isNotEmpty();
    }

    @Test void rejectsSectionAndOutputBudgetsWithoutReturningPartialResults() {
        TextChunker small = new TextChunker(new ChunkingOptions(4, 1, 100, 1, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> small.chunk(List.of(
                new TextSection(SourceLocation.page(1), "甲"), new TextSection(SourceLocation.page(2), "乙"))))
                .withMessageContaining("段落数量");
        assertThatIllegalArgumentException().isThrownBy(() -> small.chunk(List.of(
                new TextSection(SourceLocation.file(), "甲".repeat(20))))).withMessageContaining("切片数量");
    }

    @Test void rejectsInvalidOptionsAndNullInput() {
        assertThatIllegalArgumentException().isThrownBy(() -> chunker(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> chunker(10, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> chunker(10, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkingOptions(10, 1, 0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkingOptions(10, 1, 10, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkingOptions(10, 1, 10, 1, 0));
        assertThatNullPointerException().isThrownBy(() -> new TextChunker().chunk(null));
    }

    @Test void rejectsInconsistentChunkCoordinates() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TextChunk(1, 1, SourceLocation.file(), 0, 2, "😀"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TextChunk(0, 1, SourceLocation.file(), 0, 1, "甲"));
    }

    private static void assertCoverage(TextSection section, List<TextChunk> chunks, int size, int overlap) {
        String normalized = TextNormalizer.normalize(section.text());
        int[] points = normalized.codePoints().toArray();
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).startOffset()).isZero();
        int previousEnd = 0;
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            assertThat(chunk.sequence()).isEqualTo(i + 1);
            assertThat(chunk.endOffset() - chunk.startOffset()).isBetween(1, size);
            assertThat(chunk.content()).isEqualTo(new String(points, chunk.startOffset(), chunk.endOffset() - chunk.startOffset()));
            assertThat(TextNormalizer.normalize(chunk.content())).doesNotContain("\uFFFD");
            if (i > 0) {
                assertThat(chunk.startOffset()).isEqualTo(previousEnd - overlap);
                assertThat(chunk.endOffset()).isGreaterThan(previousEnd);
            }
            previousEnd = chunk.endOffset();
        }
        assertThat(previousEnd).isEqualTo(points.length);
    }
}
