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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.SyncReportBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncRunSummaryDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies event-free history and independent backend-section failure handling. */
class SyncReportPageReaderTest {
  private final SyncReportBackendClient client = mock(SyncReportBackendClient.class);
  private final SyncReportPageReader reader = new SyncReportPageReader(client);

  @Test
  void noEvents_doesNotHideAttemptsOrOtherSources() {
    when(client.syncReportPage(0, null))
        .thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0, List.of()));
    when(client.syncRunSummaries(30))
        .thenReturn(
            List.of(
                new SyncRunSummaryDto("UEX", 2, Instant.now()),
                new SyncRunSummaryDto("SCWIKI", 1, Instant.now())));

    SyncReportPageReader.Data data = reader.read(0, null);

    assertThat(data.events().content()).isEmpty();
    assertThat(data.summaries()).hasSize(2);
    assertThat(data.eventsAvailable()).isTrue();
    assertThat(data.attemptsAvailable()).isTrue();
  }

  @Test
  void failedEventsSection_preservesTheSelectedSourcesHistory() {
    when(client.syncReportPage(0, "UEX"))
        .thenThrow(new IllegalStateException("events unavailable"));
    when(client.syncRunSummaries(30))
        .thenReturn(
            List.of(
                new SyncRunSummaryDto("UEX", 2, Instant.now()),
                new SyncRunSummaryDto("SCWIKI", 1, Instant.now())));

    SyncReportPageReader.Data data = reader.read(0, "UEX");

    assertThat(data.eventsAvailable()).isFalse();
    assertThat(data.attemptsAvailable()).isTrue();
    assertThat(data.summaries()).extracting(SyncRunSummaryDto::sourceSystem).containsExactly("UEX");
  }
}
