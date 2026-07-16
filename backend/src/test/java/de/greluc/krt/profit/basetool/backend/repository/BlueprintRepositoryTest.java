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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * TestContainers-backed regression tests for {@link BlueprintRepository}'s admin-page list queries.
 * These run the real SQL against PostgreSQL so the empty-search path can never silently regress to
 * the {@code function lower(bytea) does not exist} failure that broke {@code GET
 * /api/v1/blueprints} whenever the admin opened the page without a filter: a {@code null} named
 * parameter inlined into {@code LOWER(CONCAT(...))} makes PostgreSQL type the bind as {@code
 * bytea}. The queries are split so the no-filter load ({@link
 * BlueprintRepository#findByScwikiDeletedAtIsNull(Pageable)}) never passes a string-function
 * argument and the search load ({@link BlueprintRepository#searchActive(String, Pageable)}) only
 * ever receives a non-null term. An empty table is sufficient: PostgreSQL resolves every function
 * signature at plan time, so a grammar regression throws before any row is read.
 */
@SpringBootTest
@ActiveProfiles("test")
class BlueprintRepositoryTest {

  @Autowired private BlueprintRepository blueprintRepository;

  @Test
  void findByScwikiDeletedAtIsNull_executesAgainstPostgresWithoutSearchTerm() {
    Page<Blueprint> page = blueprintRepository.findByScwikiDeletedAtIsNull(PageRequest.of(0, 25));
    assertNotNull(page);
  }

  @Test
  void searchActive_executesAgainstPostgresWithSearchTerm() {
    Page<Blueprint> page = blueprintRepository.searchActive("omni", PageRequest.of(0, 25));
    assertNotNull(page);
  }

  // covers REQ-INV-029 (item-catalog picker query: both search shapes plan against Postgres)
  @Test
  void findItemsWithActiveBlueprint_executesAgainstPostgresForBothSearchShapes() {
    // The Lager item-catalog query shares the LOWER(CONCAT(...)) grammar, so the empty-string
    // no-filter shape and the term shape both need the plan-time smoke test — the service binds
    // "" (never null) for "no filter" for exactly the bytea reason documented on the class.
    assertNotNull(blueprintRepository.findItemsWithActiveBlueprint("", PageRequest.of(0, 25)));
    assertNotNull(blueprintRepository.findItemsWithActiveBlueprint("drive", PageRequest.of(0, 25)));
  }
}
