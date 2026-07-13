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
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocationSync;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reproduction of the JobOrder #40 handover bug: MEMBER (Logistiker) hands over the remaining
 * amounts of TWO materials in a single handover request. The handover must complete the JobOrder
 * without an {@code ObjectOptimisticLockingFailureException}.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderHandoverCompletionIntegrationTest {

  @Autowired private JobOrderHandoverService jobOrderHandoverService;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private UserRepository userRepository;

  @Autowired
  private de.greluc.krt.profit.basetool.backend.repository.SquadronRepository squadronRepository;

  @Autowired private TransactionTemplate transactionTemplate;

  private record Fixture(UUID jobOrderId, UUID invItem1Id, UUID invItem2Id) {}

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
              JobOrderMaterial.builder().material(aslarite).minQuality(700).amount(1.8).build();
          JobOrderMaterial m2 =
              JobOrderMaterial.builder().material(ouratite).minQuality(800).amount(5.7).build();
          jobOrder.addMaterial(m1);
          jobOrder.addMaterial(m2);
          jobOrder = jobOrderRepository.save(jobOrder);

          InventoryItem inv1 = new InventoryItem();

          inv1.setOwningOrgUnit(
              squadronRepository
                  .findById(de.greluc.krt.profit.basetool.backend.model.Squadron.IRIDIUM_ID)
                  .orElseThrow());
          inv1.setUser(user);
          inv1.setLocation(location);
          inv1.setMaterial(aslarite);
          inv1.setQuality(800);
          inv1.setAmount(1.835);
          inv1.setJobOrder(jobOrder);
          InventoryAllocationSync.mirrorScalars(inv1);
          inv1 = inventoryItemRepository.save(inv1);

          InventoryItem inv2 = new InventoryItem();

          inv2.setOwningOrgUnit(
              squadronRepository
                  .findById(de.greluc.krt.profit.basetool.backend.model.Squadron.IRIDIUM_ID)
                  .orElseThrow());
          inv2.setUser(user);
          inv2.setLocation(location);
          inv2.setMaterial(ouratite);
          inv2.setQuality(900);
          inv2.setAmount(5.730999999999999);
          inv2.setJobOrder(jobOrder);
          InventoryAllocationSync.mirrorScalars(inv2);
          inv2 = inventoryItemRepository.save(inv2);

          return new Fixture(jobOrder.getId(), inv1.getId(), inv2.getId());
        });
  }

  /**
   * Reproduces the live-log failure: handover that exactly covers the remaining amount of EVERY
   * required material must complete the JobOrder without an OL conflict.
   */
  @Test
  @WithMockUser(
      username = "logistiker",
      roles = {"KRT_MEMBER", "LOGISTIKER"})
  void handover_exactlyCoversAllRemainingMaterials_completesOrderWithoutOptimisticLockConflict() {
    Fixture f = prepareFixture();

    JobOrderHandoverCreateDto dto =
        new JobOrderHandoverCreateDto(
            Instant.now(),
            "swing-by",
            "KARTELL",
            List.of(
                new JobOrderHandoverItemCreateDto(f.invItem1Id(), 1.8),
                new JobOrderHandoverItemCreateDto(f.invItem2Id(), 5.7)));

    // When — must NOT throw ObjectOptimisticLockingFailureException
    jobOrderHandoverService.createHandover(f.jobOrderId(), dto);

    // Then — JobOrder must be COMPLETED, both inventory items reduced, both job order materials at
    // zero
    transactionTemplate.executeWithoutResult(
        status -> {
          JobOrder reloaded = jobOrderRepository.findById(f.jobOrderId()).orElseThrow();
          assertEquals(
              JobOrderStatus.COMPLETED,
              reloaded.getStatus(),
              "JobOrder must be COMPLETED after final handover");
          for (JobOrderMaterial m : reloaded.getMaterials()) {
            assertTrue(
                m.getAmount() <= 0.0001,
                "All required materials must be fulfilled, but "
                    + m.getMaterial().getName()
                    + " is still "
                    + m.getAmount());
          }
          InventoryItem inv1 = inventoryItemRepository.findById(f.invItem1Id()).orElseThrow();
          InventoryItem inv2 = inventoryItemRepository.findById(f.invItem2Id()).orElseThrow();
          assertEquals(0.035, inv1.getAmount(), 0.0001);
          assertEquals(0.030999999999999, inv2.getAmount(), 0.0001);
        });
  }
}
