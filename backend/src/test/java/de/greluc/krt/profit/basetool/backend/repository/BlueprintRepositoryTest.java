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
 * Runs {@link BlueprintRepository}'s admin list queries against PostgreSQL to ensure the no-filter
 * path ({@link BlueprintRepository#findByScwikiDeletedAtIsNull(Pageable)}) and the search path
 * ({@link BlueprintRepository#searchActive(String, Pageable)}) plan without a {@code lower(bytea)}
 * error.
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

  @Test
  void findItemsWithActiveBlueprint_executesAgainstPostgresForBothSearchShapes() {
    assertNotNull(blueprintRepository.findItemsWithActiveBlueprint("", PageRequest.of(0, 25)));
    assertNotNull(blueprintRepository.findItemsWithActiveBlueprint("drive", PageRequest.of(0, 25)));
  }
}
