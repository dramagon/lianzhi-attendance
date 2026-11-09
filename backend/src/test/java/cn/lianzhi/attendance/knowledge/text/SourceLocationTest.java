package cn.lianzhi.attendance.knowledge.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SourceLocationTest {
    @Test void representsParserLocationsWithoutInventingPages() {
        assertThat(SourceLocation.file().kind()).isEqualTo(SourceLocation.Kind.FILE);
        assertThat(SourceLocation.page(3).ordinal()).isEqualTo(3);
        assertThat(SourceLocation.slide(2).ordinal()).isEqualTo(2);
        assertThat(SourceLocation.sheet("  员工表  ").name()).isEqualTo("员工表");
        assertThat(SourceLocation.section("第二章").name()).isEqualTo("第二章");
        assertThat(SourceLocation.timeRange(1000, 2500).startMillis()).isEqualTo(1000);
    }

    @Test void rejectsInvalidCoordinates() {
        assertThatIllegalArgumentException().isThrownBy(() -> SourceLocation.page(0));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceLocation.slide(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceLocation.sheet(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceLocation.section("a".repeat(257)));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceLocation.timeRange(-1, 1000));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceLocation.timeRange(1000, 1000));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceLocation.timeRange(2000, 1000));
    }

    @Test void rejectsMixedOrMissingLocationFields() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new SourceLocation(SourceLocation.Kind.PAGE, 1, "员工表", null, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new SourceLocation(SourceLocation.Kind.TIME_RANGE, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new SourceLocation(SourceLocation.Kind.FILE, null, null, 1L, 2L));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceLocation.section(null));
    }
}
