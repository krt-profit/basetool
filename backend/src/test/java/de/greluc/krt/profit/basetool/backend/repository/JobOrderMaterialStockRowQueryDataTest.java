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
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-level regression coverage for {@link
 * InventoryItemRepository#findMaterialStockRowsByJobOrderIds} — the page-batched projection that
 * replaced the per-(order, material) {@code SUM} fan-out of the job-order list (REQ-DATA-003) — run
 * against the real Postgres test schema (Testcontainers + Flyway via the {@code test} profile).
 *
 * <p>The {@link JobOrderServiceAssigneeAndListTest} unit tests stub this query, so they cannot
 * catch two things only the real dialect proves: (1) the JPQL constructor expression {@code new
 * JobOrderMaterialStockRow(a.jobOrder.id, a.inventoryItem.material.id, a.inventoryItem.quality,
 * a.amount)} over {@code InventoryJobOrderAllocation} (Variante C, REQ-INV-027 — the scalar {@code
 * jobOrder} column was dropped) actually parses and binds the right columns on Postgres, and the
 * {@code a.jobOrder.id IN :ids} filter returns only <em>allocated</em> rows (stock carrying no
 * job-order slice excluded); and (2) summing the projected rows at a quality floor in memory
 * reproduces the native {@code sumAmountByMaterialAndJobOrderAndMinQuality} aggregate exactly — the
 * equivalence the list path relies on instead of one {@code SUM} per bucket per order.
 *
 * <p>{@link Transactional} so each method rolls back: the seeded rows must never commit to the
 * shared Testcontainers database. The query still observes them because they are flushed within the
 * test transaction before the read, and every assertion is scoped to the freshly created order id,
 * so rows other suites committed cannot perturb it.
 */
@SpringBootTest
@ActiveProfiles("test")
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
   * then asserts the batched projection returns exactly the three linked rows (carrying the right
   * material/quality/amount) and that an in-memory floor sum over them equals the native per-bucket
   * {@code SUM} at three representative floors (no floor, mid floor, above-all floor).
   */
  @Test
  void findMaterialStockRowsByJobOrderIds_returnsOnlyLinkedRowsAndMatchesNativeSumAtEachFloor() {
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

    saveLinkedItem(user, location, material, iridium, order, 300, 10.0);
    saveLinkedItem(user, location, material, iridium, order, 600, 20.0);
    saveLinkedItem(user, location, material, iridium, order, 900, 5.0);
    // An unallocated item (no job-order slice) at the same material must be dropped by the
    // allocation filter — it never surfaces in the InventoryJobOrderAllocation projection.
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
        .extracting(JobOrderMaterialStockRow::amount)
        .containsExactlyInAnyOrder(10.0, 20.0, 5.0);

    // null floor -> 35 (all), 600 -> 25 (600 + 900), 1000 -> 0 (none qualifies).
    for (Integer floor : new Integer[] {null, 600, 1000}) {
      double inMemorySum =
          rows.stream()
              .filter(r -> floor == null || (r.quality() != null && r.quality() >= floor))
              .mapToDouble(JobOrderMaterialStockRow::amount)
              .sum();
      double nativeSum =
          inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
              material.getId(), order.getId(), floor);
      assertThat(inMemorySum)
          .as("in-memory floor sum must equal the native per-bucket SUM at floor %s", floor)
          .isEqualTo(nativeSum);
    }
  }

  /**
   * Persists one non-personal, job-order-linked inventory item of the given grade and amount.
   *
   * @param user the owning user (NOT NULL FK).
   * @param location the storage location (NOT NULL FK).
   * @param material the stocked material (NOT NULL FK).
   * @param owner the owning org unit.
   * @param order the job order the row is linked to.
   * @param quality the quality grade (NOT NULL column).
   * @param amount the stocked amount in SCU (NOT NULL column).
   */
  private void saveLinkedItem(
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
    // Variante C (REQ-INV-027): the scalar jobOrder column is gone, so the fulfilment queries read
    // the per-entry job-order allocation. The fixture earmarks the full amount into one allocation
    // (cascade-persisted with the entry) exactly as the service create path does.
    InventoryAllocations.addJobOrder(inv, order, amount, false);
    inventoryItemRepository.save(inv);
  }
}
