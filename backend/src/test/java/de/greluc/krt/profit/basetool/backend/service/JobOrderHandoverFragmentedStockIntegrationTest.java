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
 * Integration test: a single handover by a Logistiker of two materials, each split across {@value
 * #STACKS_PER_MATERIAL} {@link InventoryItem} rows, completes the job order without an optimistic
 * lock conflict, although {@link JobOrderHandoverService#createHandover(UUID,
 * JobOrderHandoverCreateDto)} visits the same {@link JobOrderMaterial} several times.
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
            InventoryAllocations.addJobOrder(inv, jobOrder, a, false);
            inv = inventoryItemRepository.save(inv);
            ouratiteIds.add(inv.getId());
          }

          return new Fixture(
              jobOrder.getId(), aslariteIds, ouratiteIds, aslariteAmounts, ouratiteAmounts);
        });
  }

  /**
   * Splits {@code total} into {@code n} unequal positive parts summing to {@code total}, so a
   * handover consumes some rows fully and others partially.
   */
  private static List<Double> splitEvenly(double total, int n) {
    List<Double> result = new ArrayList<>(n);
    double remaining = total;
    for (int i = 0; i < n - 1; i++) {
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
          new JobOrderHandoverItemCreateDto(
              f.aslariteInvIds().get(i), f.aslariteAmounts().get(i), null));
    }
    for (int i = 0; i < f.ouratiteInvIds().size(); i++) {
      items.add(
          new JobOrderHandoverItemCreateDto(
              f.ouratiteInvIds().get(i), f.ouratiteAmounts().get(i), null));
    }

    JobOrderHandoverCreateDto dto =
        new JobOrderHandoverCreateDto(Instant.now(), "swing-by", "KARTELL", items);

    jobOrderHandoverService.createHandover(f.jobOrderId(), dto);

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
