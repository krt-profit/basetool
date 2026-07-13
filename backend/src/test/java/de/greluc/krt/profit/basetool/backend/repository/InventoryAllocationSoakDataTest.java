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
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocationSync;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-level coverage for the Variante-C soak write path (REQ-INV-027) against the real Postgres
 * test schema (Testcontainers + Flyway via the {@code test} profile): that {@link
 * InventoryAllocationSync#mirrorScalars(InventoryItem)} re-mirrors a <em>persisted</em> entry to
 * the same job order <em>in place</em> without tripping the non-deferrable {@code
 * uq_inv_job_order_alloc} unique constraint (V216), and that the R2 allocation-drop queries release
 * an order's slice while the entry survives as unassigned stock.
 *
 * <p>The plain Mockito service tests cannot prove either: the constraint and the flush ordering
 * only exist on the real dialect, and the in-memory mirror never round-trips through a committed
 * row. Each method is {@link Transactional} so the seeded rows roll back and never pollute the
 * shared Testcontainers database.
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
   * Re-mirroring a persisted, flushed entry to the same order after lowering its amount must UPDATE
   * the existing slice, not delete-then-re-insert the same {@code (inventory_item_id,
   * job_order_id)} pair — the latter violates the non-deferrable unique constraint because
   * Hibernate flushes the INSERT before the orphan DELETE. Proves the checkout / handover
   * amount-reduce paths are safe on real Postgres.
   */
  @Test
  void mirrorScalarsReMirrorsSameOrderInPlaceWithoutConstraintViolation() {
    Fixture f = seedLinkedItem(100.0);

    // Detach so the re-mirror operates on a freshly loaded entry with its slice already committed —
    // the exact cross-flush shape the reduce paths hit in production.
    entityManager.flush();
    entityManager.clear();

    InventoryItem reloaded = inventoryItemRepository.findById(f.itemId).orElseThrow();
    reloaded.setAmount(40.0);
    InventoryAllocationSync.mirrorScalars(reloaded);
    inventoryItemRepository.saveAndFlush(reloaded); // must not throw a unique-constraint violation

    entityManager.clear();
    assertThat(jobOrderAllocationRepository.findAll())
        .filteredOn(a -> a.getInventoryItem().getId().equals(f.itemId))
        .singleElement()
        .satisfies(a -> assertThat(a.getAmount()).isEqualTo(40.0));
  }

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
   * Seeds one job order plus one non-personal inventory entry linked to it, mirroring the scalar
   * into a single full-amount allocation exactly as the service create path does.
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
    item.setJobOrder(order);
    InventoryAllocationSync.mirrorScalars(item);
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
