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
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-level coverage for the Variante-C soak drop path (REQ-INV-027) against the real Postgres
 * test schema (Testcontainers + Flyway via the {@code test} profile): that the R2 allocation-drop
 * queries release an order's job-order slice while the entry itself survives in the Lager as
 * (partially) unassigned stock, with its amount intact.
 *
 * <p>The plain Mockito service tests cannot prove this: the bulk {@code DELETE} and the flush
 * ordering only exist on the real dialect. Each method is {@link Transactional} so the seeded rows
 * roll back and never pollute the shared Testcontainers database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryAllocationSoakDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private InventoryJobOrderAllocationRepository jobOrderAllocationRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;

  @PersistenceContext private EntityManager entityManager;

  /**
   * The R2 {@code deleteJobOrderAllocationsByJobOrder} drop releases the order's slice while the
   * entry itself survives in the Lager with its amount intact.
   */
  @Test
  void deleteJobOrderAllocationsByJobOrderKeepsTheEntry() {
    Fixture f = seedLinkedItem(75.0);
    entityManager.flush();

    inventoryItemRepository.deleteJobOrderAllocationsByJobOrder(f.orderId);
    entityManager.clear();

    assertThat(jobOrderAllocationRepository.findAll())
        .filteredOn(a -> a.getInventoryItem().getId().equals(f.itemId))
        .isEmpty();
    assertThat(inventoryItemRepository.findById(f.itemId))
        .get()
        .satisfies(i -> assertThat(i.getAmount()).isEqualTo(75.0));
  }

  /**
   * The material-scoped R2 drop {@code deleteJobOrderAllocationsByJobOrderAndMaterial} — used by
   * the handover and material-removal flows — likewise removes only the slice and leaves the entry.
   */
  @Test
  void deleteJobOrderAllocationsByJobOrderAndMaterialKeepsTheEntry() {
    Fixture f = seedLinkedItem(50.0);
    entityManager.flush();

    inventoryItemRepository.deleteJobOrderAllocationsByJobOrderAndMaterial(f.orderId, f.materialId);
    entityManager.clear();

    assertThat(jobOrderAllocationRepository.findAll())
        .filteredOn(a -> a.getInventoryItem().getId().equals(f.itemId))
        .isEmpty();
    assertThat(inventoryItemRepository.findById(f.itemId)).isPresent();
  }

  /**
   * Seeds one job order plus one non-personal inventory entry linked to it, writing a single
   * full-amount (not-delivered) job-order allocation exactly as the service create path does.
   *
   * @param amount the entry (and initial allocation) amount in SCU.
   * @return the created ids needed by the assertions.
   */
  private Fixture seedLinkedItem(double amount) {
    OrgUnit iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();

    JobOrder order =
        jobOrderRepository.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(iridium)
                .requestingOrgUnit(iridium)
                .handle("alloc-soak-" + UUID.randomUUID())
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

    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setLocation(location);
    item.setMaterial(material);
    item.setQuality(500);
    item.setAmount(amount);
    item.setPersonal(false);
    item.setOwningOrgUnit(iridium);
    InventoryAllocations.addJobOrder(item, order, amount, false);
    inventoryItemRepository.save(item);

    return new Fixture(item.getId(), order.getId(), material.getId());
  }

  /**
   * The ids of a seeded entry/order/material returned by {@link #seedLinkedItem(double)}.
   *
   * @param itemId the inventory entry id.
   * @param orderId the linked job order id.
   * @param materialId the entry's material id.
   */
  private record Fixture(UUID itemId, UUID orderId, UUID materialId) {}
}
