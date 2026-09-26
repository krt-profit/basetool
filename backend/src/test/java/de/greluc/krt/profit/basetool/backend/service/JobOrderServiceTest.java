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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobOrderServiceTest {

  @Mock private JobOrderRepository jobOrderRepository;

  @Mock private MaterialRepository materialRepository;

  @Mock private InventoryItemRepository inventoryItemRepository;

  @Mock private de.greluc.krt.profit.basetool.backend.repository.UserRepository userRepository;

  @Mock private OrgUnitRepository orgUnitRepository;

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private SystemSettingService systemSettingService;

  @Mock private AuthHelperService authHelperService;

  @Mock private JobOrderMapper jobOrderMapper;

  @Mock private de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper squadronMapper;

  @Mock
  private de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper inventoryItemMapper;

  @Mock private MaterialClaimService materialClaimService;

  @Mock private JobOrderItemService jobOrderItemService;

  @Mock
  private de.greluc.krt.profit.basetool.backend.mapper.JobOrderItemHandoverMapper
      jobOrderItemHandoverMapper;

  @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

  @Mock private AuditService auditService;

  @InjectMocks private JobOrderOrgUnitResolver jobOrderOrgUnitResolver;
  @InjectMocks private JobOrderStockProjectionService jobOrderStockProjectionService;
  private JobOrderPriorityService jobOrderPriorityService;

  private JobOrderService jobOrderService;

  private JobOrderQueryService jobOrderQueryService;

  private Material material;
  private MaterialDto materialDto;
  private JobOrder jobOrder;
  private JobOrderDto baseJobOrderDto;
  private UUID orderId;
  private UUID materialId;
  private UUID responsibleOrgUnitId;
  private UUID requestingOrgUnitId;

  @BeforeEach
  void setUp() {
    jobOrderPriorityService =
        new JobOrderPriorityService(
            jobOrderRepository, auditService, jobOrderStockProjectionService);
    jobOrderService =
        new JobOrderService(
            jobOrderRepository,
            materialRepository,
            inventoryItemRepository,
            null,
            orgUnitRepository,
            jobOrderOrgUnitResolver,
            authHelperService,
            eventPublisher,
            materialClaimService,
            auditService,
            jobOrderItemService,
            jobOrderStockProjectionService,
            jobOrderPriorityService);
    jobOrderQueryService =
        new JobOrderQueryService(
            jobOrderRepository,
            materialRepository,
            inventoryItemRepository,
            ownerScopeService,
            jobOrderMapper,
            squadronMapper,
            jobOrderItemService,
            jobOrderStockProjectionService,
            null,
            inventoryItemMapper);
    orderId = UUID.randomUUID();
    materialId = UUID.randomUUID();

    responsibleOrgUnitId = UUID.randomUUID();
    requestingOrgUnitId = UUID.randomUUID();
    Squadron responsible = new Squadron();
    responsible.setId(responsibleOrgUnitId);
    responsible.setShorthand("RESP");
    responsible.setProfitEligible(true);
    Squadron requesting = new Squadron();
    requesting.setId(requestingOrgUnitId);
    requesting.setShorthand("Alpha");
    org.mockito.Mockito.lenient().when(authHelperService.isAuthenticated()).thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(orgUnitRepository.findById(responsibleOrgUnitId))
        .thenReturn(java.util.Optional.of(responsible));
    org.mockito.Mockito.lenient()
        .when(orgUnitRepository.findById(requestingOrgUnitId))
        .thenReturn(java.util.Optional.of(requesting));

    material = new Material();
    material.setId(materialId);
    material.setName("Gold");

    materialDto =
        new MaterialDto(
            materialId,
            "Gold",
            "RAW",
            "SCU",
            "Some desc",
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);

    jobOrder = new JobOrder();
    jobOrder.setId(orderId);
    Squadron alpha = new Squadron();
    alpha.setShorthand("Alpha");
    jobOrder.setRequestingOrgUnit(alpha);
    jobOrder.setResponsibleOrgUnit(alpha);
    jobOrder.setHandle("Tester");
    jobOrder.setPriority(1);

    JobOrderMaterial jom = new JobOrderMaterial();
    jom.setId(UUID.randomUUID());
    jom.setMaterial(material);
    jom.setMinQuality(100);
    jom.setAmount(50.0);
    jobOrder.addMaterial(jom);

    JobOrderMaterialDto jomDto =
        new JobOrderMaterialDto(
            jom.getId(), materialDto, 100, 50.0, null, java.util.List.of(), null, 1L);
    baseJobOrderDto =
        new JobOrderDto(
            orderId,
            1,
            null,
            null,
            "Tester",
            null,
            1,
            JobOrderStatus.OPEN,
            JobOrderType.MATERIAL,
            true,
            List.of(jomDto),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
  }

  @Test
  void getAllJobOrders_nonViewer_returnsEmptyPageWithoutQuerying() {
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);

    org.springframework.data.domain.Page<JobOrderDto> result =
        jobOrderQueryService.getAllJobOrders(
            null, null, org.springframework.data.domain.PageRequest.of(0, 20));

    assertTrue(result.isEmpty());
    verify(jobOrderRepository, never())
        .findScopedJobOrders(any(), anyBoolean(), any(), anyBoolean(), any(), any(), any());
    verify(ownerScopeService, never()).currentScopePredicate();
  }

  @Test
  void createJobOrder_ShouldCalculateStockAndReturnDto() {
    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 50.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(
            responsibleOrgUnitId, requestingOrgUnitId, "Tester", null, List.of(createMat), null);

    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>());
    when(jobOrderRepository.findMaxPriority()).thenReturn(Optional.of(0));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(jobOrderRepository.save(any(JobOrder.class)))
        .thenAnswer(
            i -> {
              JobOrder saved = i.getArgument(0);
              saved.setId(orderId);
              return saved;
            });
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(any(), any(), any()))
        .thenReturn(25.0);

    JobOrderDto result = jobOrderService.createJobOrder(createDto);

    assertNotNull(result);
    assertEquals(orderId, result.id());
    assertEquals(1, result.priority());
    assertEquals(1, result.materials().size());
    assertEquals(25L, result.materials().get(0).currentStock());

    verify(jobOrderRepository, times(2)).lockAllJobOrders();
    verify(jobOrderRepository).findMaxPriority();
    verify(jobOrderRepository).save(any(JobOrder.class));
    verify(auditService)
        .record(
            eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.JOB_ORDER_CREATED),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void createJobOrder_ShouldHonorMinQualityFromDto() {
    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 10.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(
            responsibleOrgUnitId, requestingOrgUnitId, "Tester", null, List.of(createMat), null);

    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>());
    when(jobOrderRepository.findMaxPriority()).thenReturn(Optional.of(0));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(jobOrderRepository.save(any(JobOrder.class)))
        .thenAnswer(
            i -> {
              JobOrder saved = i.getArgument(0);
              saved.setId(orderId);
              return saved;
            });
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(any(), any(), any()))
        .thenReturn(0.0);

    jobOrderService.createJobOrder(createDto);

    verify(jobOrderRepository)
        .save(
            argThat(
                jo ->
                    jo.getMaterials().stream()
                        .allMatch(m -> m.getMinQuality() != null && m.getMinQuality() == 650)));
  }

  @Test
  void createJobOrder_NullMinQuality_PersistsNull() {
    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, null, 10.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(
            responsibleOrgUnitId, requestingOrgUnitId, "Tester", null, List.of(createMat), null);

    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>());
    when(jobOrderRepository.findMaxPriority()).thenReturn(Optional.of(0));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(jobOrderRepository.save(any(JobOrder.class)))
        .thenAnswer(
            i -> {
              JobOrder saved = i.getArgument(0);
              saved.setId(orderId);
              return saved;
            });
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(any(), any(), any()))
        .thenReturn(0.0);

    jobOrderService.createJobOrder(createDto);

    verify(jobOrderRepository)
        .save(argThat(jo -> jo.getMaterials().stream().allMatch(m -> m.getMinQuality() == null)));
  }

  @Test
  void createJobOrder_PersistsComment() {
    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 10.0);

    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>());
    when(jobOrderRepository.findMaxPriority()).thenReturn(Optional.of(0));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(jobOrderRepository.save(any(JobOrder.class)))
        .thenAnswer(
            i -> {
              JobOrder saved = i.getArgument(0);
              saved.setId(orderId);
              return saved;
            });
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(any(), any(), any()))
        .thenReturn(0.0);

    jobOrderService.createJobOrder(
        new CreateJobOrderDto(
            responsibleOrgUnitId,
            requestingOrgUnitId,
            "Tester",
            "  Deliver fast  ",
            List.of(createMat),
            null));

    verify(jobOrderRepository).save(argThat(jo -> "Deliver fast".equals(jo.getComment())));

    jobOrderService.createJobOrder(
        new CreateJobOrderDto(
            responsibleOrgUnitId, requestingOrgUnitId, "Tester", "   ", List.of(createMat), null));

    verify(jobOrderRepository).save(argThat(jo -> jo.getComment() == null));
  }

  @Test
  void createJobOrder_MaterialNotFound_ShouldThrowException() {
    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 50.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(
            responsibleOrgUnitId, requestingOrgUnitId, "Tester", null, List.of(createMat), null);

    when(jobOrderRepository.findMaxPriority()).thenReturn(Optional.of(0));
    when(materialRepository.findById(materialId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> jobOrderService.createJobOrder(createDto));
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void createJobOrder_MissingResponsible_Throws() {
    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 5.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(null, requestingOrgUnitId, "Tester", null, List.of(createMat), null);

    assertThrows(BadRequestException.class, () -> jobOrderService.createJobOrder(createDto));
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void createJobOrder_NonProfitEligibleResponsible_Throws() {
    Squadron notEligible = new Squadron();
    notEligible.setId(responsibleOrgUnitId);
    notEligible.setShorthand("NOPE");
    notEligible.setProfitEligible(false);
    when(orgUnitRepository.findById(responsibleOrgUnitId)).thenReturn(Optional.of(notEligible));

    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 5.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(
            responsibleOrgUnitId, requestingOrgUnitId, "Tester", null, List.of(createMat), null);

    assertThrows(BadRequestException.class, () -> jobOrderService.createJobOrder(createDto));
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void updateJobOrderPriority_ShouldReorderAndNormalize() {
    JobOrder otherJob = new JobOrder();
    otherJob.setId(UUID.randomUUID());
    otherJob.setPriority(2);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.lockAllJobOrders())
        .thenReturn(new ArrayList<>(List.of(jobOrder, otherJob)));
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
            any(UUID.class), any(UUID.class), any()))
        .thenReturn(10.0);

    JobOrderDto result = jobOrderService.updateJobOrderPriority(orderId, 2);

    assertEquals(2, jobOrder.getPriority());
    assertEquals(1, otherJob.getPriority());
    assertNotNull(result);
  }

  @Test
  void updateJobOrderStatus_ToCompleted_ShouldRemovePriorityAndNormalize() {
    jobOrder.setPriority(3);
    jobOrder.setStatus(JobOrderStatus.IN_PROGRESS);
    jobOrder.setVersion(1L);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(jobOrder)));
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
            any(UUID.class), any(UUID.class), any()))
        .thenReturn(10.0);

    JobOrderDto result =
        jobOrderService.updateJobOrderStatus(
            orderId, new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, 1L));

    assertNull(jobOrder.getPriority());
    assertEquals(JobOrderStatus.COMPLETED, jobOrder.getStatus());
    assertNotNull(result);
    verify(jobOrderRepository).lockAllJobOrders();
    verify(inventoryItemRepository).deleteJobOrderAllocationsByJobOrder(orderId);
  }

  @Test
  void updateJobOrderStatus_ToRejected_ShouldRemovePriorityAndNormalizeAndUnlink() {
    jobOrder.setPriority(3);
    jobOrder.setStatus(JobOrderStatus.IN_PROGRESS);
    jobOrder.setVersion(1L);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(jobOrder)));
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
            any(UUID.class), any(UUID.class), any()))
        .thenReturn(10.0);

    JobOrderDto result =
        jobOrderService.updateJobOrderStatus(
            orderId, new UpdateJobOrderStatusDto(JobOrderStatus.REJECTED, 1L));

    assertNull(jobOrder.getPriority());
    assertEquals(JobOrderStatus.REJECTED, jobOrder.getStatus());
    assertNotNull(result);
    verify(jobOrderRepository).lockAllJobOrders();
    verify(inventoryItemRepository).deleteJobOrderAllocationsByJobOrder(orderId);
  }

  @Test
  void updateJobOrderStatus_ToInProgress_ShouldNotUnlink() {
    jobOrder.setPriority(2);
    jobOrder.setStatus(JobOrderStatus.OPEN);
    jobOrder.setVersion(1L);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
            any(UUID.class), any(UUID.class), any()))
        .thenReturn(10.0);

    JobOrderDto result =
        jobOrderService.updateJobOrderStatus(
            orderId, new UpdateJobOrderStatusDto(JobOrderStatus.IN_PROGRESS, 1L));

    assertEquals(JobOrderStatus.IN_PROGRESS, jobOrder.getStatus());
    assertNotNull(result);
    verify(inventoryItemRepository, never()).deleteJobOrderAllocationsByJobOrder(any());
  }

  @Test
  void updateJobOrderStatus_VersionMismatch_ShouldThrow409() {
    jobOrder.setVersion(5L);
    jobOrder.setStatus(JobOrderStatus.OPEN);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));

    assertThrows(
        org.springframework.orm.ObjectOptimisticLockingFailureException.class,
        () ->
            jobOrderService.updateJobOrderStatus(
                orderId, new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, 1L)));
    verify(jobOrderRepository, never()).save(any());
    verify(inventoryItemRepository, never()).deleteJobOrderAllocationsByJobOrder(any());
  }

  @Test
  void updateJobOrderStatus_ToActive_FromCompleted_ShouldAssignNewPriority() {
    jobOrder.setPriority(null);
    jobOrder.setStatus(JobOrderStatus.COMPLETED);
    jobOrder.setVersion(2L);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.findMaxPriority()).thenReturn(Optional.of(5));
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(jobOrder)));
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
            any(UUID.class), any(UUID.class), any()))
        .thenReturn(10.0);

    JobOrderDto result =
        jobOrderService.updateJobOrderStatus(
            orderId, new UpdateJobOrderStatusDto(JobOrderStatus.OPEN, 2L));

    assertEquals(1, jobOrder.getPriority());
    assertEquals(JobOrderStatus.OPEN, jobOrder.getStatus());
    assertNotNull(result);
  }

  @Test
  void updateJobOrderPriority_CompletedJobOrder_ShouldThrowException() {
    jobOrder.setPriority(null);
    jobOrder.setStatus(JobOrderStatus.COMPLETED);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));

    assertThrows(
        BadRequestException.class,
        () -> {
          jobOrderService.updateJobOrderPriority(orderId, 2);
        });
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void deleteJobOrder_ShouldLockAndNormalize() {
    jobOrder.setPriority(3);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(jobOrder)));

    jobOrderService.deleteJobOrder(orderId);

    verify(jobOrderRepository, times(2)).lockAllJobOrders();
    verify(jobOrderRepository).delete(jobOrder);
  }

  @Test
  void updateJobOrder_OptimisticLockingFailure_ShouldThrowException() {
    jobOrder.setVersion(2L);
    CreateJobOrderMaterialDto updateMat = new CreateJobOrderMaterialDto(materialId, 650, 50.0);
    CreateJobOrderDto updateDto =
        new CreateJobOrderDto(null, null, "Tester", null, List.of(updateMat), 1L);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));

    assertThrows(
        org.springframework.orm.ObjectOptimisticLockingFailureException.class,
        () -> {
          jobOrderService.updateJobOrder(orderId, updateDto);
        });
    verify(jobOrderRepository, never()).saveAndFlush(any(JobOrder.class));
  }

  @Test
  void updateJobOrder_RetargetsRequesting_AndIgnoresResponsible() {
    Squadron responsibleOriginal = new Squadron();
    responsibleOriginal.setId(UUID.randomUUID());
    responsibleOriginal.setShorthand("RESP");
    jobOrder.setResponsibleOrgUnit(responsibleOriginal);

    Squadron requestingOriginal = new Squadron();
    requestingOriginal.setId(UUID.randomUUID());
    requestingOriginal.setShorthand("REQ");
    jobOrder.setRequestingOrgUnit(requestingOriginal);

    UUID bravoId = UUID.randomUUID();
    Squadron bravo = new Squadron();
    bravo.setId(bravoId);
    bravo.setShorthand("Bravo");

    CreateJobOrderMaterialDto updateMat = new CreateJobOrderMaterialDto(materialId, 650, 50.0);
    CreateJobOrderDto updateDto =
        new CreateJobOrderDto(UUID.randomUUID(), bravoId, "Tester", null, List.of(updateMat), null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(orgUnitRepository.findById(bravoId)).thenReturn(Optional.of(bravo));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(jobOrderRepository.saveAndFlush(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);

    jobOrderService.updateJobOrder(orderId, updateDto);

    assertSame(responsibleOriginal, jobOrder.getResponsibleOrgUnit());
    assertNotNull(jobOrder.getRequestingOrgUnit());
    assertEquals("Bravo", jobOrder.getRequestingOrgUnit().getShorthand());
  }

  @Test
  void reassignResponsibleOrgUnit_Admin_MovesToProfitEligibleTarget() {
    Squadron current = new Squadron();
    current.setId(UUID.randomUUID());
    current.setShorthand("CUR");
    jobOrder.setResponsibleOrgUnit(current);

    UUID targetId = UUID.randomUUID();
    SpecialCommand target = new SpecialCommand();
    target.setId(targetId);
    target.setShorthand("SK");
    target.setProfitEligible(true);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(orgUnitRepository.findById(targetId)).thenReturn(Optional.of(target));
    when(authHelperService.isAdmin()).thenReturn(true);
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(materialClaimService.getClaimBucketsForOrder(any(JobOrder.class)))
        .thenReturn(java.util.List.of());

    jobOrderService.reassignResponsibleOrgUnit(orderId, targetId);

    assertSame(target, jobOrder.getResponsibleOrgUnit());
  }

  @Test
  void reassignResponsibleOrgUnit_RejectsNonProfitEligibleTarget() {
    UUID targetId = UUID.randomUUID();
    Squadron target = new Squadron();
    target.setId(targetId);
    target.setProfitEligible(false);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(orgUnitRepository.findById(targetId)).thenReturn(Optional.of(target));

    assertThrows(
        BadRequestException.class,
        () -> jobOrderService.reassignResponsibleOrgUnit(orderId, targetId));
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void updateJobOrder_ShouldUpdateFieldsAndUnlinkRemovedMaterials() {
    UUID newMaterialId = UUID.randomUUID();
    Material newMaterial = new Material();
    newMaterial.setId(newMaterialId);

    UUID betaId = UUID.randomUUID();
    Squadron beta = new Squadron();
    beta.setId(betaId);
    beta.setShorthand("Beta");

    CreateJobOrderMaterialDto updateMat = new CreateJobOrderMaterialDto(newMaterialId, 650, 50.0);
    CreateJobOrderDto updateDto =
        new CreateJobOrderDto(null, betaId, "NewTester", null, List.of(updateMat), null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(orgUnitRepository.findById(betaId)).thenReturn(Optional.of(beta));
    when(materialRepository.findById(newMaterialId)).thenReturn(Optional.of(newMaterial));
    when(jobOrderRepository.saveAndFlush(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);

    jobOrderService.updateJobOrder(orderId, updateDto);

    assertNotNull(jobOrder.getRequestingOrgUnit());
    assertEquals("Beta", jobOrder.getRequestingOrgUnit().getShorthand());
    assertEquals("NewTester", jobOrder.getHandle());

    verify(inventoryItemRepository)
        .deleteJobOrderAllocationsByJobOrderAndMaterial(orderId, materialId);

    verify(jobOrderRepository).saveAndFlush(jobOrder);
  }

  @Test
  void updateJobOrder_flushesSoReturnedVersionIsFresh() {
    UUID betaId = UUID.randomUUID();
    Squadron beta = new Squadron();
    beta.setId(betaId);
    beta.setShorthand("Beta");
    CreateJobOrderMaterialDto updateMat = new CreateJobOrderMaterialDto(materialId, 650, 50.0);
    CreateJobOrderDto updateDto =
        new CreateJobOrderDto(null, betaId, "Tester", null, List.of(updateMat), null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(orgUnitRepository.findById(betaId)).thenReturn(Optional.of(beta));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(jobOrderRepository.saveAndFlush(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);

    jobOrderService.updateJobOrder(orderId, updateDto);

    verify(jobOrderRepository).saveAndFlush(jobOrder);
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void updateJobOrderAsRequester_publishesEventAndAudits() {
    CreateJobOrderMaterialDto updateMat = new CreateJobOrderMaterialDto(materialId, 650, 50.0);
    CreateJobOrderDto updateDto =
        new CreateJobOrderDto(null, null, null, "requester note", List.of(updateMat), null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(jobOrderRepository.saveAndFlush(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);

    jobOrderService.updateJobOrderAsRequester(orderId, updateDto);

    verify(eventPublisher)
        .publishEvent(
            any(de.greluc.krt.profit.basetool.backend.event.JobOrderUpdatedByRequesterEvent.class));
    verify(auditService)
        .record(
            eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.JOB_ORDER_UPDATED),
            eq(orderId),
            any(),
            any(),
            any());
  }

  @Test
  void updateJobOrderAsRequester_frozenOnceDelivered_throws400() {
    jobOrder.getHandovers().add(new de.greluc.krt.profit.basetool.backend.model.JobOrderHandover());
    CreateJobOrderMaterialDto updateMat = new CreateJobOrderMaterialDto(materialId, 650, 50.0);
    CreateJobOrderDto updateDto =
        new CreateJobOrderDto(null, null, null, "note", List.of(updateMat), null);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));

    assertThrows(
        BadRequestException.class,
        () -> jobOrderService.updateJobOrderAsRequester(orderId, updateDto));
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void getRequestedJobOrders_emptyMembership_returnsEmptyPageWithoutHittingRepo() {
    when(ownerScopeService.currentDirectMembershipOrgUnitIds()).thenReturn(java.util.Set.of());

    org.springframework.data.domain.Page<JobOrderDto> page =
        jobOrderQueryService.getRequestedJobOrders(
            null, org.springframework.data.domain.PageRequest.of(0, 20));

    assertTrue(page.isEmpty(), "no memberships -> empty page");
    verify(jobOrderRepository, never()).findRequestedOrders(any(), any(), any());
  }

  @Test
  void getRequestedJobOrders_nullStatuses_defaultsToAllStatuses_scopedToDirectMembership() {
    UUID requestingUnitId = UUID.randomUUID();
    when(ownerScopeService.currentDirectMembershipOrgUnitIds())
        .thenReturn(java.util.Set.of(requestingUnitId));
    when(jobOrderRepository.findRequestedOrders(
            any(), eq(java.util.Set.of(requestingUnitId)), any()))
        .thenReturn(org.springframework.data.domain.Page.empty());

    jobOrderQueryService.getRequestedJobOrders(
        null, org.springframework.data.domain.PageRequest.of(0, 20));

    org.mockito.ArgumentCaptor<List<JobOrderStatus>> statusesCaptor =
        org.mockito.ArgumentCaptor.captor();
    verify(jobOrderRepository)
        .findRequestedOrders(
            statusesCaptor.capture(), eq(java.util.Set.of(requestingUnitId)), any());
    assertEquals(
        JobOrderStatus.values().length,
        statusesCaptor.getValue().size(),
        "null status filter expands to every JobOrderStatus");
  }

  @Test
  void updateItemJobOrderAsRequester_nonItemOrder_throws400() {
    JobOrder materialOrder = new JobOrder();
    materialOrder.setId(orderId);
    materialOrder.setType(JobOrderType.MATERIAL);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(materialOrder));

    de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto dto =
        new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto(
            null, null, "note", null, List.of(), null);

    assertThrows(
        BadRequestException.class,
        () -> jobOrderService.updateItemJobOrderAsRequester(orderId, dto));
    verify(eventPublisher, never()).publishEvent(any());
    verify(jobOrderRepository, never()).saveAndFlush(any(JobOrder.class));
  }

  @Test
  void updateItemJobOrderAsRequester_frozenOnceItemDelivered_throws400() {
    JobOrder itemOrder = new JobOrder();
    itemOrder.setId(orderId);
    itemOrder.setType(JobOrderType.ITEM);
    itemOrder.setVersion(1L);
    itemOrder
        .getItemHandovers()
        .add(new de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover());
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(itemOrder));

    de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto dto =
        new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto(
            null, null, "note", null, List.of(), 1L);

    assertThrows(
        BadRequestException.class,
        () -> jobOrderService.updateItemJobOrderAsRequester(orderId, dto));
    verify(eventPublisher, never()).publishEvent(any());
    verify(jobOrderRepository, never()).saveAndFlush(any(JobOrder.class));
  }

  @Test
  void updateItemJobOrderAsRequester_rebuildsUnlinksRemovedMaterialNotifiesAndAudits() {
    JobOrder itemOrder = new JobOrder();
    itemOrder.setId(orderId);
    itemOrder.setType(JobOrderType.ITEM);
    itemOrder.setVersion(1L);
    itemOrder.setHandle("Tester");
    Squadron responsibleUnit = new Squadron();
    responsibleUnit.setId(UUID.randomUUID());
    responsibleUnit.setShorthand("RESP");
    Squadron requestingUnit = new Squadron();
    requestingUnit.setId(UUID.randomUUID());
    requestingUnit.setShorthand("REQ");
    itemOrder.setResponsibleOrgUnit(responsibleUnit);
    itemOrder.setRequestingOrgUnit(requestingUnit);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(itemOrder));
    when(jobOrderItemService.buildItemLine(any()))
        .thenAnswer(inv -> new de.greluc.krt.profit.basetool.backend.model.JobOrderItem());
    UUID keptMaterial = UUID.randomUUID();
    UUID removedMaterial = UUID.randomUUID();
    when(jobOrderItemService.requiredMaterialIds(itemOrder))
        .thenReturn(java.util.Set.of(keptMaterial, removedMaterial))
        .thenReturn(java.util.Set.of(keptMaterial));
    when(jobOrderMapper.toDto(itemOrder)).thenReturn(baseJobOrderDto);
    when(jobOrderItemService.toItemDtos(itemOrder)).thenReturn(List.of());
    when(jobOrderItemService.aggregateMaterials(itemOrder)).thenReturn(List.of());

    de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto dto =
        new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto(
            null,
            null,
            "requester item note",
            null,
            List.of(
                new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                    null, UUID.randomUUID(), UUID.randomUUID(), 1, List.of(), 1, null)),
            1L);

    jobOrderService.updateItemJobOrderAsRequester(orderId, dto);

    org.mockito.InOrder inOrder =
        org.mockito.Mockito.inOrder(jobOrderRepository, inventoryItemRepository);
    inOrder.verify(jobOrderRepository).saveAndFlush(itemOrder);
    inOrder
        .verify(inventoryItemRepository)
        .deleteJobOrderAllocationsByJobOrderAndMaterial(orderId, removedMaterial);
    verify(inventoryItemRepository, never())
        .deleteJobOrderAllocationsByJobOrderAndMaterial(orderId, keptMaterial);
    verify(materialClaimService).withdrawOrphanedClaimsWithinTransaction(itemOrder);
    verify(eventPublisher)
        .publishEvent(
            any(de.greluc.krt.profit.basetool.backend.event.JobOrderUpdatedByRequesterEvent.class));
    verify(auditService)
        .record(
            eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.JOB_ORDER_ITEM_UPDATED),
            eq(orderId),
            any(),
            any(),
            any());
  }

  @Test
  void updateItemJobOrderAsRequester_rebuildsUnlinksRemovedGameItemNotifiesAndAudits() {
    JobOrder itemOrder = new JobOrder();
    itemOrder.setId(orderId);
    itemOrder.setType(JobOrderType.ITEM);
    itemOrder.setVersion(1L);
    itemOrder.setHandle("Tester");
    Squadron responsibleUnit = new Squadron();
    responsibleUnit.setId(UUID.randomUUID());
    responsibleUnit.setShorthand("RESP");
    Squadron requestingUnit = new Squadron();
    requestingUnit.setId(UUID.randomUUID());
    requestingUnit.setShorthand("REQ");
    itemOrder.setResponsibleOrgUnit(responsibleUnit);
    itemOrder.setRequestingOrgUnit(requestingUnit);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(itemOrder));
    when(jobOrderItemService.buildItemLine(any()))
        .thenAnswer(inv -> new de.greluc.krt.profit.basetool.backend.model.JobOrderItem());
    UUID keptGameItem = UUID.randomUUID();
    UUID removedGameItem = UUID.randomUUID();
    when(jobOrderItemService.requiredGameItemIds(itemOrder))
        .thenReturn(java.util.Set.of(keptGameItem, removedGameItem))
        .thenReturn(java.util.Set.of(keptGameItem));
    when(jobOrderMapper.toDto(itemOrder)).thenReturn(baseJobOrderDto);
    when(jobOrderItemService.toItemDtos(itemOrder)).thenReturn(List.of());
    when(jobOrderItemService.aggregateMaterials(itemOrder)).thenReturn(List.of());

    de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto dto =
        new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto(
            null,
            null,
            "requester item note",
            null,
            List.of(
                new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                    null, UUID.randomUUID(), UUID.randomUUID(), 1, List.of(), 1, null)),
            1L);

    jobOrderService.updateItemJobOrderAsRequester(orderId, dto);

    org.mockito.InOrder inOrder =
        org.mockito.Mockito.inOrder(jobOrderRepository, inventoryItemRepository);
    inOrder.verify(jobOrderRepository).saveAndFlush(itemOrder);
    inOrder
        .verify(inventoryItemRepository)
        .deleteJobOrderAllocationsByJobOrderAndGameItem(orderId, removedGameItem);
    verify(inventoryItemRepository, times(1))
        .deleteJobOrderAllocationsByJobOrderAndGameItem(any(), any());
    verify(inventoryItemRepository, never())
        .deleteJobOrderAllocationsByJobOrderAndGameItem(orderId, keptGameItem);
    verify(inventoryItemRepository, never())
        .deleteJobOrderAllocationsByJobOrderAndMaterial(any(), any());
    verify(materialClaimService).withdrawOrphanedClaimsWithinTransaction(itemOrder);
    verify(eventPublisher)
        .publishEvent(
            any(de.greluc.krt.profit.basetool.backend.event.JobOrderUpdatedByRequesterEvent.class));
    verify(auditService)
        .record(
            eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.JOB_ORDER_ITEM_UPDATED),
            eq(orderId),
            any(),
            any(),
            any());
  }

  @Test
  void
      completeJobOrderWithinTransaction_ShouldFlushBeforeLockQuery_ToAvoidOptimisticLockConflict() {
    jobOrder.setStatus(JobOrderStatus.OPEN);
    jobOrder.setPriority(1);
    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(jobOrder)));

    assertDoesNotThrow(() -> jobOrderService.completeJobOrderWithinTransaction(jobOrder));

    var inOrder = inOrder(jobOrderRepository);
    inOrder.verify(jobOrderRepository).flush();
    inOrder.verify(jobOrderRepository).lockAllJobOrders();

    assertEquals(JobOrderStatus.COMPLETED, jobOrder.getStatus());
    assertNull(jobOrder.getPriority());
    verify(inventoryItemRepository).deleteJobOrderAllocationsByJobOrder(orderId);
  }

  @Test
  void completeJobOrderWithinTransaction_ShouldNotNormalize_WhenAlreadyTerminal() {
    jobOrder.setStatus(JobOrderStatus.COMPLETED);
    jobOrder.setPriority(null);

    assertDoesNotThrow(() -> jobOrderService.completeJobOrderWithinTransaction(jobOrder));

    verify(jobOrderRepository, never()).flush();
    verify(jobOrderRepository, never()).lockAllJobOrders();
    verify(inventoryItemRepository, never()).deleteJobOrderAllocationsByJobOrder(any());
  }

  @Test
  void getInventoryItemsForJobOrderMaterial_ShouldReturnMappedDtos() {
    de.greluc.krt.profit.basetool.backend.model.InventoryItem item =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    item.setId(UUID.randomUUID());
    item.setAmount(10.0);

    InventoryItemDto itemDto =
        new InventoryItemDto(
            item.getId(),
            null,
            null,
            null,
            null,
            100,
            10.0,
            false,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null,
            null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(inventoryItemRepository.findByJobOrderIdAndMaterialId(orderId, materialId))
        .thenReturn(List.of(item));
    when(inventoryItemMapper.toDto(item)).thenReturn(itemDto);

    List<InventoryItemDto> result =
        jobOrderQueryService.getInventoryItemsForJobOrderMaterial(orderId, materialId);

    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals(itemDto.id(), result.get(0).id());
    verify(jobOrderRepository).findById(orderId);
    verify(materialRepository).findById(materialId);
    verify(inventoryItemRepository).findByJobOrderIdAndMaterialId(orderId, materialId);
    verify(inventoryItemMapper).toDto(item);
  }

  @Test
  void unlinkMaterial_ShouldCallUnlinkAndRemoveMaterialFromJobOrder() {
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);

    jobOrderService.unlinkMaterial(orderId, materialId);

    verify(inventoryItemRepository)
        .deleteJobOrderAllocationsByJobOrderAndMaterial(orderId, materialId);
    verify(jobOrderRepository).save(jobOrder);
    assertTrue(
        jobOrder.getMaterials().isEmpty(), "Material should have been removed from job order");
  }

  @Test
  void unlinkMaterial_WhenJobOrderNotFound_ShouldThrowNotFound() {
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.empty());

    NotFoundException ex =
        assertThrows(
            NotFoundException.class, () -> jobOrderService.unlinkMaterial(orderId, materialId));
    verify(inventoryItemRepository, never())
        .deleteJobOrderAllocationsByJobOrderAndMaterial(any(), any());
  }

  @Test
  void unlinkMaterial_WhenMaterialNotLinked_ShouldThrowNotFound() {
    UUID otherMaterialId = UUID.randomUUID();
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));

    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> jobOrderService.unlinkMaterial(orderId, otherMaterialId));
    verify(inventoryItemRepository, never())
        .deleteJobOrderAllocationsByJobOrderAndMaterial(any(), any());
  }

  @Test
  void unlinkInventoryItem_ShouldDropTheOrdersAllocationSlice() {
    UUID inventoryItemId = UUID.randomUUID();
    de.greluc.krt.profit.basetool.backend.model.InventoryItem item =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    item.setId(inventoryItemId);
    item.setAmount(10.0);
    InventoryAllocations.addJobOrder(item, jobOrder, 10.0, false);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(item));

    jobOrderService.unlinkInventoryItem(orderId, inventoryItemId);

    assertTrue(
        item.getJobOrderAllocations().stream()
            .noneMatch(a -> a.getJobOrder() != null && a.getJobOrder().getId().equals(orderId)),
        "the order's allocation slice should be removed after unlinking");
    verify(jobOrderRepository).findById(orderId);
    verify(inventoryItemRepository).findById(inventoryItemId);
  }

  @Test
  void unlinkInventoryItem_WhenJobOrderNotFound_ShouldThrowNotFound() {
    UUID inventoryItemId = UUID.randomUUID();
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.empty());

    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> jobOrderService.unlinkInventoryItem(orderId, inventoryItemId));
    verify(inventoryItemRepository, never()).findById(any());
  }

  @Test
  void unlinkInventoryItem_WhenInventoryItemNotFound_ShouldThrowNotFound() {
    UUID inventoryItemId = UUID.randomUUID();
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.empty());

    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> jobOrderService.unlinkInventoryItem(orderId, inventoryItemId));
  }

  @Test
  void unlinkInventoryItem_WhenItemNotLinkedToOrder_ShouldThrowNotFound() {
    UUID inventoryItemId = UUID.randomUUID();
    UUID otherOrderId = UUID.randomUUID();
    JobOrder otherOrder = new JobOrder();
    otherOrder.setId(otherOrderId);

    de.greluc.krt.profit.basetool.backend.model.InventoryItem item =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    item.setId(inventoryItemId);
    item.setAmount(10.0);
    InventoryAllocations.addJobOrder(item, otherOrder, 10.0, false);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(item));

    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> jobOrderService.unlinkInventoryItem(orderId, inventoryItemId));
  }

  @Test
  void
      getInventoryItemsForJobOrderMaterial_ShouldReturnItemsSortedByOwnerAscQualityDescLocationAscAmountDesc() {
    de.greluc.krt.profit.basetool.backend.model.InventoryItem i1 =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    i1.setId(UUID.randomUUID());
    de.greluc.krt.profit.basetool.backend.model.InventoryItem i2 =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    i2.setId(UUID.randomUUID());
    de.greluc.krt.profit.basetool.backend.model.InventoryItem i3 =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    i3.setId(UUID.randomUUID());
    de.greluc.krt.profit.basetool.backend.model.InventoryItem i4 =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    i4.setId(UUID.randomUUID());

    UserReferenceDto userAlpha =
        new UserReferenceDto(UUID.randomUUID(), "alpha", "Alpha", "Alpha", 1);
    UserReferenceDto userBeta = new UserReferenceDto(UUID.randomUUID(), "beta", "Beta", "Beta", 2);
    LocationReferenceDto locA = new LocationReferenceDto(UUID.randomUUID(), "ArcCorp");
    LocationReferenceDto locB = new LocationReferenceDto(UUID.randomUUID(), "Baijini");

    InventoryItemDto dto1 =
        new InventoryItemDto(
            i1.getId(),
            userAlpha,
            null,
            null,
            locB,
            80,
            5.0,
            false,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null,
            null);
    InventoryItemDto dto2 =
        new InventoryItemDto(
            i2.getId(),
            userAlpha,
            null,
            null,
            locA,
            90,
            3.0,
            false,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null,
            null);
    InventoryItemDto dto3 =
        new InventoryItemDto(
            i3.getId(),
            userBeta,
            null,
            null,
            locA,
            70,
            20.0,
            false,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null,
            null);
    InventoryItemDto dto4 =
        new InventoryItemDto(
            i4.getId(),
            userAlpha,
            null,
            null,
            locA,
            80,
            10.0,
            false,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null,
            null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(inventoryItemRepository.findByJobOrderIdAndMaterialId(orderId, materialId))
        .thenReturn(List.of(i1, i2, i3, i4));
    when(inventoryItemMapper.toDto(i1)).thenReturn(dto1);
    when(inventoryItemMapper.toDto(i2)).thenReturn(dto2);
    when(inventoryItemMapper.toDto(i3)).thenReturn(dto3);
    when(inventoryItemMapper.toDto(i4)).thenReturn(dto4);

    List<InventoryItemDto> result =
        jobOrderQueryService.getInventoryItemsForJobOrderMaterial(orderId, materialId);

    assertNotNull(result);
    assertEquals(4, result.size());
    assertEquals(dto2.id(), result.get(0).id(), "1st: Alpha, quality 90, ArcCorp");
    assertEquals(dto4.id(), result.get(1).id(), "2nd: Alpha, quality 80, ArcCorp, amount 10");
    assertEquals(dto1.id(), result.get(2).id(), "3rd: Alpha, quality 80, Baijini, amount 5");
    assertEquals(dto3.id(), result.get(3).id(), "4th: Beta, quality 70, ArcCorp");
  }

  @Test
  void getOrphanedLinkedInventoryReturnsOnlyLinksWhoseMaterialIsNotRequired() {
    UUID orderId = UUID.randomUUID();
    UUID requiredMatId = UUID.randomUUID();
    UUID orphanMatId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.backend.model.JobOrder order =
        new de.greluc.krt.profit.basetool.backend.model.JobOrder();
    order.setId(orderId);

    de.greluc.krt.profit.basetool.backend.model.Material requiredMat =
        new de.greluc.krt.profit.basetool.backend.model.Material();
    requiredMat.setId(requiredMatId);
    de.greluc.krt.profit.basetool.backend.model.Material orphanMat =
        new de.greluc.krt.profit.basetool.backend.model.Material();
    orphanMat.setId(orphanMatId);

    de.greluc.krt.profit.basetool.backend.model.InventoryItem requiredItem =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    requiredItem.setId(UUID.randomUUID());
    requiredItem.setMaterial(requiredMat);
    de.greluc.krt.profit.basetool.backend.model.InventoryItem orphanItem =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    orphanItem.setId(UUID.randomUUID());
    orphanItem.setMaterial(orphanMat);

    InventoryItemDto orphanDto =
        new InventoryItemDto(
            orphanItem.getId(),
            null,
            null,
            null,
            null,
            661,
            0.18,
            false,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null,
            null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(jobOrderItemService.requiredMaterialIds(order))
        .thenReturn(java.util.Set.of(requiredMatId));
    when(inventoryItemRepository.findByJobOrderIdOrdered(orderId))
        .thenReturn(List.of(requiredItem, orphanItem));
    when(inventoryItemMapper.toDto(orphanItem)).thenReturn(orphanDto);

    List<InventoryItemDto> result = jobOrderQueryService.getOrphanedLinkedInventory(orderId);

    assertEquals(1, result.size(), "only the non-required (orphaned) link is returned");
    assertEquals(orphanDto.id(), result.get(0).id());
    verify(inventoryItemMapper, never()).toDto(requiredItem);
  }

  @Test
  void getOrphanedLinkedInventoryReturnsOnlyItemEarmarksWhoseGameItemIsNotRequested() {
    UUID orderId = UUID.randomUUID();
    UUID requestedGameItemId = UUID.randomUUID();
    UUID orphanGameItemId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.backend.model.JobOrder order =
        new de.greluc.krt.profit.basetool.backend.model.JobOrder();
    order.setId(orderId);
    order.setType(JobOrderType.ITEM);

    de.greluc.krt.profit.basetool.backend.model.GameItem requestedGameItem =
        new de.greluc.krt.profit.basetool.backend.model.GameItem();
    requestedGameItem.setId(requestedGameItemId);
    de.greluc.krt.profit.basetool.backend.model.GameItem orphanGameItem =
        new de.greluc.krt.profit.basetool.backend.model.GameItem();
    orphanGameItem.setId(orphanGameItemId);

    de.greluc.krt.profit.basetool.backend.model.InventoryItem requestedRow =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    requestedRow.setId(UUID.randomUUID());
    requestedRow.setGameItem(requestedGameItem);
    de.greluc.krt.profit.basetool.backend.model.InventoryItem orphanRow =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    orphanRow.setId(UUID.randomUUID());
    orphanRow.setGameItem(orphanGameItem);

    InventoryItemDto orphanDto =
        new InventoryItemDto(
            orphanRow.getId(),
            null,
            null,
            null,
            null,
            null,
            2.0,
            false,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null,
            null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(jobOrderItemService.requiredGameItemIds(order))
        .thenReturn(java.util.Set.of(requestedGameItemId));
    when(inventoryItemRepository.findGameItemRowsByJobOrderIdOrdered(orderId))
        .thenReturn(List.of(requestedRow, orphanRow));
    when(inventoryItemMapper.toDto(orphanRow)).thenReturn(orphanDto);

    List<InventoryItemDto> result = jobOrderQueryService.getOrphanedLinkedInventory(orderId);

    assertEquals(1, result.size(), "only the no-longer-requested item earmark is flagged");
    assertEquals(orphanDto.id(), result.get(0).id());
    verify(inventoryItemMapper, never()).toDto(requestedRow);
  }

  @Test
  void updateJobOrderStatus_openToCompleted_recordsJobOrderCompletedNotStatusChanged() {
    jobOrder.setPriority(3);
    jobOrder.setStatus(JobOrderStatus.OPEN);
    jobOrder.setVersion(1L);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(jobOrder)));
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
            any(UUID.class), any(UUID.class), any()))
        .thenReturn(10.0);

    jobOrderService.updateJobOrderStatus(
        orderId, new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, 1L));

    verify(auditService)
        .record(
            eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.JOB_ORDER_COMPLETED),
            eq(orderId),
            any(),
            any(),
            argThat(d -> d != null && d.toString().equals("from=OPEN autoCompleted=false")));
    verify(auditService, never())
        .record(
            eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.JOB_ORDER_STATUS_CHANGED),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void updateJobOrderStatus_completedToCompleted_recordsStatusChangedOnly() {
    jobOrder.setPriority(null);
    jobOrder.setStatus(JobOrderStatus.COMPLETED);
    jobOrder.setVersion(1L);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
            any(UUID.class), any(UUID.class), any()))
        .thenReturn(10.0);

    jobOrderService.updateJobOrderStatus(
        orderId, new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, 1L));

    verify(auditService)
        .record(
            eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.JOB_ORDER_STATUS_CHANGED),
            eq(orderId),
            any(),
            any(),
            argThat(d -> d != null && d.toString().equals("from=COMPLETED to=COMPLETED")));
    verify(auditService, never())
        .record(
            eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.JOB_ORDER_COMPLETED),
            any(),
            any(),
            any(),
            any());
    verify(inventoryItemRepository, never()).deleteJobOrderAllocationsByJobOrder(any());
  }

  @Test
  void reassignResponsibleOrgUnit_NonAdmin_EscalatesOwnSquadronToSk_succeeds() {
    UUID currentId = UUID.randomUUID();
    Squadron current = new Squadron();
    current.setId(currentId);
    current.setShorthand("CUR");
    jobOrder.setResponsibleOrgUnit(current);

    UUID targetId = UUID.randomUUID();
    SpecialCommand target = new SpecialCommand();
    target.setId(targetId);
    target.setShorthand("SK");
    target.setProfitEligible(true);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(orgUnitRepository.findById(targetId)).thenReturn(Optional.of(target));
    when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.canEditOrgUnit(currentId)).thenReturn(true);
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(materialClaimService.getClaimBucketsForOrder(any(JobOrder.class)))
        .thenReturn(java.util.List.of());

    jobOrderService.reassignResponsibleOrgUnit(orderId, targetId);

    assertSame(target, jobOrder.getResponsibleOrgUnit());
    verify(jobOrderRepository).save(jobOrder);
  }

  @Test
  void reassignResponsibleOrgUnit_NonAdmin_ToAnotherSquadron_throwsAccessDenied() {
    UUID currentId = UUID.randomUUID();
    Squadron current = new Squadron();
    current.setId(currentId);
    current.setShorthand("CUR");
    jobOrder.setResponsibleOrgUnit(current);

    UUID targetId = UUID.randomUUID();
    Squadron target = new Squadron();
    target.setId(targetId);
    target.setShorthand("OTHER");
    target.setProfitEligible(true);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(orgUnitRepository.findById(targetId)).thenReturn(Optional.of(target));
    when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.canEditOrgUnit(currentId)).thenReturn(true);

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> jobOrderService.reassignResponsibleOrgUnit(orderId, targetId));
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void reassignResponsibleOrgUnit_NonAdmin_OnSkResponsibleOrder_throwsAccessDenied() {
    UUID currentId = UUID.randomUUID();
    SpecialCommand current = new SpecialCommand();
    current.setId(currentId);
    current.setShorthand("SKCUR");
    jobOrder.setResponsibleOrgUnit(current);

    UUID targetId = UUID.randomUUID();
    SpecialCommand target = new SpecialCommand();
    target.setId(targetId);
    target.setShorthand("SK");
    target.setProfitEligible(true);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(orgUnitRepository.findById(targetId)).thenReturn(Optional.of(target));
    when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.canEditOrgUnit(currentId)).thenReturn(true);

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> jobOrderService.reassignResponsibleOrgUnit(orderId, targetId));
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void reassignResponsibleOrgUnit_NonAdmin_CannotEditCurrentSquadron_throwsAccessDenied() {
    UUID currentId = UUID.randomUUID();
    Squadron current = new Squadron();
    current.setId(currentId);
    current.setShorthand("FOREIGN");
    jobOrder.setResponsibleOrgUnit(current);

    UUID targetId = UUID.randomUUID();
    SpecialCommand target = new SpecialCommand();
    target.setId(targetId);
    target.setShorthand("SK");
    target.setProfitEligible(true);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(orgUnitRepository.findById(targetId)).thenReturn(Optional.of(target));
    when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.canEditOrgUnit(currentId)).thenReturn(false);

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> jobOrderService.reassignResponsibleOrgUnit(orderId, targetId));
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @org.junit.jupiter.api.Nested
  class UpdateItemJobOrderTests {

    private de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto oneLine(
        Long version) {
      return new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto(
          null,
          null,
          "edited",
          null,
          List.of(
              new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                  null, UUID.randomUUID(), UUID.randomUUID(), 1, List.of(), 1, null)),
          version);
    }

    private JobOrder itemOrder() {
      JobOrder order = new JobOrder();
      order.setId(orderId);
      order.setType(JobOrderType.ITEM);
      order.setStatus(de.greluc.krt.profit.basetool.backend.model.JobOrderStatus.OPEN);
      order.setVersion(1L);
      return order;
    }

    @Test
    void nonItemOrder_throwsBadRequest() {
      JobOrder material = new JobOrder();
      material.setId(orderId);
      material.setType(JobOrderType.MATERIAL);
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(material));

      assertThrows(
          de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
          () -> jobOrderService.updateItemJobOrder(orderId, oneLine(null)));
      verify(jobOrderItemService, never()).buildItemLine(any());
    }

    @Test
    void orderWithItemHandover_throwsBadRequest() {
      JobOrder order = itemOrder();
      order
          .getItemHandovers()
          .add(new de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover());
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));

      assertThrows(
          de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
          () -> jobOrderService.updateItemJobOrder(orderId, oneLine(null)));
      verify(jobOrderItemService, never()).buildItemLine(any());
    }

    @Test
    void versionMismatch_throws409() {
      JobOrder order = itemOrder();
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));

      assertThrows(
          org.springframework.orm.ObjectOptimisticLockingFailureException.class,
          () -> jobOrderService.updateItemJobOrder(orderId, oneLine(99L)));
      verify(jobOrderItemService, never()).buildItemLine(any());
    }

    @Test
    void happyPath_rebuildsLines_wiresSubAssembly_andWithdrawsOrphanClaims() {
      JobOrder order = itemOrder();
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));
      when(jobOrderRepository.save(any(JobOrder.class))).thenAnswer(inv -> inv.getArgument(0));
      when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
      when(jobOrderItemService.toItemDtos(any())).thenReturn(List.of());
      when(jobOrderItemService.aggregateMaterials(any())).thenReturn(List.of());
      when(jobOrderItemService.buildItemLine(any()))
          .thenAnswer(inv -> new de.greluc.krt.profit.basetool.backend.model.JobOrderItem());

      de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto dto =
          new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto(
              null,
              null,
              "edited",
              null,
              List.of(
                  new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                      null, UUID.randomUUID(), UUID.randomUUID(), 1, List.of(), 1, null),
                  new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                      null, UUID.randomUUID(), UUID.randomUUID(), 2, List.of(), 2, 1)),
              1L);

      jobOrderService.updateItemJobOrder(orderId, dto);

      verify(jobOrderItemService, times(2)).buildItemLine(any());
      assertEquals(2, order.getItems().size(), "the two new lines replace the old set");
      java.util.List<de.greluc.krt.profit.basetool.backend.model.JobOrderItem> items =
          new java.util.ArrayList<>(order.getItems());
      assertTrue(
          items.stream().anyMatch(i -> i.getParentItem() != null),
          "the adopted line keeps its sub-assembly parent");
      verify(materialClaimService).withdrawOrphanedClaimsWithinTransaction(order);
      assertEquals("edited", order.getHandle());
    }

    @Test
    void matchedLine_isReDerivedInPlace_soBookedProductionSurvives() {
      JobOrder order = itemOrder();
      de.greluc.krt.profit.basetool.backend.model.JobOrderItem existing =
          new de.greluc.krt.profit.basetool.backend.model.JobOrderItem();
      java.util.UUID lineId = UUID.randomUUID();
      existing.setId(lineId);
      existing.setAmount(10);
      existing.setManufacturedAmount(6);
      order.addItem(existing);

      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));
      when(jobOrderRepository.save(any(JobOrder.class))).thenAnswer(inv -> inv.getArgument(0));
      when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
      when(jobOrderItemService.toItemDtos(any())).thenReturn(List.of());
      when(jobOrderItemService.aggregateMaterials(any())).thenReturn(List.of());

      de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto dto =
          new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto(
              null,
              null,
              "edited",
              null,
              List.of(
                  new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                      lineId, UUID.randomUUID(), UUID.randomUUID(), 12, List.of(), 1, null)),
              1L);

      jobOrderService.updateItemJobOrder(orderId, dto);

      verify(jobOrderItemService, never()).buildItemLine(any());
      verify(jobOrderItemService).applyItemLine(eq(existing), any());
      assertEquals(1, order.getItems().size());
      assertSame(existing, order.getItems().iterator().next(), "the persisted line is reused");
      assertEquals(6, existing.getManufacturedAmount(), "booked production survives the edit");
    }

    @Test
    void droppingALineWithBookedProduction_throwsBadRequest() {
      JobOrder order = itemOrder();
      de.greluc.krt.profit.basetool.backend.model.JobOrderItem produced =
          new de.greluc.krt.profit.basetool.backend.model.JobOrderItem();
      produced.setId(UUID.randomUUID());
      produced.setAmount(4);
      produced.setManufacturedAmount(4);
      order.addItem(produced);
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));
      when(jobOrderItemService.buildItemLine(any()))
          .thenAnswer(inv -> new de.greluc.krt.profit.basetool.backend.model.JobOrderItem());

      assertThrows(
          de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
          () -> jobOrderService.updateItemJobOrder(orderId, oneLine(1L)));
      verify(jobOrderRepository, never()).save(any(JobOrder.class));
    }

    @Test
    void twoPayloadLinesClaimingTheSameExistingLine_throwsBadRequest() {
      JobOrder order = itemOrder();
      de.greluc.krt.profit.basetool.backend.model.JobOrderItem existing =
          new de.greluc.krt.profit.basetool.backend.model.JobOrderItem();
      java.util.UUID lineId = UUID.randomUUID();
      existing.setId(lineId);
      existing.setAmount(5);
      order.addItem(existing);
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));

      de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto dto =
          new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto(
              null,
              null,
              "edited",
              null,
              List.of(
                  new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                      lineId, UUID.randomUUID(), UUID.randomUUID(), 5, List.of(), 1, null),
                  new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                      lineId, UUID.randomUUID(), UUID.randomUUID(), 7, List.of(), 2, null)),
              1L);

      assertThrows(
          de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
          () -> jobOrderService.updateItemJobOrder(orderId, dto));
      verify(jobOrderRepository, never()).save(any(JobOrder.class));
    }

    @Test
    void loweringAmountBelowManufactured_throwsBadRequest() {
      JobOrder order = itemOrder();
      de.greluc.krt.profit.basetool.backend.model.JobOrderItem existing =
          new de.greluc.krt.profit.basetool.backend.model.JobOrderItem();
      java.util.UUID lineId = UUID.randomUUID();
      existing.setId(lineId);
      existing.setAmount(10);
      existing.setManufacturedAmount(6);
      order.addItem(existing);
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));

      de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto dto =
          new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto(
              null,
              null,
              "edited",
              null,
              List.of(
                  new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                      lineId, UUID.randomUUID(), UUID.randomUUID(), 3, List.of(), 1, null)),
              1L);

      assertThrows(
          de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
          () -> jobOrderService.updateItemJobOrder(orderId, dto));
      verify(jobOrderRepository, never()).save(any(JobOrder.class));
    }

    @Test
    void happyPath_enrichesAggregatedMaterialsWithCollectionStock() {
      JobOrder order = itemOrder();
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));
      when(jobOrderRepository.save(any(JobOrder.class))).thenAnswer(inv -> inv.getArgument(0));
      JobOrderDto itemBase =
          new JobOrderDto(
              orderId,
              1,
              null,
              null,
              "Tester",
              null,
              1,
              JobOrderStatus.OPEN,
              JobOrderType.ITEM,
              true,
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              Instant.now(),
              1L,
              null,
              false);
      when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(itemBase);
      when(jobOrderItemService.toItemDtos(any())).thenReturn(List.of());
      when(jobOrderItemService.aggregateMaterials(any()))
          .thenReturn(
              List.of(
                  new de.greluc.krt.profit.basetool.backend.model.dto.AggregatedMaterialDto(
                      materialDto,
                      de.greluc.krt.profit.basetool.backend.model.QualityRequirement.GOOD,
                      10.0,
                      null,
                      List.of(),
                      null)));
      when(jobOrderItemService.buildItemLine(any()))
          .thenAnswer(inv -> new de.greluc.krt.profit.basetool.backend.model.JobOrderItem());
      when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
              materialId, orderId, 650))
          .thenReturn(4.0);

      JobOrderDto result = jobOrderService.updateItemJobOrder(orderId, oneLine(1L));

      assertEquals(1, result.aggregatedMaterials().size());
      assertEquals(
          4.0,
          result.aggregatedMaterials().get(0).currentStock(),
          "GOOD bucket sums order-linked inventory at the 650 floor as collection progress");
    }
  }

  @org.junit.jupiter.api.Nested
  class UpdateBlueprintVariantCountingTests {

    private JobOrder itemOrder(boolean countWithVariants, Long version) {
      JobOrder order = new JobOrder();
      order.setId(orderId);
      order.setType(JobOrderType.ITEM);
      order.setStatus(JobOrderStatus.OPEN);
      order.setCountBlueprintsWithVariants(countWithVariants);
      order.setVersion(version);
      return order;
    }

    /** mapToDtoWithStock runs on the return path; stub the ITEM enrichment + mapper to no-ops. */
    private void stubItemMapping() {
      lenient().when(jobOrderItemService.toItemDtos(any())).thenReturn(List.of());
      lenient().when(jobOrderItemService.aggregateMaterials(any())).thenReturn(List.of());
      lenient()
          .when(jobOrderMapper.toDto(any(JobOrder.class)))
          .thenAnswer(
              inv -> {
                JobOrder o = inv.getArgument(0);
                return new JobOrderDto(
                    o.getId(),
                    1,
                    null,
                    null,
                    null,
                    null,
                    1,
                    JobOrderStatus.OPEN,
                    JobOrderType.ITEM,
                    o.isCountBlueprintsWithVariants(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Instant.now(),
                    o.getVersion(),
                    null,
                    false);
              });
    }

    @Test
    void notFound_throws() {
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> jobOrderService.updateBlueprintVariantCounting(orderId, false, 1L));
    }

    @Test
    void nonItemOrder_throwsBadRequest() {
      JobOrder material = new JobOrder();
      material.setId(orderId);
      material.setType(JobOrderType.MATERIAL);
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(material));

      assertThrows(
          BadRequestException.class,
          () -> jobOrderService.updateBlueprintVariantCounting(orderId, false, null));
      verify(jobOrderRepository, never()).saveAndFlush(any());
      verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void versionMismatch_throwsOptimisticLockingFailure() {
      JobOrder order = itemOrder(true, 5L);
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));

      assertThrows(
          org.springframework.orm.ObjectOptimisticLockingFailureException.class,
          () -> jobOrderService.updateBlueprintVariantCounting(orderId, false, 99L));
      verify(jobOrderRepository, never()).saveAndFlush(any());
      verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void togglesFlagOff_persistsAndAuditsWithBooleanDetailsOnly() {
      JobOrder order = itemOrder(true, 1L);
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));
      when(jobOrderRepository.saveAndFlush(order)).thenReturn(order);
      stubItemMapping();

      JobOrderDto result = jobOrderService.updateBlueprintVariantCounting(orderId, false, 1L);

      assertFalse(order.isCountBlueprintsWithVariants(), "the entity flag is flipped to off");
      assertFalse(result.countBlueprintsWithVariants(), "the returned DTO reflects the new mode");
      verify(jobOrderRepository).saveAndFlush(order);
      verify(auditService)
          .record(
              eq(
                  de.greluc.krt.profit.basetool.backend.model.AuditEventType
                      .JOB_ORDER_BLUEPRINT_COUNTING_CHANGED),
              eq(orderId),
              any(),
              any(),
              argThat(d -> d != null && d.toString().equals("countWithVariants=false")));
    }

    @Test
    void noOp_whenModeUnchanged_doesNotSaveOrAudit() {
      JobOrder order = itemOrder(true, 1L);
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));
      stubItemMapping();

      jobOrderService.updateBlueprintVariantCounting(orderId, true, 1L);

      verify(jobOrderRepository, never()).saveAndFlush(any());
      verify(auditService, never()).record(any(), any(), any(), any(), any());
    }
  }
}
