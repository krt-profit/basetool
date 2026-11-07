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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIdNameRow;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs {@link BlueprintRepository}'s queries against PostgreSQL: the admin list's no-filter path
 * ({@link
 * BlueprintRepository#findByScwikiDeletedAtIsNullAndP4kDeletedAtIsNullAndP4kHiddenAtIsNull(Pageable)})
 * and search path ({@link BlueprintRepository#searchActive(String, Pageable)}) plan without a
 * {@code lower(bytea)} error, and the queries that weigh a P4K-live recipe over a retired one
 * (REQ-P4K-013, REQ-P4K-027).
 */
@SpringBootTest
class BlueprintRepositoryTest {

  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private EntityManager entityManager;

  @Test
  void findByScwikiDeletedAtIsNull_executesAgainstPostgresWithoutSearchTerm() {
    Page<Blueprint> page =
        blueprintRepository.findByScwikiDeletedAtIsNullAndP4kDeletedAtIsNullAndP4kHiddenAtIsNull(
            PageRequest.of(0, 25));
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

  @Test
  @Transactional
  void findActiveIdNameRows_listsALiveRecipeBeforeARetiredOneOfTheSameName() {
    Blueprint retired = blueprint("Aaa Repository Order Pistol");
    retired.setP4kDeletedAt(Instant.now());
    Blueprint hidden = blueprint("Aaa Repository Order Pistol");
    hidden.setP4kHiddenAt(Instant.now());
    Blueprint live = blueprint("Aaa Repository Order Pistol");

    var rows =
        blueprintRepository.findActiveIdNameRows().stream()
            .filter(row -> "Aaa Repository Order Pistol".equals(row.outputName()))
            .toList();

    assertThat(rows.getFirst().id()).isEqualTo(live.getId());
    assertThat(rows.getFirst().retired()).isFalse();
    assertThat(rows).filteredOn(BlueprintIdNameRow::retired).hasSize(2);
    assertThat(rows)
        .extracting(BlueprintIdNameRow::id)
        .containsExactlyInAnyOrder(live.getId(), retired.getId(), hidden.getId());
  }

  @Test
  @Transactional
  void markScwikiDeleted_leavesARecipeTheP4kStillLists() {
    Blueprint wikiOnly = blueprint("Wiki-only recipe");
    wikiOnly.setScwikiUuid(UUID.randomUUID());
    Blueprint p4kLive = blueprint("P4K-live recipe");
    p4kLive.setScwikiUuid(UUID.randomUUID());
    p4kLive.setP4kUuid(UUID.randomUUID());
    Blueprint p4kSwept = blueprint("P4K-swept recipe");
    p4kSwept.setScwikiUuid(UUID.randomUUID());
    p4kSwept.setP4kUuid(UUID.randomUUID());
    p4kSwept.setP4kDeletedAt(Instant.now());
    entityManager.flush();

    blueprintRepository.markScwikiDeleted(Set.of(UUID.randomUUID()), Instant.now());
    entityManager.clear();

    assertThat(reload(wikiOnly).getScwikiDeletedAt()).isNotNull();
    assertThat(reload(p4kLive).getScwikiDeletedAt()).isNull();
    assertThat(reload(p4kSwept).getScwikiDeletedAt()).isNotNull();
  }

  @Test
  @Transactional
  void findP4kSeededByScwikiKey_listsTheRecipeTheP4kStillListsFirst() {
    String key = "BP_CRAFT_repository_order_" + UUID.randomUUID();
    Blueprint swept = blueprint("Seeded swept");
    swept.setScwikiKey(key);
    swept.setP4kUuid(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    swept.setP4kDeletedAt(Instant.now());
    Blueprint live = blueprint("Seeded live");
    live.setScwikiKey(key);
    live.setP4kUuid(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
    entityManager.flush();

    assertThat(blueprintRepository.findP4kSeededByScwikiKey(key)).containsExactly(live, swept);
  }

  private Blueprint blueprint(String outputName) {
    Blueprint blueprint = new Blueprint();
    blueprint.setOutputName(outputName);
    return blueprintRepository.save(blueprint);
  }

  private Blueprint reload(Blueprint blueprint) {
    return blueprintRepository.findById(blueprint.getId()).orElseThrow();
  }
}
