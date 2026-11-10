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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexFactionDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexTerminalDto;
import de.greluc.krt.profit.basetool.backend.testcontext.LeafServiceMockTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
  @Autowired private TerminalRepository terminalRepository;
  @Autowired private ShopRepository shopRepository;
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
          long lookups = executionsContaining(statistics, "FROM Faction");
          long sourceMarkers =
              executionsContaining(statistics, "set_config('basetool.change_source'");
          long chunks = size == 10 ? 1 : 2;
          assertThat(lookups).as("chunk identity/name lookups").isEqualTo(chunks);
          assertThat(sourceMarkers).as("transactional change-source markers").isEqualTo(chunks);
          assertThat(queries).as("all HQL/native query executions").isEqualTo(2 * chunks);
          assertThat(
                  repository.findByIdFactionInOrNameInOrderByIdAsc(
                      rows.stream().map(UexFactionDto::id).toList(), List.of()))
              .extracting(Faction::getIdFaction)
              .containsExactlyInAnyOrderElementsOf(rows.stream().map(UexFactionDto::id).toList());
          System.out.printf(
              "UEX_QUERY_PROFILE rows=%d lookup_queries=%d change_source_queries=%d"
                  + " total_queries=%d%n",
              size, lookups, sourceMarkers, queries);
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

  private static long executionsContaining(Statistics statistics, String queryPart) {
    return Arrays.stream(statistics.getQueries())
        .filter(query -> query.contains(queryPart))
        .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
        .sum();
  }

  @Test
  void sharedTerminalNamesAdoptBothLocalRowsAndPreserveOverridesAndShopLinks() {
    String name = "Shared terminal " + UUID.randomUUID();
    List<UUID> terminals =
        Objects.requireNonNull(
            transactionTemplate.execute(_ -> {
              for (int i = 0; i < 2; i++) {
                Terminal terminal = new Terminal();
                terminal.setName(name);
                terminal.setHidden(true);
                terminal.setHasLoadingDock(true);
                terminal.setHasLoadingDockOverridden(true);
                terminal.setIsAutoLoad(true);
                terminal.setIsAutoLoadOverridden(true);
                terminalRepository.saveAndFlush(terminal);
              }
              return terminalRepository
                  .findByIdTerminalInOrNameInOrderByIdAsc(List.of(), List.of(name))
                  .stream()
                  .map(Terminal::getId)
                  .toList();
            }));
    assertThat(terminals).hasSize(2);
    List<UUID> shops =
        Objects.requireNonNull(
            transactionTemplate.execute(_ -> {
              List<UUID> ids = new ArrayList<>();
              for (UUID terminal : terminals) {
                Shop shop = new Shop();
                shop.setShopKey("Synthetic linked shop " + UUID.randomUUID());
                shop.setP4kUuid(UUID.randomUUID());
                shopRepository.saveAndFlush(shop);
                assertThat(
                        shopRepository.manualLinkTerminal(
                            shop.getId(), terminal, true, null, false))
                    .isEqualTo(1);
                ids.add(shop.getId());
              }
              return ids;
            }));
    List<UexTerminalDto> rows =
        IntStream.range(0, 2)
            .mapToObj(i ->
                UexTerminalDto.builder()
                    .id(1400030000 + i)
                    .name(name)
                    .hasLoadingDock(0)
                    .isAutoLoad(0)
                    .build())
            .toList();
    when(uexClient.getTerminals()).thenReturn(new UexClient.FetchResult<>(rows, true));
    try {
      service.syncTerminals();
      transactionTemplate.executeWithoutResult(_ -> {
        assertThat(
                terminalRepository.findByIdTerminalInOrNameInOrderByIdAsc(List.of(), List.of(name)))
            .extracting(Terminal::getId)
            .containsExactlyElementsOf(terminals);
        for (int i = 0; i < terminals.size(); i++) {
          Terminal terminal = terminalRepository.findById(terminals.get(i)).orElseThrow();
          assertThat(terminal.getIdTerminal()).isEqualTo(1400030000 + i);
          assertThat(terminal.getHidden()).isTrue();
          assertThat(terminal.getHasLoadingDock()).isTrue();
          assertThat(terminal.getHasLoadingDockOverridden()).isTrue();
          assertThat(terminal.getIsAutoLoad()).isTrue();
          assertThat(terminal.getIsAutoLoadOverridden()).isTrue();
          assertThat(terminal.getUexHasLoadingDock()).isFalse();
          assertThat(terminal.getUexIsAutoLoad()).isFalse();
          assertThat(shopRepository.findById(shops.get(i)).orElseThrow().getTerminalId())
              .isEqualTo(terminals.get(i));
        }
      });
    } finally {
      transactionTemplate.executeWithoutResult(_ -> {
        shopRepository.deleteAllByIdInBatch(shops);
        terminalRepository.deleteAllByIdInBatch(terminals);
      });
    }
  }
}
