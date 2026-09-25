/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Pins the isolation contract of {@link SyncChunkWriter} (BE-PERF-09, REQ-DATA-005): one
 * transaction per chunk on the happy path, and a failing row rolls back alone — its chunk is
 * replayed row by row and every sibling still lands.
 */
class SyncChunkWriterTest {

  private final RecordingTransactionManager tx = new RecordingTransactionManager();
  private final SyncChunkWriter writer = new SyncChunkWriter(tx);

  @Test
  void happyPath_oneTransactionPerChunk() {
    List<Integer> rows = IntStream.rangeClosed(1, 25).boxed().toList();

    SyncChunkWriter.Outcome<Integer> outcome =
        writer.write(rows, 10, chunk -> chunk, "row", String::valueOf);

    assertThat(outcome.results()).containsExactlyElementsOf(rows);
    assertThat(outcome.failedRows()).isZero();
    assertThat(tx.begun).isEqualTo(3);
    assertThat(tx.committed).isEqualTo(3);
    assertThat(tx.rolledBack).isZero();
  }

  @Test
  void aFailingRowRollsBackAlone_andItsSiblingsStillCommit() {
    List<Integer> rows = IntStream.rangeClosed(1, 6).boxed().toList();
    List<List<Integer>> attempts = new ArrayList<>();

    SyncChunkWriter.Outcome<Integer> outcome =
        writer.write(
            rows,
            3,
            chunk -> {
              attempts.add(List.copyOf(chunk));
              if (chunk.contains(5)) {
                throw new IllegalStateException("database refused row 5");
              }
              return chunk;
            },
            "row",
            String::valueOf);

    assertThat(outcome.results()).containsExactly(1, 2, 3, 4, 6);
    assertThat(outcome.failedRows()).isEqualTo(1);
    assertThat(attempts)
        .containsExactly(List.of(1, 2, 3), List.of(4, 5, 6), List.of(4), List.of(5), List.of(6));
    assertThat(tx.committed).isEqualTo(3);
    assertThat(tx.rolledBack).isEqualTo(2);
  }

  @Test
  void skippedRowsProduceNoResult() {
    SyncChunkWriter.Outcome<Integer> outcome =
        writer.write(
            List.of(1, 2, 3, 4),
            10,
            chunk -> chunk.stream().filter(i -> i % 2 == 0).toList(),
            "row",
            String::valueOf);

    assertThat(outcome.results()).containsExactly(2, 4);
    assertThat(outcome.failedRows()).isZero();
  }

  @Test
  void inNewTransaction_runsTheWorkInItsOwnTransaction() {
    assertThat(writer.inNewTransaction(() -> "done")).isEqualTo("done");
    assertThat(tx.begun).isEqualTo(1);
    assertThat(tx.committed).isEqualTo(1);
  }

  @Test
  void aChunkSizeBelowOneIsRefused() {
    assertThatThrownBy(() -> writer.write(List.of(1), 0, chunk -> chunk, "row", String::valueOf))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
