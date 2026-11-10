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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexFactionDto;
import de.greluc.krt.profit.basetool.backend.testcontext.LeafServiceMockTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL query counts stay proportional to the number of synchronization chunks. */
@LeafServiceMockTest
class UexUniverseBatchQueriesIntegrationTest {

  @Autowired private UexClient uexClient;
  @Autowired private UexUniverseSyncService service;
  @Autowired private FactionRepository repository;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void tenAndFiveHundredTenNewRowsUseOneAndTwoLookupQueries() {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean enabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    String token = UUID.randomUUID().toString();
    try {
      for (int size : List.of(10, 510)) {
        int offset = size == 10 ? 1400000000 : 1400001000;
        List<UexFactionDto> rows =
            IntStream.range(0, size)
                .mapToObj(i ->
                    UexFactionDto.builder()
                        .id(offset + i)
                        .name(token + "-" + size + "-" + i)
                        .build())
                .toList();
        when(uexClient.getFactions()).thenReturn(new UexClient.FetchResult<>(rows, true));
        statistics.clear();
        try {
          service.syncFactions();
          long queries = statistics.getQueryExecutionCount();
          assertThat(queries).isEqualTo(size == 10 ? 1 : 2);
          System.out.printf("UEX universe lookup queries: rows=%d queries=%d%n", size, queries);
        } finally {
          transactionTemplate.executeWithoutResult(status ->
              repository.deleteAllInBatch(
                  repository.findByIdFactionInOrNameInOrderByIdAsc(
                      rows.stream().map(UexFactionDto::id).toList(), List.of())));
        }
      }
    } finally {
      statistics.setStatisticsEnabled(enabled);
    }
  }
}
