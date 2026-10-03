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
import de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies against the real schema that the „gestohlen" marker is part of the stock identity
 * (REQ-INV-053): stolen and legitimate stock of one material, location and quality are two stacks
 * that never merge and never mix entries, the filters narrow to either, and every total that
 * ignores the marker — the per-material overview and the craftability sum — stays exactly what it
 * was.
 */
@SpringBootTest
@Transactional
class InventoryStolenStockDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;

  @PersistenceContext private EntityManager entityManager;

  private User user;
  private Location location;
  private Material material;
  private Squadron iridium;

  @BeforeEach
  void seed() {
    user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("stolen-" + UUID.randomUUID());
    userRepository.save(user);
    location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);
    material = new Material();
    material.setName("Laranite-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
  }

  /**
   * Stores one row of the seeded material, location and quality.
   *
   * @param amount the SCU
   * @param stolen the marker
   * @param personal whether the row is personal
   * @return the saved row
   */
  private InventoryItem row(double amount, boolean stolen, boolean personal) {
    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setLocation(location);
    item.setMaterial(material);
    item.setQuality(600);
    item.setAmount(amount);
    item.setPersonal(personal);
    item.setStolen(stolen);
    item.setOwningOrgUnit(iridium);
    return inventoryItemRepository.save(item);
  }

  /**
   * Reads the caller's stacks of the seeded material with the given marker filter.
   *
   * @param stolenOnly narrow to stolen stock
   * @param nonStolenOnly narrow to legitimate stock
   * @return the stacks
   */
  private List<InventoryStackAggregate> userStacks(boolean stolenOnly, boolean nonStolenOnly) {
    return inventoryItemRepository.findUserStacks(
        user.getId(),
        true,
        List.of(material.getId()),
        false,
        null,
        null,
        false,
        null,
        false,
        null,
        false,
        false,
        stolenOnly,
        nonStolenOnly);
  }

  @Test
  void stolenAndLegitimateStockOfOneIdentityAreTwoStacks() {
    row(10.0, false, false);
    row(5.0, false, false);
    row(4.0, true, false);
    entityManager.flush();

    List<InventoryStackAggregate> stacks = userStacks(false, false);

    assertThat(stacks).hasSize(2);
    assertThat(stacks)
        .extracting(InventoryStackAggregate::stolen, InventoryStackAggregate::totalAmount)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(false, 15.0),
            org.assertj.core.groups.Tuple.tuple(true, 4.0));
  }

  @Test
  void theGlobalViewSeparatesThemToo() {
    row(10.0, false, false);
    row(4.0, true, false);
    entityManager.flush();

    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findGlobalStacks(
            true,
            List.of(material.getId()),
            false,
            null,
            null,
            false,
            null,
            false,
            null,
            true,
            null,
            Set.of(),
            false,
            false);

    assertThat(stacks)
        .extracting(InventoryStackAggregate::stolen)
        .containsExactlyInAnyOrder(false, true);
  }

  @Test
  void theFiltersNarrowToEitherSide() {
    row(10.0, false, false);
    row(4.0, true, false);
    entityManager.flush();

    assertThat(userStacks(true, false))
        .singleElement()
        .extracting(InventoryStackAggregate::totalAmount)
        .isEqualTo(4.0);
    assertThat(userStacks(false, true))
        .singleElement()
        .extracting(InventoryStackAggregate::totalAmount)
        .isEqualTo(10.0);

    List<UUID> stolenIds =
        inventoryItemRepository.findUserEntryIds(
            user.getId(),
            true,
            List.of(material.getId()),
            false,
            null,
            null,
            false,
            null,
            false,
            null,
            false,
            false,
            true,
            false);
    assertThat(stolenIds).hasSize(1);
  }

  @Test
  void expandingAStackListsOnlyItsOwnEntries() {
    InventoryItem legit = row(10.0, false, true);
    InventoryItem stolen = row(4.0, true, true);
    entityManager.flush();

    Page<InventoryItem> stolenEntries =
        inventoryItemRepository.findUserStackEntries(
            user.getId(),
            material.getId(),
            location.getId(),
            600,
            true,
            true,
            iridium.getId(),
            PageRequest.of(0, 20));
    Page<InventoryItem> legitEntries =
        inventoryItemRepository.findUserStackEntries(
            user.getId(),
            material.getId(),
            location.getId(),
            600,
            true,
            false,
            iridium.getId(),
            PageRequest.of(0, 20));

    assertThat(stolenEntries.getContent())
        .extracting(InventoryItem::getId)
        .containsExactly(stolen.getId());
    assertThat(legitEntries.getContent())
        .extracting(InventoryItem::getId)
        .containsExactly(legit.getId());
  }

  @Test
  void aMergeGroupNeverTakesStockOfTheOtherMarker() {
    InventoryItem legit = row(10.0, false, false);
    row(4.0, true, false);
    entityManager.flush();

    List<InventoryItem> group =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), material.getId(), location.getId(), 600, false, false, iridium.getId());

    assertThat(group).extracting(InventoryItem::getId).containsExactly(legit.getId());
  }

  @Test
  void thePerMaterialOverviewAndTheCraftabilitySumCountBothLikeBefore() {
    row(10.0, false, false);
    row(4.0, true, false);
    row(1.0, true, true);
    entityManager.flush();

    Page<Object[]> overview =
        inventoryItemRepository.getAggregatedInventory(
            true, null, Set.of(), PageRequest.of(0, 500));
    Object[] line =
        overview.getContent().stream()
            .filter(r -> ((Material) r[0]).getId().equals(material.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(((Number) line[3]).doubleValue())
        .as("the shared overview makes no distinction: 10 + 4, the personal row stays out")
        .isEqualTo(14.0);

    List<OwnedStockSlice> owned =
        inventoryItemRepository.sumOwnedStockByMaterialAndQuality(user.getId());
    assertThat(owned)
        .filteredOn(s -> s.materialId().equals(material.getId()))
        .singleElement()
        .extracting(OwnedStockSlice::totalScu)
        .as("a stolen material crafts the same: 10 + 4 + 1")
        .isEqualTo(15.0);
  }
}
