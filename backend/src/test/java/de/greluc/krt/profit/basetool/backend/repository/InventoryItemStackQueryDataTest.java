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

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-level regression coverage for the group-on-read stack queries ({@link
 * InventoryItemRepository#findGlobalStacks} / {@link InventoryItemRepository#findUserStacks},
 * ADR-0003, REQ-INV-002) against the real Postgres test schema (Testcontainers + Flyway via the
 * {@code test} profile).
 *
 * <p>The sibling {@code InventoryItemStackQueryTest} only smoke-tests these queries against an
 * empty table, so it cannot catch the trap this test pins down: the projection groups the
 * <em>nullable</em> {@code owningOrgUnit} association as a whole entity. A naive
 * constructor-expression projection over a nullable to-one renders an implicit INNER JOIN, which
 * silently drops every row where that association is {@code null} — the ownerless-personal stock a
 * user with no Staffel/SK records, and (before Variante C, REQ-INV-027, dropped the scalar {@code
 * jobOrder} / {@code mission} columns off the row) the vast majority of real Lager stock that
 * belongs to no job order and no mission. That made {@code /inventory/all} and {@code
 * /inventory/my} show "no entries" even though the aggregated overview listed the very same
 * material, which is why the query LEFT JOINs {@code owningOrgUnit}. Under the current model the
 * earmarks live in side tables and are no longer part of the stock identity, so an unearmarked
 * entry must also aggregate and surface; these tests seed exactly such rows — a non-personal item
 * earmarked to nothing and a personal item with a {@code null} owning org unit — and assert they
 * still surface.
 *
 * <p>The class is {@link Transactional} so each method rolls back: the seeded rows must never
 * commit to the shared Testcontainers database, otherwise the sibling empty-table smoke test (and
 * any other unscoped query) would observe this fixture. The query still sees the rows because they
 * are flushed within the test transaction before the read.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryItemStackQueryDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;

  @PersistenceContext private EntityManager entityManager;

  /**
   * A squadron-owned, non-personal item that is earmarked to neither a job order nor a mission (the
   * common case: both allocation collections are empty) must surface in the global stack view.
   * Since Variante C (REQ-INV-027) keeps the earmarks in side tables and out of the stock identity,
   * this pins the grouped read down to the physical stock — an unearmarked entry must still
   * aggregate and appear rather than be silently dropped.
   */
  @Test
  void findGlobalStacks_includesNonPersonalItemWithoutJobOrderOrMission() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Quantanium-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    InventoryItem inv = new InventoryItem();
    inv.setUser(user);
    inv.setLocation(location);
    inv.setMaterial(material);
    inv.setQuality(800);
    inv.setAmount(100.0);
    inv.setPersonal(false);
    inv.setOwningOrgUnit(squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());
    inventoryItemRepository.save(inv);
    entityManager.flush();

    UUID materialId = material.getId();
    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findGlobalStacks(
            true, List.of(materialId), null, false, null, false, null, true, null, Set.of());

    assertThat(stacks)
        .as(
            "a non-personal squadron item with null jobOrder/mission must still appear in the"
                + " admin-wide global stack view")
        .hasSize(1);
    assertThat(stacks.get(0).material().getId()).isEqualTo(materialId);
    assertThat(stacks.get(0).totalAmount()).isEqualTo(100.0);
  }

  /**
   * A personal item is, by the inventory invariants, never earmarked to a job order or mission and
   * may have a {@code null} owning org unit (ownerless personal). It must still surface in the
   * owner's grouped "my inventory" view; the implicit-join trap on the nullable {@code
   * owningOrgUnit} would otherwise hide every ownerless-personal stack, which is why the projection
   * LEFT JOINs it.
   */
  @Test
  void findUserStacks_includesPersonalItemWithoutAssociations() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Astatine-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    InventoryItem inv = new InventoryItem();
    inv.setUser(user);
    inv.setLocation(location);
    inv.setMaterial(material);
    inv.setQuality(500);
    inv.setAmount(42.0);
    inv.setPersonal(true);
    inv.setOwningOrgUnit(null);
    inventoryItemRepository.save(inv);
    entityManager.flush();

    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findUserStacks(
            user.getId(), false, null, null, false, null, false, null, false, false);

    assertThat(stacks)
        .as(
            "a personal item with null jobOrder/mission/owningOrgUnit must still appear in the"
                + " owner's grouped inventory view")
        .hasSize(1);
    assertThat(stacks.get(0).material().getId()).isEqualTo(material.getId());
    assertThat(stacks.get(0).totalAmount()).isEqualTo(42.0);
  }

  /**
   * The mutually exclusive "Mein Lager" personal- / non-personal-entries-only filters narrow the
   * owner's grouped view: {@code personalOnly = true} returns only the caller's private stock
   * ({@code personal = true}), {@code nonPersonalOnly = true} returns only the shared stock ({@code
   * personal = false}), and both {@code false} keeps every stack. Seeds one personal and one shared
   * contribution at the same location/material and asserts each toggle keeps only its side.
   */
  @Test
  void findUserStacks_personalAndNonPersonalOnly_narrowToMatchingStock() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Quantanium-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    InventoryItem personal = new InventoryItem();
    personal.setUser(user);
    personal.setLocation(location);
    personal.setMaterial(material);
    personal.setQuality(500);
    personal.setAmount(10.0);
    personal.setPersonal(true);
    inventoryItemRepository.save(personal);

    InventoryItem shared = new InventoryItem();
    shared.setUser(user);
    shared.setLocation(location);
    shared.setMaterial(material);
    shared.setQuality(600);
    shared.setAmount(25.0);
    shared.setPersonal(false);
    inventoryItemRepository.save(shared);
    entityManager.flush();

    List<InventoryStackAggregate> personalOnly =
        inventoryItemRepository.findUserStacks(
            user.getId(), false, null, null, false, null, false, null, true, false);
    assertThat(personalOnly)
        .as("personalOnly=true must return only the caller's personal stock")
        .hasSize(1);
    assertThat(personalOnly.get(0).personal()).isTrue();
    assertThat(personalOnly.get(0).totalAmount()).isEqualTo(10.0);

    List<InventoryStackAggregate> nonPersonalOnly =
        inventoryItemRepository.findUserStacks(
            user.getId(), false, null, null, false, null, false, null, false, true);
    assertThat(nonPersonalOnly)
        .as("nonPersonalOnly=true must return only the caller's shared (non-personal) stock")
        .hasSize(1);
    assertThat(nonPersonalOnly.get(0).personal()).isFalse();
    assertThat(nonPersonalOnly.get(0).totalAmount()).isEqualTo(25.0);

    List<InventoryStackAggregate> all =
        inventoryItemRepository.findUserStacks(
            user.getId(), false, null, null, false, null, false, null, false, false);
    assertThat(all)
        .as("both toggles false must return the personal and the shared stack")
        .hasSize(2);
  }
}
