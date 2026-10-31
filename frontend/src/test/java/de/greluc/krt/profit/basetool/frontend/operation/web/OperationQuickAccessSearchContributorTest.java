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

package de.greluc.krt.profit.basetool.frontend.operation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.operation.client.OperationBackendClient;
import de.greluc.krt.profit.basetool.frontend.operation.client.OperationBackendClient.OperationSearch;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link OperationQuickAccessSearchContributor}: the operation list's read. */
@ExtendWith(MockitoExtension.class)
class OperationQuickAccessSearchContributorTest {

  @Mock private OperationBackendClient operationClient;

  @InjectMocks private OperationQuickAccessSearchContributor contributor;

  /** It searches operations. */
  @Test
  void searchesOperations() {
    assertThat(contributor.kind()).isEqualTo(QuickAccessSearchKind.OPERATION);
  }

  /** The term and limit go to the list read across every period. */
  @Test
  void asksTheListReadOverEveryPeriod() {
    when(operationClient.searchOperations(any()))
        .thenReturn(new PageResponse<>(List.of(), 0, 5, 0, 0, List.of()));

    contributor.results("nova", 5);

    ArgumentCaptor<OperationSearch> search = ArgumentCaptor.forClass(OperationSearch.class);
    verify(operationClient).searchOperations(search.capture());
    assertThat(search.getValue()).isEqualTo(new OperationSearch("nova", null, null, 0, 5, "ALL"));
  }

  /** Each operation becomes a hit on its detail page with its status and creation date. */
  @Test
  void mapsOperationsToDetailHits() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");
    Instant created = Instant.parse("2026-10-01T10:00:00Z");
    when(operationClient.searchOperations(any()))
        .thenReturn(
            new PageResponse<>(
                List.of(
                    new OperationDto(
                        id, "Nova", null, "CANCELED", null, 1L, created, created, false)),
                0,
                5,
                1,
                1,
                List.of()));

    assertThat(contributor.results("nova", 5))
        .containsExactly(
            new QuickAccessHit(
                "/operations/00000000-0000-0000-0000-000000000002",
                "Nova",
                "operation.status.canceled",
                created));
  }

  /** A response without a body yields no hit. */
  @Test
  void noBodyYieldsNoHit() {
    when(operationClient.searchOperations(any())).thenReturn(null);

    assertThat(contributor.results("nova", 5)).isEmpty();
  }
}
