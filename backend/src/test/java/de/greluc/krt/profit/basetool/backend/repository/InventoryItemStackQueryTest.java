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
 * Runs every group-on-read stack query against an empty PostgreSQL table to verify that the JPQL
 * parses and executes on the real dialect (REQ-INV-002).
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
                false, null, false, null, null, false, null, false, null, true, null, Set.of()))
        .isEmpty();
    assertThat(
            inventoryItemRepository.findUserStacks(
                UUID.randomUUID(),
                false,
                null,
                false,
                null,
                null,
                false,
                null,
                false,
                null,
                false,
                false))
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
