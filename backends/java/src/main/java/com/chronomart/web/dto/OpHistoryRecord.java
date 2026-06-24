package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * One entry in a verification run's operation history — the structured, replayable log the offline
 * analyzer consumes (and a human can download via {@code GET /workloads/{runId}/history}).
 *
 * <p>Only single-register ops (pointRead / pointUpsert over the owned keyspace) are recorded: they
 * are what a register-history linearizability check reasons about. {@code beginNanos}/{@code endNanos}
 * are relative to run start so intervals are comparable without absolute clocks; the
 * begin/end pair is the op's real-time interval, which is what lets the analyzer establish
 * happens-before between writes and reads.
 *
 * @param globalSeq     monotonic record index (recording order)
 * @param userIdx       virtual user that issued the op (-1 for the keyspace pre-seed)
 * @param op            {@code pointRead} or {@code pointUpsert}
 * @param container     target container
 * @param key           keyspace key (the register)
 * @param beginNanos    op start, nanos since run start
 * @param endNanos      op completion, nanos since run start
 * @param outcome       {@code ok} | {@code notfound} | {@code error}
 * @param writeSeq      for writes: the sequence written (null for reads)
 * @param observedSeq   for reads: the sequence observed (null on notfound/error)
 * @param statusCode    SDK status code
 * @param requestCharge RU consumed
 */
@JsonInclude(Include.NON_NULL)
public record OpHistoryRecord(
    long globalSeq,
    int userIdx,
    String op,
    String container,
    String key,
    long beginNanos,
    long endNanos,
    String outcome,
    Long writeSeq,
    Long observedSeq,
    int statusCode,
    double requestCharge
) {
    public static final String OUTCOME_OK = "ok";
    public static final String OUTCOME_NOTFOUND = "notfound";
    public static final String OUTCOME_ERROR = "error";
}
