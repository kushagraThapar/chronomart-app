package com.chronomart.oracle;

import com.chronomart.web.dto.OpHistoryRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OfflineHistoryAnalyzer}. Each builds a small register history with explicit
 * op intervals and asserts the re-derived verdict — including the two soundness guards that keep it
 * free of false positives: concurrent (non-settled) writes never anchor a stale judgment, and a read
 * that overlaps a write in real time is allowed to see the older value.
 */
class OfflineHistoryAnalyzerTest {

    private static final String C = "Inventory";

    private OpHistoryRecord write(long g, String key, long seq, long begin, long end) {
        return new OpHistoryRecord(g, 0, "pointUpsert", C, key, begin, end, OpHistoryRecord.OUTCOME_OK, seq, null, 200, 1.0);
    }

    private OpHistoryRecord writeError(long g, String key, long seq, long begin, long end) {
        return new OpHistoryRecord(g, 0, "pointUpsert", C, key, begin, end, OpHistoryRecord.OUTCOME_ERROR, seq, null, 0, 0.0);
    }

    private OpHistoryRecord read(long g, String key, Long observed, long begin, long end) {
        return new OpHistoryRecord(g, 0, "pointRead", C, key, begin, end, OpHistoryRecord.OUTCOME_OK, null, observed, 200, 1.0);
    }

    private OpHistoryRecord readNotFound(long g, String key, long begin, long end) {
        return new OpHistoryRecord(g, 0, "pointRead", C, key, begin, end, OpHistoryRecord.OUTCOME_NOTFOUND, null, null, 404, 0.0);
    }

    @Test
    void cleanHistoryIsLinearizable() {
        var v = OfflineHistoryAnalyzer.analyze(List.of(
            write(0, "k", 1, 0, 10),
            read(1, "k", 1L, 20, 30)));
        assertThat(v.clean()).isTrue();
        assertThat(v.readsChecked()).isEqualTo(1);
        assertThat(v.writesChecked()).isEqualTo(1);
        assertThat(v.keysAnalyzed()).isEqualTo(1);
    }

    @Test
    void staleReadIsFlagged() {
        // settled writes seq1 [0,10] then seq2 [12,20]; a later read returns the older seq1.
        var v = OfflineHistoryAnalyzer.analyze(List.of(
            write(0, "k", 1, 0, 10),
            write(1, "k", 2, 12, 20),
            read(2, "k", 1L, 30, 40)));
        assertThat(v.violations()).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("STALE_READ");
            assertThat(x.observedSeq()).isEqualTo(1L);
            assertThat(x.expectedSeq()).isEqualTo(2L);
        });
    }

    @Test
    void phantomReadIsFlagged() {
        var v = OfflineHistoryAnalyzer.analyze(List.of(
            write(0, "k", 1, 0, 10),
            read(1, "k", 5L, 20, 30)));
        assertThat(v.violations()).singleElement().satisfies(x ->
            assertThat(x.code()).isEqualTo("PHANTOM_READ"));
    }

    @Test
    void readAtAllocatedSeqIsNotPhantomWhenWriteErrored() {
        var v = OfflineHistoryAnalyzer.analyze(List.of(
            write(0, "k", 1, 0, 10),
            writeError(1, "k", 2, 12, 20),
            read(2, "k", 2L, 30, 40)));
        assertThat(v.clean()).isTrue();
    }

    @Test
    void lostWriteIsFlagged() {
        var v = OfflineHistoryAnalyzer.analyze(List.of(
            write(0, "k", 1, 0, 10),
            readNotFound(1, "k", 20, 30)));
        assertThat(v.violations()).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("LOST_WRITE");
            assertThat(x.expectedSeq()).isEqualTo(1L);
        });
    }

    @Test
    void concurrentWritesDoNotAnchorStale() {
        // seq1 settled [0,10]; seq2 [12,25] and seq3 [15,30] overlap -> neither settles.
        // A read of seq2 must NOT be flagged stale against seq3 (their order is ambiguous).
        var v = OfflineHistoryAnalyzer.analyze(List.of(
            write(0, "k", 1, 0, 10),
            write(1, "k", 2, 12, 25),
            write(2, "k", 3, 15, 30),
            read(3, "k", 2L, 40, 50)));
        assertThat(v.clean()).isTrue();
    }

    @Test
    void readOverlappingTheWriteIsNotStale() {
        // A read whose interval overlaps the settled write may legitimately observe the old value.
        var v = OfflineHistoryAnalyzer.analyze(List.of(
            write(0, "k", 1, 0, 5),
            write(1, "k", 2, 0, 30),   // long write, overlaps the read
            read(2, "k", 1L, 10, 20)));
        // seq2 overlaps seq1 AND the read, so seq2 isn't settled; nothing anchors a stale verdict.
        assertThat(v.clean()).isTrue();
    }

    @Test
    void violationsAreIsolatedPerKey() {
        var v = OfflineHistoryAnalyzer.analyze(List.of(
            write(0, "k1", 1, 0, 10),
            write(1, "k1", 2, 12, 20),
            read(2, "k1", 1L, 30, 40),   // stale on k1
            write(3, "k2", 1, 0, 10),
            read(4, "k2", 1L, 20, 30))); // clean on k2
        assertThat(v.keysAnalyzed()).isEqualTo(2);
        assertThat(v.violations()).singleElement().satisfies(x ->
            assertThat(x.key()).isEqualTo("k1"));
    }
}
