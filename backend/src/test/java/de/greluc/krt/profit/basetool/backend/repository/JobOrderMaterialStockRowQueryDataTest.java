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
import static org.assertj.core.api.Assertions.tuple;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies {@link InventoryItemRepository#findMaterialStockRowsByJobOrderIds} against PostgreSQL:
 * the projection binds correctly, returns only allocated rows with their inventory row id, and
 * summing at a quality floor yields the order's linked stock per bucket (REQ-DATA-003). Each test
 * rolls back.
 */
@SpringBootTest
@Transactional
class JobOrderMaterialStockRowQueryDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;

  @PersistenceContext private EntityManager entityManager;

  /**
   * Seeds one order with three linked inventory rows at mixed quality grades plus one unlinked row,
   * then asserts the batched projection returns exactly the three linked rows, each carrying its
   * inventory row id, material, quality and allocated amount, and that a floor sum over them gives
   * the linked stock at three representative floors (no floor, mid floor, above-all floor).
   */
  @Test
  void findMaterialStockRowsByJobOrderIds_returnsOnlyLinkedRowsWithTheirIdsAndSumsAtEachFloor() {
    OrgUnit iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();

    JobOrder order =
        jobOrderRepository.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(iridium)
                .requestingOrgUnit(iridium)
                .handle("stockrow-test")
                .status(JobOrderStatus.OPEN)
                .build());

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

    InventoryItem low = saveLinkedItem(user, location, material, iridium, order, 300, 10.0);
    InventoryItem mid = saveLinkedItem(user, location, material, iridium, order, 600, 20.0);
    InventoryItem high = saveLinkedItem(user, location, material, iridium, order, 900, 5.0);
    InventoryItem unlinked = new InventoryItem();
    unlinked.setUser(user);
    unlinked.setLocation(location);
    unlinked.setMaterial(material);
    unlinked.setQuality(900);
    unlinked.setAmount(1000.0);
    unlinked.setPersonal(false);
    unlinked.setOwningOrgUnit(iridium);
    inventoryItemRepository.save(unlinked);
    entityManager.flush();

    List<JobOrderMaterialStockRow> rows =
        inventoryItemRepository.findMaterialStockRowsByJobOrderIds(List.of(order.getId()));

    assertThat(rows)
        .as("only rows linked to the order are returned; the unlinked 1000-SCU item is excluded")
        .hasSize(3)
        .allSatisfy(
            r -> {
              assertThat(r.jobOrderId()).isEqualTo(order.getId());
              assertThat(r.materialId()).isEqualTo(material.getId());
            });
    assertThat(rows)
        .extracting(
            JobOrderMaterialStockRow::inventoryItemId,
            JobOrderMaterialStockRow::quality,
            JobOrderMaterialStockRow::amount)
        .containsExactlyInAnyOrder(
            tuple(low.getId(), 300, 10.0),
            tuple(mid.getId(), 600, 20.0),
            tuple(high.getId(), 900, 5.0));
    assertThat(rows)
        .extracting(JobOrderMaterialStockRow::inventoryItemId)
        .doesNotContain(unlinked.getId());

    assertThat(sumAtFloor(rows, null)).isEqualTo(35.0);
    assertThat(sumAtFloor(rows, 600)).isEqualTo(25.0);
    assertThat(sumAtFloor(rows, 1000)).isZero();
  }

  /**
   * Sums the projected amounts whose quality reaches the floor.
   *
   * @param rows the projected stock rows
   * @param floor the minimum quality, or {@code null} for every row
   * @return the summed amount
   */
  private static double sumAtFloor(List<JobOrderMaterialStockRow> rows, Integer floor) {
    return rows.stream()
        .filter(r -> floor == null || (r.quality() != null && r.quality() >= floor))
        .mapToDouble(JobOrderMaterialStockRow::amount)
        .sum();
  }

  /**
   * Persists a non-personal inventory item linked to the given job order.
   *
   * @param user the owning user (NOT NULL FK).
   * @param location the storage location (NOT NULL FK).
   * @param material the stocked material (NOT NULL FK).
   * @param owner the owning org unit.
   * @param order the job order the row is linked to.
   * @param quality the quality grade (NOT NULL column).
   * @param amount the stocked amount in SCU (NOT NULL column).
   * @return the saved inventory item.
   */
  private InventoryItem saveLinkedItem(
      User user,
      Location location,
      Material material,
      OrgUnit owner,
      JobOrder order,
      int quality,
      double amount) {
    InventoryItem inv = new InventoryItem();
    inv.setUser(user);
    inv.setLocation(location);
    inv.setMaterial(material);
    inv.setQuality(quality);
    inv.setAmount(amount);
    inv.setPersonal(false);
    inv.setOwningOrgUnit(owner);
    InventoryAllocations.addJobOrder(inv, order, amount, false);
    return inventoryItemRepository.save(inv);
  }
}
