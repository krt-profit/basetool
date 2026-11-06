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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies the review cases a refused Wiki/UEX merge of two P4K identities opens. */
@ExtendWith(MockitoExtension.class)
class P4kMergeConflictRecorderTest {
  @Mock private P4kReviewCaseRepository reviewCases;
  @Mock private P4kImportRunRepository runs;

  @Test
  void opensOneGuidConflictPerGuidNamingTheOtherRow() {
    UUID run = UUID.randomUUID();
    GameItem retired = row(null);
    GameItem survivor = row(run);
    when(reviewCases.findByKindAndGuidAndReason(any(), any(), eq(P4kReviewReason.GUID_CONFLICT)))
        .thenReturn(Optional.empty());

    assertThat(record(retired, survivor)).isTrue();

    ArgumentCaptor<P4kReviewCase> saved = ArgumentCaptor.forClass(P4kReviewCase.class);
    verify(reviewCases, times(2)).save(saved.capture());
    List<P4kReviewCase> cases = saved.getAllValues();
    assertThat(cases)
        .allSatisfy(reviewCase -> {
          assertThat(reviewCase.getKind()).isEqualTo(P4kKind.ITEM);
          assertThat(reviewCase.getReason()).isEqualTo(P4kReviewReason.GUID_CONFLICT);
          assertThat(reviewCase.getFirstRunId()).isEqualTo(run);
          assertThat(reviewCase.getLastRunId()).isEqualTo(run);
        });
    assertThat(cases.get(0).getGuid()).isEqualTo(retired.getP4kUuid());
    assertThat(cases.get(0).getRowId()).isEqualTo(survivor.getId());
    assertThat(cases.get(1).getGuid()).isEqualTo(survivor.getP4kUuid());
    assertThat(cases.get(1).getRowId()).isEqualTo(retired.getId());
    verify(runs, never()).findFirstByOrderByAppliedAtDesc();
  }

  @Test
  void anExistingCaseKeepsItsRuns() {
    UUID firstRun = UUID.randomUUID();
    P4kReviewCase existing = new P4kReviewCase();
    existing.setFirstRunId(firstRun);
    existing.setLastRunId(firstRun);
    when(reviewCases.findByKindAndGuidAndReason(any(), any(), any()))
        .thenReturn(Optional.of(existing));

    record(row(UUID.randomUUID()), row(UUID.randomUUID()));

    assertThat(existing.getLastRunId()).isEqualTo(firstRun);
    assertThat(existing.getRowId()).isNotNull();
  }

  @Test
  void rowsWithoutARunFallBackToTheLatestRun() {
    P4kImportRun latest = new P4kImportRun();
    latest.setId(UUID.randomUUID());
    when(runs.findFirstByOrderByAppliedAtDesc()).thenReturn(Optional.of(latest));
    when(reviewCases.findByKindAndGuidAndReason(any(), any(), any())).thenReturn(Optional.empty());

    assertThat(record(row(null), row(null))).isTrue();

    ArgumentCaptor<P4kReviewCase> saved = ArgumentCaptor.forClass(P4kReviewCase.class);
    verify(reviewCases, times(2)).save(saved.capture());
    assertThat(saved.getAllValues())
        .allSatisfy(reviewCase -> assertThat(reviewCase.getFirstRunId()).isEqualTo(latest.getId()));
  }

  @Test
  void beforeTheFirstRunNoCaseIsWritten() {
    when(runs.findFirstByOrderByAppliedAtDesc()).thenReturn(Optional.empty());

    assertThat(record(row(null), row(null))).isFalse();

    verify(reviewCases, never()).save(any());
  }

  private boolean record(GameItem retired, GameItem survivor) {
    return recorder().record(retired, retired.getP4kUuid(), survivor, survivor.getP4kUuid());
  }

  private P4kMergeConflictRecorder recorder() {
    return new P4kMergeConflictRecorder(reviewCases, runs);
  }

  private static GameItem row(UUID seenRun) {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setP4kUuid(UUID.randomUUID());
    item.setP4kSeenRunId(seenRun);
    return item;
  }
}
