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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots the real Postgres test schema (Testcontainers + Flyway via the {@code test} profile) and
 * executes every group-on-read stack query introduced for the append-only lazy-load Lager
 * (ADR-0003, REQ-INV-002) against an empty table. The point is to validate that the JPQL actually
 * parses and runs on Postgres — in particular the entity-argument constructor expression and the
 * eight-dimension {@code GROUP BY} of {@link InventoryItemRepository#findGlobalStacks} / {@link
 * InventoryItemRepository#findUserStacks}, plus the null-safe stack-key matching of the two
 * paginated entry drill-downs. Aggregate-math and grouping correctness is covered by the
 * service-level tests; this is the SQL smoke test that fails loudly if a query is malformed for the
 * real dialect.
 */
@SpringBootTest
class InventoryItemStackQueryTest {

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private InventoryItemRepository inventoryItemRepository;

  /** The four new stack queries must execute against Postgres (empty table -&gt; empty results). */
  @Test
  void stackQueriesExecuteAgainstPostgres() {
    Pageable firstPage = PageRequest.of(0, 20);

    assertThat(
            inventoryItemRepository.findGlobalStacks(
                false, null, null, false, null, false, null, true, null, Set.of()))
        .isEmpty();
    assertThat(
            inventoryItemRepository.findUserStacks(
                UUID.randomUUID(), false, null, null, false, null, false, null, false))
        .isEmpty();
    assertThat(
            inventoryItemRepository
                .findGlobalStackEntries(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    500,
                    null,
                    true,
                    null,
                    Set.of(),
                    firstPage)
                .getContent())
        .isEmpty();
    assertThat(
            inventoryItemRepository
                .findUserStackEntries(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    500,
                    false,
                    null,
                    firstPage)
                .getContent())
        .isEmpty();
  }
}
