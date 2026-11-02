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

package de.greluc.krt.profit.basetool.frontend.catalogue.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncRunSummaryDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the requests {@link SyncReportBackendClient} sends and how it reads the run counts. */
class SyncReportBackendClientTest {

  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":100,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private SyncReportBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new SyncReportBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void syncReports() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{\"deleted\":3}");
    backend.answerJson("{\"deleted\":0}");

    client.syncReportPage(2, "UEX");
    client.syncReportPage(0, null);
    assertThat(client.purgeSyncReports("SCWIKI", 30).deleted()).isEqualTo(3);
    client.purgeSyncReports(null, 7);

    backend.expect("GET", "/api/v1/catalog/admin/sync-reports?page=2&size=100&source=UEX");
    backend.expect("GET", "/api/v1/catalog/admin/sync-reports?page=0&size=100");
    backend.expect(
        "DELETE", "/api/v1/catalog/admin/sync-reports?olderThanDays=30&source=SCWIKI", null);
    backend.expect("DELETE", "/api/v1/catalog/admin/sync-reports?olderThanDays=7", null);
  }

  @Test
  void syncRunSummaries() {
    backend.answerJson(
        "[{\"sourceSystem\":\"UEX\",\"runs\":3,\"lastRanAt\":\"2026-11-01T10:00:00Z\"}]");

    List<SyncRunSummaryDto> summaries = client.syncRunSummaries(7);

    assertThat(summaries).hasSize(1);
    assertThat(summaries.getFirst().runs()).isEqualTo(3);
    backend.expect("GET", "/api/v1/catalog/admin/sync-reports/runs?days=7");
  }
}
