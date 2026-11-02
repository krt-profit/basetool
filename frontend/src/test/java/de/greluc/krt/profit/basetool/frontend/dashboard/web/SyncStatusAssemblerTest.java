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
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportJobDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncReportDto;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.SyncSource;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.SyncStatusRow;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests of {@link SyncStatusAssembler}: the newest run per source, the range test and the
 * import job's timestamp and status (REQ-UI-034).
 */
@ExtendWith(MockitoExtension.class)
class SyncStatusAssemblerTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

  @Mock private CatalogueBackendClient catalogue;

  private SyncStatusAssembler assembler;

  @BeforeEach
  void setUp() {
    assembler = new SyncStatusAssembler(catalogue);
  }

  private static PageResponse<SyncReportDto> reports(Instant... ranAt) {
    List<SyncReportDto> content =
        java.util.Arrays.stream(ranAt)
            .map(at -> new SyncReportDto(null, null, at, null, null, null, null, null, null, null))
            .toList();
    return new PageResponse<>(content, 0, 20, content.size(), 1, List.of());
  }

  private static P4kImportJobDto job(String status, String createdAt, String finishedAt) {
    return new P4kImportJobDto(
        null, null, status, false, null, null, null, null, null, createdAt, null, finishedAt);
  }

  private void noRuns() {
    when(catalogue.syncReportPage(0, "UEX")).thenReturn(reports());
    when(catalogue.syncReportPage(0, "SCWIKI")).thenReturn(reports());
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
  void aSourceThatNeverRanHasNoTimeNoStatusAndIsOutOfRange() {
    noRuns();

    assertThat(assembler.rows(7, NOW))
        .allSatisfy(row -> {
          assertThat(row.lastRunAt()).isNull();
          assertThat(row.status()).isNull();
          assertThat(row.inRange()).isFalse();
        });
  }

  @Test
  void aSyncRowTakesTheNewestReportAndIsInRangeWhenItIsNotOlderThanTheRange() {
    Instant newest = NOW.minusSeconds(3600);
    when(catalogue.syncReportPage(0, "UEX")).thenReturn(reports(newest, NOW.minusSeconds(7200)));
    when(catalogue.syncReportPage(0, "SCWIKI")).thenReturn(reports());
    when(catalogue.p4kImportJobs()).thenReturn(List.of());

    SyncStatusRow uex = assembler.rows(1, NOW).getFirst();

    assertThat(uex.lastRunAt()).isEqualTo(newest);
    assertThat(uex.status()).isNull();
    assertThat(uex.inRange()).isTrue();
  }

  @Test
  void aReportOlderThanTheRangeIsOutOfRange() {
    Instant old = NOW.minusSeconds(8 * 86_400L);
    when(catalogue.syncReportPage(0, "UEX")).thenReturn(reports(old));
    when(catalogue.syncReportPage(0, "SCWIKI")).thenReturn(reports());
    when(catalogue.p4kImportJobs()).thenReturn(List.of());

    SyncStatusRow uex = assembler.rows(7, NOW).getFirst();

    assertThat(uex.lastRunAt()).isEqualTo(old);
    assertThat(uex.inRange()).isFalse();
  }

  @Test
  void aReportExactlyAtTheStartOfTheRangeIsInRange() {
    Instant boundary = NOW.minusSeconds(7 * 86_400L);
    when(catalogue.syncReportPage(0, "UEX")).thenReturn(reports(boundary));
    when(catalogue.syncReportPage(0, "SCWIKI")).thenReturn(reports());
    when(catalogue.p4kImportJobs()).thenReturn(List.of());

    assertThat(assembler.rows(7, NOW).getFirst().inRange()).isTrue();
  }

  @Test
  void anAbsentReportPageCountsAsNoRun() {
    when(catalogue.syncReportPage(0, "UEX")).thenReturn(null);
    when(catalogue.syncReportPage(0, "SCWIKI")).thenReturn(reports());
    when(catalogue.p4kImportJobs()).thenReturn(List.of());

    assertThat(assembler.rows(7, NOW).getFirst().lastRunAt()).isNull();
  }

  @Test
  void theImportRowUsesTheNewestJobFinishTimeAndItsStatus() {
    noRunsForSyncs();
    when(catalogue.p4kImportJobs())
        .thenReturn(
            List.of(
                job("SUCCEEDED", "2026-11-02T09:00:00Z", "2026-11-02T10:00:00Z"),
                job("FAILED", "2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z")));

    SyncStatusRow p4k = assembler.rows(7, NOW).getLast();

    assertThat(p4k.lastRunAt()).isEqualTo(Instant.parse("2026-11-02T10:00:00Z"));
    assertThat(p4k.status()).isEqualTo("SUCCEEDED");
    assertThat(p4k.inRange()).isTrue();
  }

  @Test
  void anUnfinishedImportFallsBackToItsCreationTime() {
    noRunsForSyncs();
    when(catalogue.p4kImportJobs())
        .thenReturn(List.of(job("RUNNING", "2026-11-02T11:00:00Z", null)));

    SyncStatusRow p4k = assembler.rows(7, NOW).getLast();

    assertThat(p4k.lastRunAt()).isEqualTo(Instant.parse("2026-11-02T11:00:00Z"));
    assertThat(p4k.status()).isEqualTo("RUNNING");
  }

  @Test
  void anAbsentJobListCountsAsNoImport() {
    noRunsForSyncs();
    when(catalogue.p4kImportJobs()).thenReturn(null);

    SyncStatusRow p4k = assembler.rows(7, NOW).getLast();

    assertThat(p4k.lastRunAt()).isNull();
    assertThat(p4k.status()).isNull();
    assertThat(p4k.inRange()).isFalse();
  }

  private void noRunsForSyncs() {
    when(catalogue.syncReportPage(0, "UEX")).thenReturn(reports());
    when(catalogue.syncReportPage(0, "SCWIKI")).thenReturn(reports());
  }
}
