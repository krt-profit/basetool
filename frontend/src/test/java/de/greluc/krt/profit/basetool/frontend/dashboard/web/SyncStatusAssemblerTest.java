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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.client.SyncReportBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportJobDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncRunSummaryDto;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.SyncSource;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.SyncStatusRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests of {@link SyncStatusAssembler}: the run counts and last run per source, and the import
 * job's timestamp, status and range test (REQ-UI-034).
 */
@ExtendWith(MockitoExtension.class)
class SyncStatusAssemblerTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

  @Mock private CatalogueBackendClient catalogue;
  @Mock private SyncReportBackendClient syncReports;

  private SyncStatusAssembler assembler;

  @BeforeEach
  void setUp() {
    assembler = new SyncStatusAssembler(catalogue, syncReports);
  }

  private static P4kImportJobDto job(String status, String createdAt, String finishedAt) {
    return new P4kImportJobDto(
        null,
        null,
        status,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        createdAt,
        null,
        finishedAt);
  }

  private static List<SyncRunSummaryDto> summaries(long uex, Instant uexLast, long p4k) {
    return List.of(
        new SyncRunSummaryDto("UEX", uex, uexLast),
        new SyncRunSummaryDto("SCWIKI", 0, null),
        new SyncRunSummaryDto("P4K", p4k, null));
  }

  private void noRuns() {
    when(syncReports.syncRunSummaries(7)).thenReturn(summaries(0, null, 0));
    when(catalogue.p4kImportJobs()).thenReturn(List.of());
  }

  @Test
  void listsUexScWikiAndP4kInThatOrder() {
    noRuns();

    assertThat(assembler.rows(7, NOW))
        .extracting(SyncStatusRow::source)
        .containsExactly(SyncSource.UEX, SyncSource.SCWIKI, SyncSource.P4K);
  }

  @Test
  void aSourceThatNeverRanHasNoTimeNoStatusNoRunsAndIsOutOfRange() {
    noRuns();

    assertThat(assembler.rows(7, NOW))
        .allSatisfy(row -> {
          assertThat(row.lastRunAt()).isNull();
          assertThat(row.status()).isNull();
          assertThat(row.inRange()).isFalse();
          assertThat(row.runs()).isZero();
        });
  }

  @Test
  void aSyncRowCarriesTheRunsOfTheRangeAndIsInRangeWhenItHasAny() {
    Instant last = NOW.minusSeconds(3600);
    when(syncReports.syncRunSummaries(1)).thenReturn(summaries(4, last, 0));
    when(catalogue.p4kImportJobs()).thenReturn(List.of());

    SyncStatusRow uex = assembler.rows(1, NOW).getFirst();

    assertThat(uex.runs()).isEqualTo(4);
    assertThat(uex.lastRunAt()).isEqualTo(last);
    assertThat(uex.status()).isNull();
    assertThat(uex.inRange()).isTrue();
  }

  @Test
  void partialSync_preservesItsOutcomeAndTheOlderCompleteApplyTime() {
    Instant complete = NOW.minusSeconds(86_400);
    when(syncReports.syncRunSummaries(7))
        .thenReturn(
            List.of(
                new SyncRunSummaryDto(
                    "UEX", 1, NOW.minusSeconds(60), complete, "PARTIAL", List.of())));
    when(catalogue.p4kImportJobs()).thenReturn(List.of());

    SyncStatusRow row = assembler.rows(7, NOW).getFirst();

    assertThat(row.status()).isEqualTo("PARTIAL");
    assertThat(row.lastCompleteAt()).isEqualTo(complete);
    assertThat(row.lastRunAt()).isEqualTo(NOW.minusSeconds(60));
  }

  @Test
  void aSyncWhoseLastRunLiesOutsideTheRangeKeepsItsTimeAndIsOutOfRange() {
    Instant old = NOW.minusSeconds(20 * 86_400L);
    when(syncReports.syncRunSummaries(7)).thenReturn(summaries(0, old, 0));
    when(catalogue.p4kImportJobs()).thenReturn(List.of());

    SyncStatusRow uex = assembler.rows(7, NOW).getFirst();

    assertThat(uex.lastRunAt()).isEqualTo(old);
    assertThat(uex.runs()).isZero();
    assertThat(uex.inRange()).isFalse();
  }

  @Test
  void anAbsentSummaryListCountsAsNoRun() {
    when(syncReports.syncRunSummaries(7)).thenReturn(null);
    when(catalogue.p4kImportJobs()).thenReturn(List.of());

    SyncStatusRow uex = assembler.rows(7, NOW).getFirst();

    assertThat(uex.lastRunAt()).isNull();
    assertThat(uex.runs()).isZero();
  }

  @Test
  void theImportRowUsesTheNewestJobFinishTimeItsStatusAndTheRunCount() {
    when(syncReports.syncRunSummaries(7)).thenReturn(summaries(0, null, 2));
    when(catalogue.p4kImportJobs())
        .thenReturn(
            List.of(
                job("SUCCEEDED", "2026-11-02T09:00:00Z", "2026-11-02T10:00:00Z"),
                job("FAILED", "2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z")));

    SyncStatusRow p4k = assembler.rows(7, NOW).getLast();

    assertThat(p4k.lastRunAt()).isEqualTo(Instant.parse("2026-11-02T10:00:00Z"));
    assertThat(p4k.status()).isEqualTo("SUCCEEDED");
    assertThat(p4k.inRange()).isTrue();
    assertThat(p4k.runs()).isEqualTo(2);
  }

  @Test
  void anImportJobExactlyAtTheStartOfTheRangeIsInRange() {
    when(syncReports.syncRunSummaries(7)).thenReturn(summaries(0, null, 0));
    when(catalogue.p4kImportJobs())
        .thenReturn(List.of(job("SUCCEEDED", "2026-10-26T11:00:00Z", "2026-10-26T12:00:00Z")));

    assertThat(assembler.rows(7, NOW).getLast().inRange()).isTrue();
  }

  @Test
  void anUnfinishedImportFallsBackToItsCreationTime() {
    when(syncReports.syncRunSummaries(7)).thenReturn(summaries(0, null, 0));
    when(catalogue.p4kImportJobs())
        .thenReturn(List.of(job("RUNNING", "2026-11-02T11:00:00Z", null)));

    SyncStatusRow p4k = assembler.rows(7, NOW).getLast();

    assertThat(p4k.lastRunAt()).isEqualTo(Instant.parse("2026-11-02T11:00:00Z"));
    assertThat(p4k.status()).isEqualTo("RUNNING");
  }

  @Test
  void anAbsentJobListCountsAsNoImport() {
    when(syncReports.syncRunSummaries(7)).thenReturn(summaries(0, null, 0));
    when(catalogue.p4kImportJobs()).thenReturn(null);

    SyncStatusRow p4k = assembler.rows(7, NOW).getLast();

    assertThat(p4k.lastRunAt()).isNull();
    assertThat(p4k.status()).isNull();
    assertThat(p4k.inRange()).isFalse();
  }
}
