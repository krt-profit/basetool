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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverItemCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Worst-case reproduction of the JobOrder handover Optimistic-Locking bug for <b>fragmented
 * stock</b>: two materials, each split across {@value #STACKS_PER_MATERIAL} separate {@link
 * InventoryItem} rows, all handed over (and thus the JobOrder fully completed) in a SINGLE handover
 * request as a MEMBER+LOGISTIKER.
 *
 * <p>This is the constellation that the live-log MEMBER failure (JobOrder #40) actually had —
 * multiple {@link InventoryItem}s per {@link Material} cause the per-item loop in {@link
 * JobOrderHandoverService#createHandover(UUID, JobOrderHandoverCreateDto)} to iterate multiple
 * times over the SAME {@link JobOrderMaterial}, which (before the fix) was the trigger for a
 * mid-loop persistence-context detach + implicit {@code merge()} → second {@code @Version} bump →
 * 409.
 *
 * <p>Earlier integration tests used a 1:1 mapping (one InventoryItem per Material) and therefore
 * could not reproduce this exact failure path. This test guarantees the structural fix holds even
 * when stocks are fragmented as they typically are in production after multiple mining sessions.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderHandoverFragmentedStockIntegrationTest {

  private static final int STACKS_PER_MATERIAL = 12;
  private static final double ASLARITE_REQUIRED = 1.8;
  private static final double OURATITE_REQUIRED = 5.7;

  @Autowired private JobOrderHandoverService jobOrderHandoverService;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private UserRepository userRepository;

  @Autowired
  private de.greluc.krt.profit.basetool.backend.repository.SquadronRepository squadronRepository;

  @Autowired private TransactionTemplate transactionTemplate;

  private record Fixture(
      UUID jobOrderId,
      List<UUID> aslariteInvIds,
      List<UUID> ouratiteInvIds,
      List<Double> aslariteAmounts,
      List<Double> ouratiteAmounts) {}

  private Fixture prepareFixture() {
    return transactionTemplate.execute(
        status -> {
          User user = new User();
          user.setId(UUID.randomUUID());
          user.setUsername("logistiker-" + UUID.randomUUID());
          userRepository.save(user);

          Location location = new Location();
          location.setName("Hub-" + UUID.randomUUID());
          location = locationRepository.save(location);

          Material aslarite = new Material();
          aslarite.setName("Aslarite-" + UUID.randomUUID());
          aslarite.setType(MaterialType.RAW);
          aslarite = materialRepository.save(aslarite);

          Material ouratite = new Material();
          ouratite.setName("Ouratite-" + UUID.randomUUID());
          ouratite.setType(MaterialType.RAW);
          ouratite = materialRepository.save(ouratite);

          JobOrder jobOrder =
              JobOrder.builder()
                  .responsibleOrgUnit(
                      squadronRepository
                          .findById(de.greluc.krt.profit.basetool.backend.model.Squadron.IRIDIUM_ID)
                          .orElseThrow())
                  .requestingOrgUnit(
                      squadronRepository
                          .findById(de.greluc.krt.profit.basetool.backend.model.Squadron.IRIDIUM_ID)
                          .orElseThrow())
                  .handle("requester")
                  .status(JobOrderStatus.OPEN)
                  .build();

          JobOrderMaterial m1 =
              JobOrderMaterial.builder()
                  .material(aslarite)
                  .minQuality(700)
                  .amount(ASLARITE_REQUIRED)
                  .build();
          JobOrderMaterial m2 =
              JobOrderMaterial.builder()
                  .material(ouratite)
                  .minQuality(800)
                  .amount(OURATITE_REQUIRED)
                  .build();
          jobOrder.addMaterial(m1);
          jobOrder.addMaterial(m2);
          jobOrder = jobOrderRepository.save(jobOrder);

          // Split each required amount across STACKS_PER_MATERIAL inventory rows
          // so the per-item handover loop iterates multiple times over the SAME
          // JobOrderMaterial — exactly the production constellation that triggered
          // the original 409.
          List<Double> aslariteAmounts = splitEvenly(ASLARITE_REQUIRED, STACKS_PER_MATERIAL);
          List<Double> ouratiteAmounts = splitEvenly(OURATITE_REQUIRED, STACKS_PER_MATERIAL);

          List<UUID> aslariteIds = new ArrayList<>();
          for (double a : aslariteAmounts) {
            InventoryItem inv = new InventoryItem();
            inv.setOwningOrgUnit(
                squadronRepository
                    .findById(de.greluc.krt.profit.basetool.backend.model.Squadron.IRIDIUM_ID)
                    .orElseThrow());
            inv.setUser(user);
            inv.setLocation(location);
            inv.setMaterial(aslarite);
            inv.setQuality(800);
            inv.setAmount(a);
            // Variante C (REQ-INV-027): earmark the entry's full amount to the order via a
            // job-order allocation slice (the scalar jobOrder column + soak mirror were dropped in
            // V218). The handover's pre-write guard requires the item to carry a slice for this
            // order.
            InventoryAllocations.addJobOrder(inv, jobOrder, a, false);
            inv = inventoryItemRepository.save(inv);
            aslariteIds.add(inv.getId());
          }

          List<UUID> ouratiteIds = new ArrayList<>();
          for (double a : ouratiteAmounts) {
            InventoryItem inv = new InventoryItem();
            inv.setOwningOrgUnit(
                squadronRepository
                    .findById(de.greluc.krt.profit.basetool.backend.model.Squadron.IRIDIUM_ID)
                    .orElseThrow());
            inv.setUser(user);
            inv.setLocation(location);
            inv.setMaterial(ouratite);
            inv.setQuality(900);
            inv.setAmount(a);
            // Variante C (REQ-INV-027): earmark the entry's full amount to the order via a
            // job-order allocation slice (the scalar jobOrder column + soak mirror were dropped in
            // V218). The handover's pre-write guard requires the item to carry a slice for this
            // order.
            InventoryAllocations.addJobOrder(inv, jobOrder, a, false);
            inv = inventoryItemRepository.save(inv);
            ouratiteIds.add(inv.getId());
          }

          return new Fixture(
              jobOrder.getId(), aslariteIds, ouratiteIds, aslariteAmounts, ouratiteAmounts);
        });
  }

  /**
   * Splits {@code total} into {@code n} non-equal positive doubles whose sum equals {@code total}.
   * We deliberately use varying chunk sizes to stress the per-iteration delete-vs-update branch of
   * the handover service (some chunks are fully consumed, some are partially consumed within the
   * same handover request).
   */
  private static List<Double> splitEvenly(double total, int n) {
    List<Double> result = new ArrayList<>(n);
    double remaining = total;
    for (int i = 0; i < n - 1; i++) {
      // alternating chunk sizes around total/n
      double chunk = (total / n) * (1.0 + (i % 2 == 0 ? -0.2 : 0.2));
      chunk = Math.round(chunk * 1000.0) / 1000.0;
      if (chunk <= 0) chunk = total / n;
      if (chunk > remaining - (n - 1 - i) * 0.001) {
        chunk = remaining - (n - 1 - i) * 0.001;
      }
      result.add(chunk);
      remaining -= chunk;
    }
    result.add(Math.round(remaining * 1000000.0) / 1000000.0);
    return result;
  }

  /**
   * Worst-case reproduction: two materials, each split across {@value #STACKS_PER_MATERIAL}
   * inventory rows, ALL handed over in one request → JobOrder must transition to {@link
   * JobOrderStatus#COMPLETED} without an {@code ObjectOptimisticLockingFailureException}.
   */
  @Test
  @WithMockUser(
      username = "logistiker",
      roles = {"KRT_MEMBER", "LOGISTIKER"})
  void handover_completesOrder_whenStockIsFragmentedAcrossManyInventoryItems() {
    Fixture f = prepareFixture();

    List<JobOrderHandoverItemCreateDto> items = new ArrayList<>();
    for (int i = 0; i < f.aslariteInvIds().size(); i++) {
      items.add(
          new JobOrderHandoverItemCreateDto(f.aslariteInvIds().get(i), f.aslariteAmounts().get(i)));
    }
    for (int i = 0; i < f.ouratiteInvIds().size(); i++) {
      items.add(
          new JobOrderHandoverItemCreateDto(f.ouratiteInvIds().get(i), f.ouratiteAmounts().get(i)));
    }

    JobOrderHandoverCreateDto dto =
        new JobOrderHandoverCreateDto(Instant.now(), "swing-by", "KARTELL", items);

    // When — must NOT throw ObjectOptimisticLockingFailureException even though
    // the same JobOrderMaterial is touched STACKS_PER_MATERIAL times in this request.
    jobOrderHandoverService.createHandover(f.jobOrderId(), dto);

    // Then — JobOrder COMPLETED, all materials at zero, all inventory rows fully consumed.
    transactionTemplate.executeWithoutResult(
        status -> {
          JobOrder reloaded = jobOrderRepository.findById(f.jobOrderId()).orElseThrow();
          assertEquals(
              JobOrderStatus.COMPLETED,
              reloaded.getStatus(),
              "JobOrder must be COMPLETED after fragmented-stock handover");
          for (JobOrderMaterial m : reloaded.getMaterials()) {
            assertTrue(
                m.getAmount() <= 0.0001,
                "All required materials must be fulfilled, but "
                    + m.getMaterial().getName()
                    + " is still "
                    + m.getAmount());
          }
          for (UUID id : f.aslariteInvIds()) {
            inventoryItemRepository
                .findById(id)
                .ifPresent(
                    inv ->
                        assertTrue(
                            inv.getAmount() <= 0.0001,
                            "Aslarite inventory row " + id + " should be (effectively) empty"));
          }
          for (UUID id : f.ouratiteInvIds()) {
            inventoryItemRepository
                .findById(id)
                .ifPresent(
                    inv ->
                        assertTrue(
                            inv.getAmount() <= 0.0001,
                            "Ouratite inventory row " + id + " should be (effectively) empty"));
          }
        });
  }
}
