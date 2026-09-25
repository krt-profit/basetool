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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Concurrency-sensitive coverage for {@link JobOrderService} priority/status transitions and the
 * priority-normalisation loop. CLAUDE.md flags these exact methods as bug-prone (optimistic locking
 * + bulk updates inside loops + {@code …WithinTransaction} pattern). Class-level coverage was 77%
 * line / 67% branch with the methods below at 0-50%.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderServicePriorityAndStatusTest {

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private SquadronRepository squadronRepository;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private JobOrderMapper jobOrderMapper;
  @Mock private de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper squadronMapper;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private MaterialClaimService materialClaimService;

  @Mock private AuditService auditService;

  @InjectMocks private JobOrderStockProjectionService jobOrderStockProjectionService;
  private JobOrderPriorityService jobOrderPriorityService;

  private JobOrderService service;

  private static final UUID ORDER_ID = UUID.randomUUID();

  @BeforeEach
  void stubMapper() {
    jobOrderPriorityService =
        new JobOrderPriorityService(
            jobOrderRepository, auditService, jobOrderStockProjectionService);
    service =
        new JobOrderService(
            jobOrderRepository,
            materialRepository,
            inventoryItemRepository,
            null,
            null,
            null,
            null,
            null,
            materialClaimService,
            auditService,
            null,
            jobOrderStockProjectionService,
            jobOrderPriorityService);
    lenient()
        .when(jobOrderMapper.toDto(any(JobOrder.class)))
        .thenAnswer(
            inv -> {
              JobOrder o = inv.getArgument(0);
              return new JobOrderDto(
                  o.getId(),
                  o.getDisplayId(),
                  null,
                  null,
                  o.getHandle(),
                  o.getComment(),
                  o.getPriority(),
                  o.getStatus(),
                  JobOrderType.MATERIAL,
                  o.isCountBlueprintsWithVariants(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  null,
                  o.getVersion(),
                  null,
                  false);
            });

    Squadron testSquadron = new Squadron();
    testSquadron.setShorthand("Alpha");
    lenient().when(ownerScopeService.currentSquadron()).thenReturn(Optional.of(testSquadron));
  }

  @Nested
  class UpdateJobOrderStatusTests {

    @Test
    void notFound_throws() {
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () ->
              service.updateJobOrderStatus(
                  ORDER_ID, new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, 1L)));
    }

    @Test
    void versionMismatch_throwsOptimisticLockingFailure() {
      JobOrder o = newOrder(JobOrderStatus.OPEN, 1, 5L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () ->
              service.updateJobOrderStatus(
                  ORDER_ID, new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, 99L)));
      verify(jobOrderRepository, never()).save(any());
    }

    @Test
    void activeToTerminal_clearsPriority_andUnlinksInventory() {
      JobOrder o = newOrder(JobOrderStatus.OPEN, 3, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(jobOrderRepository.save(o)).thenReturn(o);
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(List.of());

      service.updateJobOrderStatus(
          ORDER_ID, new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, 1L));

      assertNull(o.getPriority(), "terminal status (COMPLETED) must clear priority");
      assertEquals(JobOrderStatus.COMPLETED, o.getStatus());
      verify(inventoryItemRepository).deleteJobOrderAllocationsByJobOrder(ORDER_ID);
      verify(jobOrderRepository).save(o);
      verify(jobOrderRepository).flush();
      verify(jobOrderRepository).lockAllJobOrders();
    }

    @Test
    void activeToRejected_clearsPriority_andUnlinks() {
      JobOrder o = newOrder(JobOrderStatus.IN_PROGRESS, 2, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(jobOrderRepository.save(o)).thenReturn(o);
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(List.of());

      service.updateJobOrderStatus(
          ORDER_ID, new UpdateJobOrderStatusDto(JobOrderStatus.REJECTED, 1L));

      assertNull(o.getPriority());
      assertEquals(JobOrderStatus.REJECTED, o.getStatus());
      verify(inventoryItemRepository).deleteJobOrderAllocationsByJobOrder(ORDER_ID);
    }

    @Test
    void completedToActive_restoresPriorityViaMaxPlusOne() {
      JobOrder o = newOrder(JobOrderStatus.COMPLETED, null, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(List.of());
      when(jobOrderRepository.findMaxPriority()).thenReturn(Optional.of(5));
      when(jobOrderRepository.save(o)).thenReturn(o);

      service.updateJobOrderStatus(ORDER_ID, new UpdateJobOrderStatusDto(JobOrderStatus.OPEN, 1L));

      assertEquals(JobOrderStatus.OPEN, o.getStatus());
      assertEquals(
          Integer.valueOf(6),
          o.getPriority(),
          "un-completion sets priority = findMaxPriority + 1 = 6; "
              + "normalize doesn't see this order in lockAllJobOrders stub");
      verify(inventoryItemRepository, never()).deleteJobOrderAllocationsByJobOrder(any());
      verify(jobOrderRepository, times(2)).lockAllJobOrders();
    }

    @Test
    void completedToActive_findMaxPriorityEmpty_defaultsToZeroPlus1() {
      JobOrder o = newOrder(JobOrderStatus.COMPLETED, null, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(List.of());
      when(jobOrderRepository.findMaxPriority()).thenReturn(Optional.empty());
      when(jobOrderRepository.save(o)).thenReturn(o);

      service.updateJobOrderStatus(ORDER_ID, new UpdateJobOrderStatusDto(JobOrderStatus.OPEN, 1L));

      assertEquals(
          Integer.valueOf(1),
          o.getPriority(),
          "empty findMaxPriority -> 0+1 = 1 (normalise is a no-op on empty stub list)");
    }

    @Test
    void terminalToTerminal_doesNotNormalize() {
      JobOrder o = newOrder(JobOrderStatus.COMPLETED, null, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(jobOrderRepository.save(o)).thenReturn(o);

      service.updateJobOrderStatus(
          ORDER_ID, new UpdateJobOrderStatusDto(JobOrderStatus.REJECTED, 1L));

      assertEquals(JobOrderStatus.REJECTED, o.getStatus());
      assertNull(o.getPriority());
      verify(jobOrderRepository, never()).lockAllJobOrders();
      verify(inventoryItemRepository, never()).deleteJobOrderAllocationsByJobOrder(any());
    }

    @Test
    void activeToActive_doesNotChangePriority() {
      JobOrder o = newOrder(JobOrderStatus.OPEN, 3, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(jobOrderRepository.save(o)).thenReturn(o);

      service.updateJobOrderStatus(
          ORDER_ID, new UpdateJobOrderStatusDto(JobOrderStatus.IN_PROGRESS, 1L));

      assertEquals(JobOrderStatus.IN_PROGRESS, o.getStatus());
      assertEquals(
          Integer.valueOf(3), o.getPriority(), "active-to-active transition preserves priority");
      verify(jobOrderRepository, never()).lockAllJobOrders();
    }

    @Test
    void nullVersionInDto_bypassesOptimisticCheck() {
      JobOrder o = newOrder(JobOrderStatus.OPEN, 1, 5L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(jobOrderRepository.save(o)).thenReturn(o);

      service.updateJobOrderStatus(
          ORDER_ID, new UpdateJobOrderStatusDto(JobOrderStatus.IN_PROGRESS, null));

      verify(jobOrderRepository).save(o);
    }
  }

  @Nested
  class UpdateJobOrderPriorityTests {

    @Test
    void notFound_throws() {
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.updateJobOrderPriority(ORDER_ID, 2));
    }

    @Test
    void completedOrder_throwsBadRequest() {
      JobOrder completed = newOrder(JobOrderStatus.COMPLETED, null, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(completed));

      assertThrows(BadRequestException.class, () -> service.updateJobOrderPriority(ORDER_ID, 2));
    }

    @Test
    void samePriority_isNoOpButStillNormalizes() {
      JobOrder target = newOrder(JobOrderStatus.OPEN, 3, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(target));
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(List.of(target));

      service.updateJobOrderPriority(ORDER_ID, 3);

      assertEquals(Integer.valueOf(1), target.getPriority(), "single-order normalisation -> 1");
    }

    @Test
    void moveDown_reordersOtherEntries() {
      JobOrder a = newOrderInstance(JobOrderStatus.OPEN, 1, "A");
      JobOrder b = newOrderInstance(JobOrderStatus.OPEN, 2, "B");
      JobOrder c = newOrderInstance(JobOrderStatus.OPEN, 3, "C");
      when(jobOrderRepository.findById(a.getId())).thenReturn(Optional.of(a));
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(a, b, c)));

      service.updateJobOrderPriority(a.getId(), 3);

      assertEquals(Integer.valueOf(3), a.getPriority());
      assertEquals(Integer.valueOf(1), b.getPriority());
      assertEquals(Integer.valueOf(2), c.getPriority());
    }

    @Test
    void newPriorityBelowZero_clampsToFront() {
      JobOrder a = newOrderInstance(JobOrderStatus.OPEN, 1, "A");
      JobOrder b = newOrderInstance(JobOrderStatus.OPEN, 2, "B");
      JobOrder c = newOrderInstance(JobOrderStatus.OPEN, 3, "C");
      when(jobOrderRepository.findById(c.getId())).thenReturn(Optional.of(c));
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(a, b, c)));

      service.updateJobOrderPriority(c.getId(), -5);

      assertEquals(Integer.valueOf(1), c.getPriority());
      assertEquals(Integer.valueOf(2), a.getPriority());
      assertEquals(Integer.valueOf(3), b.getPriority());
    }

    @Test
    void newPriorityAboveSize_clampsToBack() {
      JobOrder a = newOrderInstance(JobOrderStatus.OPEN, 1, "A");
      JobOrder b = newOrderInstance(JobOrderStatus.OPEN, 2, "B");
      when(jobOrderRepository.findById(a.getId())).thenReturn(Optional.of(a));
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(a, b)));

      service.updateJobOrderPriority(a.getId(), 99);

      assertEquals(Integer.valueOf(1), b.getPriority());
      assertEquals(Integer.valueOf(2), a.getPriority());
    }
  }

  @Nested
  class UpdateJobOrderTests {

    @Test
    void notFound_throws() {
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class, () -> service.updateJobOrder(ORDER_ID, newUpdateDto(1L)));
    }

    @Test
    void versionMismatch_throws() {
      JobOrder o = newOrder(JobOrderStatus.OPEN, 1, 7L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> service.updateJobOrder(ORDER_ID, newUpdateDto(3L)));
      verify(jobOrderRepository, never()).saveAndFlush(any());
    }

    @Test
    void removedMaterials_areUnlinkedFromInventory() {
      UUID xId = UUID.randomUUID();
      UUID yId = UUID.randomUUID();

      JobOrder o = newOrder(JobOrderStatus.OPEN, 1, 1L);
      JobOrderMaterial existingMat =
          JobOrderMaterial.builder()
              .material(materialWithId(xId))
              .minQuality(700)
              .amount(10.0)
              .build();
      o.getMaterials().add(existingMat);

      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(materialRepository.findById(yId)).thenReturn(Optional.of(materialWithId(yId)));
      when(jobOrderRepository.saveAndFlush(o)).thenReturn(o);

      CreateJobOrderDto dto =
          new CreateJobOrderDto(
              null, null, "OP-1", null, List.of(new CreateJobOrderMaterialDto(yId, 650, 5.0)), 1L);

      service.updateJobOrder(ORDER_ID, dto);

      verify(inventoryItemRepository).deleteJobOrderAllocationsByJobOrderAndMaterial(ORDER_ID, xId);
    }

    @Test
    void newMaterialNotFound_throws() {
      JobOrder o = newOrder(JobOrderStatus.OPEN, 1, 1L);
      UUID missingMat = UUID.randomUUID();
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(materialRepository.findById(missingMat)).thenReturn(Optional.empty());

      CreateJobOrderDto dto =
          new CreateJobOrderDto(
              null,
              null,
              "OP-1",
              null,
              List.of(new CreateJobOrderMaterialDto(missingMat, 650, 5.0)),
              1L);

      assertThrows(NotFoundException.class, () -> service.updateJobOrder(ORDER_ID, dto));
    }

    @Test
    void nullVersion_bypassesOptimisticCheck() {
      JobOrder o = newOrder(JobOrderStatus.OPEN, 1, 5L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(jobOrderRepository.saveAndFlush(o)).thenReturn(o);

      CreateJobOrderDto dto = new CreateJobOrderDto(null, null, "b", null, List.of(), null);

      service.updateJobOrder(ORDER_ID, dto);

      verify(jobOrderRepository).saveAndFlush(o);
    }
  }

  @Nested
  class DeleteJobOrderTests {

    @Test
    void notFound_throws() {
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.deleteJobOrder(ORDER_ID));
    }

    @Test
    void activeOrderWithPriority_isDeletedAndNormalised() {
      JobOrder o = newOrder(JobOrderStatus.OPEN, 3, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(List.of());

      service.deleteJobOrder(ORDER_ID);

      verify(jobOrderRepository).delete(o);
      verify(jobOrderRepository).flush();
      verify(jobOrderRepository, times(2)).lockAllJobOrders();
    }

    @Test
    void terminalOrderWithNullPriority_isDeletedWithoutNormalize() {
      JobOrder o = newOrder(JobOrderStatus.COMPLETED, null, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));

      service.deleteJobOrder(ORDER_ID);

      verify(jobOrderRepository).delete(o);
      verify(jobOrderRepository, times(1)).lockAllJobOrders();
    }
  }

  @Nested
  class UnlinkTests {

    @Test
    void unlinkMaterial_notFoundJobOrder_throws() {
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class, () -> service.unlinkMaterial(ORDER_ID, UUID.randomUUID()));
    }

    @Test
    void unlinkMaterial_materialNotLinked_throws() {
      JobOrder o = newOrder(JobOrderStatus.OPEN, 1, 1L);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));

      UUID missingMatId = UUID.randomUUID();
      assertThrows(NotFoundException.class, () -> service.unlinkMaterial(ORDER_ID, missingMatId));
    }

    @Test
    void unlinkMaterial_happyPath_removesAndUnlinks() {
      UUID matId = UUID.randomUUID();
      JobOrder o = newOrder(JobOrderStatus.OPEN, 1, 1L);
      JobOrderMaterial mat =
          JobOrderMaterial.builder()
              .material(materialWithId(matId))
              .minQuality(700)
              .amount(10.0)
              .build();
      o.getMaterials().add(mat);

      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));

      service.unlinkMaterial(ORDER_ID, matId);

      verify(inventoryItemRepository)
          .deleteJobOrderAllocationsByJobOrderAndMaterial(ORDER_ID, matId);
      assertTrue(
          o.getMaterials().isEmpty(),
          "the JobOrderMaterial association must be removed from the in-memory set too");
      verify(jobOrderRepository).save(o);
    }

    @Test
    void unlinkInventoryItem_notFoundJobOrder_throws() {
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class, () -> service.unlinkInventoryItem(ORDER_ID, UUID.randomUUID()));
    }
  }

  @Nested
  class CompleteWithinTransactionTests {

    @Test
    void alreadyTerminal_skipsAllSideEffects() {
      JobOrder o = newOrder(JobOrderStatus.COMPLETED, null, 1L);

      service.completeJobOrderWithinTransaction(o);

      assertEquals(JobOrderStatus.COMPLETED, o.getStatus());
      verify(jobOrderRepository, never()).flush();
      verify(jobOrderRepository, never()).lockAllJobOrders();
      verify(inventoryItemRepository, never()).deleteJobOrderAllocationsByJobOrder(any());
    }

    @Test
    void activeWithPriority_clearsPriorityAndRunsSideEffects() {
      JobOrder o = newOrder(JobOrderStatus.OPEN, 5, 1L);
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(List.of());

      service.completeJobOrderWithinTransaction(o);

      assertEquals(JobOrderStatus.COMPLETED, o.getStatus());
      assertNull(o.getPriority(), "priority must be cleared");
      verify(jobOrderRepository).flush();
      verify(jobOrderRepository).lockAllJobOrders();
      verify(inventoryItemRepository).deleteJobOrderAllocationsByJobOrder(ORDER_ID);
    }

    @Test
    void activeWithNullPriority_runsSideEffects_butSkipsPriorityClear() {
      JobOrder o = newOrder(JobOrderStatus.IN_PROGRESS, null, 1L);
      when(jobOrderRepository.lockAllJobOrders()).thenReturn(List.of());

      service.completeJobOrderWithinTransaction(o);

      assertEquals(JobOrderStatus.COMPLETED, o.getStatus());
      assertNull(o.getPriority());
      verify(jobOrderRepository).flush();
      verify(inventoryItemRepository).deleteJobOrderAllocationsByJobOrder(ORDER_ID);
    }
  }

  @Nested
  class NormalizePrioritiesTests {

    @Test
    void equalPriorities_breaksTieByCreatedAt() {
      JobOrder earlier = new JobOrder();
      earlier.setId(UUID.randomUUID());
      earlier.setStatus(JobOrderStatus.OPEN);
      earlier.setPriority(1);
      earlier.setCreatedAt(Instant.parse("2026-07-10T00:00:00Z"));

      JobOrder later = new JobOrder();
      later.setId(UUID.randomUUID());
      later.setStatus(JobOrderStatus.OPEN);
      later.setPriority(1);
      later.setCreatedAt(Instant.parse("2026-07-10T00:00:30Z"));

      when(jobOrderRepository.lockAllJobOrders()).thenReturn(List.of(later, earlier));

      jobOrderPriorityService.normalizePriorities();

      assertEquals(
          Integer.valueOf(1),
          earlier.getPriority(),
          "the earlier-createdAt order must win the contiguous slot 1");
      assertEquals(
          Integer.valueOf(2),
          later.getPriority(),
          "the later-createdAt order is re-packed to slot 2");
    }
  }

  private JobOrder newOrder(JobOrderStatus status, Integer priority, Long version) {
    JobOrder o = new JobOrder();
    o.setId(ORDER_ID);
    o.setStatus(status);
    o.setPriority(priority);
    o.setVersion(version);
    o.setCreatedAt(Instant.now());
    o.setMaterials(new HashSet<>());
    o.setAssignees(new HashSet<>());
    return o;
  }

  private JobOrder newOrderInstance(JobOrderStatus status, Integer priority, String handle) {
    JobOrder o = new JobOrder();
    o.setId(UUID.randomUUID());
    o.setStatus(status);
    o.setPriority(priority);
    o.setHandle(handle);
    o.setVersion(1L);
    o.setCreatedAt(Instant.now().plusSeconds(priority == null ? 0 : priority));
    o.setMaterials(new HashSet<>());
    o.setAssignees(new HashSet<>());
    return o;
  }

  private Material materialWithId(UUID id) {
    Material m = new Material();
    m.setId(id);
    m.setName("mat-" + id);
    return m;
  }

  private CreateJobOrderDto newUpdateDto(Long version) {
    return new CreateJobOrderDto(null, null, "OP-X", null, List.of(), version);
  }
}
