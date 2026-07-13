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
import org.springframework.test.util.ReflectionTestUtils;

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

  // The org-unit resolution, stock/claim DTO projection and priority queue were extracted to
  // JobOrderOrgUnitResolver / JobOrderStockProjectionService / JobOrderPriorityService (L2, #921);
  // JobOrderService now calls them. Mockito builds real instances from the same mocks, wired into
  // jobOrderService via reflection in setUp() (Mockito does not inject one @InjectMocks into
  // another; the priority service also gets the real projection chained in), so the
  // create/update/delete/read paths keep exercising the real logic.
  @InjectMocks private JobOrderOrgUnitResolver jobOrderOrgUnitResolver;
  @InjectMocks private JobOrderStockProjectionService jobOrderStockProjectionService;
  @InjectMocks private JobOrderPriorityService jobOrderPriorityService;

  @InjectMocks private JobOrderService jobOrderService;

  // Read/write split (#14): the list/detail/picker reads moved to JobOrderQueryService, built from
  // the same mocks with the real stock projection wired in below, so the moved read paths keep
  // exercising the real logic from this fixture.
  @InjectMocks private JobOrderQueryService jobOrderQueryService;

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
    ReflectionTestUtils.setField(
        jobOrderService, "jobOrderOrgUnitResolver", jobOrderOrgUnitResolver);
    ReflectionTestUtils.setField(
        jobOrderService, "jobOrderStockProjectionService", jobOrderStockProjectionService);
    ReflectionTestUtils.setField(
        jobOrderPriorityService, "jobOrderStockProjectionService", jobOrderStockProjectionService);
    ReflectionTestUtils.setField(
        jobOrderService, "jobOrderPriorityService", jobOrderPriorityService);
    ReflectionTestUtils.setField(
        jobOrderQueryService, "jobOrderStockProjectionService", jobOrderStockProjectionService);
    orderId = UUID.randomUUID();
    materialId = UUID.randomUUID();

    // Phase 2 org-unit stamping: createJobOrder resolves a profit-eligible responsible org unit and
    // a requesting org unit via OrgUnitRepository. Lenient defaults cover the authenticated happy
    // path — guest-path and error-path tests override isAuthenticated / the repo stubs as needed.
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
    // Fixtures stamp both org-unit refs explicitly (the responsible governs visibility from Phase 3
    // on; the requesting is the customer).
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
            false);
  }

  @Test
  void getAllJobOrders_nonViewer_returnsEmptyPageWithoutQuerying() {
    // A caller who may not view orders (non-admin, no profit-eligible membership) short-circuits to
    // an empty page — the scope predicate and the repository query are never reached, so the
    // SK-public union can never leak to them.
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);

    org.springframework.data.domain.Page<JobOrderDto> result =
        jobOrderQueryService.getAllJobOrders(
            null, null, org.springframework.data.domain.PageRequest.of(0, 20));

    assertTrue(result.isEmpty());
    verify(jobOrderRepository, never())
        .findScopedJobOrders(any(), any(), anyBoolean(), any(), any(), any());
    verify(ownerScopeService, never()).currentScopePredicate();
  }

  @Test
  void createJobOrder_ShouldCalculateStockAndReturnDto() {
    // Given
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

    // When
    JobOrderDto result = jobOrderService.createJobOrder(createDto);

    // Then
    assertNotNull(result);
    assertEquals(orderId, result.id());
    assertEquals(1, result.priority());
    assertEquals(1, result.materials().size());
    assertEquals(25L, result.materials().get(0).currentStock());

    verify(jobOrderRepository, times(2)).lockAllJobOrders();
    verify(jobOrderRepository).findMaxPriority();
    verify(jobOrderRepository).save(any(JobOrder.class));
    // REQ-AUDIT-001: a material job-order create records exactly one JOB_ORDER_CREATED audit event.
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
    // Given — DTO carries 650 (the predefined value); the service must persist it verbatim (650),
    // not force a default.
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

    // When
    jobOrderService.createJobOrder(createDto);

    // Then — the saved JobOrder must carry minQuality == 650 (honored, not forced) on every
    // material.
    verify(jobOrderRepository)
        .save(
            argThat(
                jo ->
                    jo.getMaterials().stream()
                        .allMatch(m -> m.getMinQuality() != null && m.getMinQuality() == 650)));
  }

  @Test
  void createJobOrder_NullMinQuality_PersistsNull() {
    // Given — DTO carries a null minQuality ("Keine"); the service must persist null (no floor),
    // not coerce it to 650 or 0.
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

    // When
    jobOrderService.createJobOrder(createDto);

    // Then — every saved material's minQuality must be null. Use == null (not == 650) to avoid an
    // NPE unbox.
    verify(jobOrderRepository)
        .save(argThat(jo -> jo.getMaterials().stream().allMatch(m -> m.getMinQuality() == null)));
  }

  @Test
  void createJobOrder_PersistsComment() {
    // Given
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

    // When — comment with surrounding whitespace must be trimmed before persisting.
    jobOrderService.createJobOrder(
        new CreateJobOrderDto(
            responsibleOrgUnitId,
            requestingOrgUnitId,
            "Tester",
            "  Deliver fast  ",
            List.of(createMat),
            null));

    // Then — trimmed value persisted.
    verify(jobOrderRepository).save(argThat(jo -> "Deliver fast".equals(jo.getComment())));

    // When — a blank comment must normalise to null.
    jobOrderService.createJobOrder(
        new CreateJobOrderDto(
            responsibleOrgUnitId, requestingOrgUnitId, "Tester", "   ", List.of(createMat), null));

    // Then — null comment persisted.
    verify(jobOrderRepository).save(argThat(jo -> jo.getComment() == null));
  }

  @Test
  void createJobOrder_MaterialNotFound_ShouldThrowException() {
    // Given
    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 50.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(
            responsibleOrgUnitId, requestingOrgUnitId, "Tester", null, List.of(createMat), null);

    when(jobOrderRepository.findMaxPriority()).thenReturn(Optional.of(0));
    when(materialRepository.findById(materialId)).thenReturn(Optional.empty());

    // When/Then
    assertThrows(NotFoundException.class, () -> jobOrderService.createJobOrder(createDto));
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void createJobOrder_Guest_UnresolvableResponsible_FallsBackToIntakeSpecialCommand() {
    // Given — a guest (anonymous) creation whose responsible pick does not resolve to a known org
    // unit (here: an unknown id). The order falls back to the configured intake SK; the requesting
    // (customer) is honoured.
    UUID unknownResponsibleId = UUID.randomUUID();
    UUID intakeId = UUID.randomUUID();
    SpecialCommand intake = new SpecialCommand();
    intake.setId(intakeId);
    intake.setShorthand("INTK");

    when(authHelperService.isAuthenticated()).thenReturn(false);
    when(orgUnitRepository.findById(unknownResponsibleId)).thenReturn(Optional.empty());
    when(systemSettingService.getSettingValue("job_order.intake_special_command_id"))
        .thenReturn(Optional.of(intakeId.toString()));
    when(orgUnitRepository.findById(intakeId)).thenReturn(Optional.of(intake));

    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 5.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(
            unknownResponsibleId,
            requestingOrgUnitId,
            "anon-handle",
            null,
            List.of(createMat),
            null);

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

    // When
    jobOrderService.createJobOrder(createDto);

    // Then — responsible is the intake SK (unresolvable pick), requesting is honoured.
    verify(jobOrderRepository)
        .save(
            argThat(
                jo ->
                    jo.getResponsibleOrgUnit() == intake
                        && jo.getRequestingOrgUnit().getId().equals(requestingOrgUnitId)));
  }

  @Test
  void createJobOrder_Guest_HonorsProfitEligiblePick() {
    // Given — a guest creation that picks a *profit-eligible* responsible unit from the create
    // form's responsible picker. The pick is honoured verbatim (no intake-SK fallback) and the
    // intake SK setting is never consulted.
    UUID pickedId = UUID.randomUUID();
    Squadron picked = new Squadron();
    picked.setId(pickedId);
    picked.setShorthand("PROF");
    picked.setProfitEligible(true);

    when(authHelperService.isAuthenticated()).thenReturn(false);
    when(orgUnitRepository.findById(pickedId)).thenReturn(Optional.of(picked));

    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 5.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(
            pickedId, requestingOrgUnitId, "anon-handle", null, List.of(createMat), null);

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

    // When
    jobOrderService.createJobOrder(createDto);

    // Then — responsible is the guest's profit-eligible pick; the intake SK setting is not read.
    verify(jobOrderRepository).save(argThat(jo -> jo.getResponsibleOrgUnit() == picked));
    verify(systemSettingService, never()).getSettingValue("job_order.intake_special_command_id");
  }

  @Test
  void createJobOrder_Guest_NonProfitPick_FallsBackToIntakeSpecialCommand() {
    // Given — a guest creation that picks a resolvable but NON-profit-eligible responsible unit.
    // The
    // profit-eligibility guard rejects it (a guest may not direct work to a non-opted-in unit), so
    // the order falls back to the configured intake SK rather than 400-ing the public form.
    UUID nonProfitId = UUID.randomUUID();
    Squadron nonProfit = new Squadron();
    nonProfit.setId(nonProfitId);
    nonProfit.setShorthand("NOPF");
    nonProfit.setProfitEligible(false);
    UUID intakeId = UUID.randomUUID();
    SpecialCommand intake = new SpecialCommand();
    intake.setId(intakeId);
    intake.setShorthand("INTK");

    when(authHelperService.isAuthenticated()).thenReturn(false);
    when(orgUnitRepository.findById(nonProfitId)).thenReturn(Optional.of(nonProfit));
    when(systemSettingService.getSettingValue("job_order.intake_special_command_id"))
        .thenReturn(Optional.of(intakeId.toString()));
    when(orgUnitRepository.findById(intakeId)).thenReturn(Optional.of(intake));

    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 5.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(
            nonProfitId, requestingOrgUnitId, "anon-handle", null, List.of(createMat), null);

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

    // When
    jobOrderService.createJobOrder(createDto);

    // Then — the non-profit pick is rejected and the order routes to the intake SK.
    verify(jobOrderRepository).save(argThat(jo -> jo.getResponsibleOrgUnit() == intake));
  }

  @Test
  void createJobOrder_Guest_NoIntakeConfigured_Throws() {
    // Given — a guest creation but no intake SK configured: must reject with 400, never persist.
    when(authHelperService.isAuthenticated()).thenReturn(false);
    when(systemSettingService.getSettingValue("job_order.intake_special_command_id"))
        .thenReturn(Optional.empty());

    CreateJobOrderMaterialDto createMat = new CreateJobOrderMaterialDto(materialId, 650, 5.0);
    CreateJobOrderDto createDto =
        new CreateJobOrderDto(
            null, requestingOrgUnitId, "anon-handle", null, List.of(createMat), null);

    assertThrows(BadRequestException.class, () -> jobOrderService.createJobOrder(createDto));
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void updateJobOrderPriority_ShouldReorderAndNormalize() {
    // Given
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

    // When
    JobOrderDto result = jobOrderService.updateJobOrderPriority(orderId, 2);

    // Then
    assertEquals(2, jobOrder.getPriority());
    assertEquals(1, otherJob.getPriority());
    assertNotNull(result);
  }

  @Test
  void updateJobOrderStatus_ToCompleted_ShouldRemovePriorityAndNormalize() {
    // Given
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

    // When
    JobOrderDto result =
        jobOrderService.updateJobOrderStatus(
            orderId, new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, 1L));

    // Then
    assertNull(jobOrder.getPriority());
    assertEquals(JobOrderStatus.COMPLETED, jobOrder.getStatus());
    assertNotNull(result);
    verify(jobOrderRepository).lockAllJobOrders();
    verify(inventoryItemRepository).unlinkJobOrder(orderId);
  }

  @Test
  void updateJobOrderStatus_ToRejected_ShouldRemovePriorityAndNormalizeAndUnlink() {
    // Given
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

    // When
    JobOrderDto result =
        jobOrderService.updateJobOrderStatus(
            orderId, new UpdateJobOrderStatusDto(JobOrderStatus.REJECTED, 1L));

    // Then
    assertNull(jobOrder.getPriority());
    assertEquals(JobOrderStatus.REJECTED, jobOrder.getStatus());
    assertNotNull(result);
    verify(jobOrderRepository).lockAllJobOrders();
    verify(inventoryItemRepository).unlinkJobOrder(orderId);
  }

  @Test
  void updateJobOrderStatus_ToInProgress_ShouldNotUnlink() {
    // Given
    jobOrder.setPriority(2);
    jobOrder.setStatus(JobOrderStatus.OPEN);
    jobOrder.setVersion(1L);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);
    when(inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
            any(UUID.class), any(UUID.class), any()))
        .thenReturn(10.0);

    // When
    JobOrderDto result =
        jobOrderService.updateJobOrderStatus(
            orderId, new UpdateJobOrderStatusDto(JobOrderStatus.IN_PROGRESS, 1L));

    // Then
    assertEquals(JobOrderStatus.IN_PROGRESS, jobOrder.getStatus());
    assertNotNull(result);
    verify(inventoryItemRepository, never()).unlinkJobOrder(any());
  }

  @Test
  void updateJobOrderStatus_VersionMismatch_ShouldThrow409() {
    // Given
    jobOrder.setVersion(5L);
    jobOrder.setStatus(JobOrderStatus.OPEN);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));

    // When / Then
    assertThrows(
        org.springframework.orm.ObjectOptimisticLockingFailureException.class,
        () ->
            jobOrderService.updateJobOrderStatus(
                orderId, new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, 1L)));
    verify(jobOrderRepository, never()).save(any());
    verify(inventoryItemRepository, never()).unlinkJobOrder(any());
  }

  @Test
  void updateJobOrderStatus_ToActive_FromCompleted_ShouldAssignNewPriority() {
    // Given
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

    // When
    JobOrderDto result =
        jobOrderService.updateJobOrderStatus(
            orderId, new UpdateJobOrderStatusDto(JobOrderStatus.OPEN, 2L));

    // Then
    assertEquals(1, jobOrder.getPriority());
    assertEquals(JobOrderStatus.OPEN, jobOrder.getStatus());
    assertNotNull(result);
  }

  @Test
  void updateJobOrderPriority_CompletedJobOrder_ShouldThrowException() {
    // Given
    jobOrder.setPriority(null);
    jobOrder.setStatus(JobOrderStatus.COMPLETED);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));

    // When/Then
    assertThrows(
        BadRequestException.class,
        () -> {
          jobOrderService.updateJobOrderPriority(orderId, 2);
        });
    verify(jobOrderRepository, never()).save(any(JobOrder.class));
  }

  @Test
  void deleteJobOrder_ShouldLockAndNormalize() {
    // Given
    jobOrder.setPriority(3);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(jobOrder)));

    // When
    jobOrderService.deleteJobOrder(orderId);

    // Then
    verify(jobOrderRepository, times(2)).lockAllJobOrders();
    verify(jobOrderRepository).delete(jobOrder);
    verify(inventoryItemRepository).unlinkJobOrder(orderId);
  }

  @Test
  void updateJobOrder_OptimisticLockingFailure_ShouldThrowException() {
    // Given
    jobOrder.setVersion(2L);
    CreateJobOrderMaterialDto updateMat = new CreateJobOrderMaterialDto(materialId, 650, 50.0);
    CreateJobOrderDto updateDto =
        new CreateJobOrderDto(
            null, null, "Tester", null, List.of(updateMat), 1L); // version mismatch

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));

    // When/Then
    assertThrows(
        org.springframework.orm.ObjectOptimisticLockingFailureException.class,
        () -> {
          jobOrderService.updateJobOrder(orderId, updateDto);
        });
    verify(jobOrderRepository, never()).saveAndFlush(any(JobOrder.class));
  }

  @Test
  void updateJobOrder_RetargetsRequesting_AndIgnoresResponsible() {
    // The regular update path retargets the requesting (customer) org unit but NEVER touches the
    // responsible (processing) org unit — that is changed only via the dedicated reassignment
    // endpoint. Any responsibleOrgUnitId in the update DTO is ignored.
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
    // A non-null responsibleOrgUnitId is supplied but must be ignored by the update path.
    CreateJobOrderDto updateDto =
        new CreateJobOrderDto(UUID.randomUUID(), bravoId, "Tester", null, List.of(updateMat), null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(orgUnitRepository.findById(bravoId)).thenReturn(Optional.of(bravo));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(jobOrderRepository.saveAndFlush(any(JobOrder.class))).thenReturn(jobOrder);
    when(jobOrderMapper.toDto(any(JobOrder.class))).thenReturn(baseJobOrderDto);

    jobOrderService.updateJobOrder(orderId, updateDto);

    // Responsible unchanged (same reference); requesting flipped to "Bravo".
    assertSame(responsibleOriginal, jobOrder.getResponsibleOrgUnit());
    assertNotNull(jobOrder.getRequestingOrgUnit());
    assertEquals("Bravo", jobOrder.getRequestingOrgUnit().getShorthand());
  }

  @Test
  void reassignResponsibleOrgUnit_Admin_MovesToProfitEligibleTarget() {
    // Admin may reassign freely to any profit-eligible org unit.
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
    // The order is now responsible to an SK, so mapToDtoWithStock enriches it with the claim view
    // (Phase 5, #345).
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
    // Given
    UUID newMaterialId = UUID.randomUUID();
    Material newMaterial = new Material();
    newMaterial.setId(newMaterialId);

    // Post Phase 7 part 3 / V90 the resolver is UUID-only; pass a typed `requestingSquadronId`
    // and stub the repository to map it to a "Beta" squadron so the assertion below pins the
    // requesting-squadron-flip contract.
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

    // When
    jobOrderService.updateJobOrder(orderId, updateDto);

    // Then — requesting squadron flipped to the resolved "Beta" target.
    assertNotNull(jobOrder.getRequestingOrgUnit());
    assertEquals("Beta", jobOrder.getRequestingOrgUnit().getShorthand());
    assertEquals("NewTester", jobOrder.getHandle());

    // Check if the old material was unlinked
    verify(inventoryItemRepository).unlinkJobOrderMaterial(orderId, materialId);

    // Verify the persist flushes (saveAndFlush) so the in-place response carries the fresh
    // @Version.
    verify(jobOrderRepository).saveAndFlush(jobOrder);
  }

  @Test
  void updateJobOrder_flushesSoReturnedVersionIsFresh() {
    // Regression (#571): updateJobOrder maps the response DTO inside the open transaction, so the
    // @Version bump must be flushed (saveAndFlush) before toDto — otherwise the in-place edit modal
    // receives a stale pre-flush version and the user's next consecutive edit 409s. Assert the
    // flush, and that a plain save() (which defers the bump to commit) is never used on this path.
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
    // REQ-ORDERS-023: a requester's material edit audits as JOB_ORDER_UPDATED and notifies the
    // processing unit (a JobOrderUpdatedByRequesterEvent is published on commit).
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
    // Whole-order freeze: a requester cannot edit an order that already has a delivery.
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
    // REQ-ORDERS-023: an anonymous / memberless caller resolves to zero direct memberships and gets
    // an empty page — the scoped query is never issued (no all-orders leak).
    when(ownerScopeService.currentDirectMembershipOrgUnitIds()).thenReturn(java.util.Set.of());

    org.springframework.data.domain.Page<JobOrderDto> page =
        jobOrderQueryService.getRequestedJobOrders(
            null, org.springframework.data.domain.PageRequest.of(0, 20));

    assertTrue(page.isEmpty(), "no memberships -> empty page");
    verify(jobOrderRepository, never()).findRequestedOrders(any(), any(), any());
  }

  @Test
  void getRequestedJobOrders_nullStatuses_defaultsToAllStatuses_scopedToDirectMembership() {
    // A null/empty status filter expands to every status, and the scope is the caller's OWN direct
    // memberships (resolved server-side — clients cannot inject org-unit ids).
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
    // The requester item endpoint refuses a MATERIAL order (mirrors the logistician item path).
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
    // Whole-order freeze: an item order that already has an item handover cannot be edited.
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
    // REQ-ORDERS-023 canonical unlink ordering: rebuild the lines and saveAndFlush FIRST, THEN run
    // the clearAutomatically unlink for every material no longer required, then re-fetch, withdraw
    // orphan claims, notify the processing unit and audit as JOB_ORDER_ITEM_UPDATED (byRequester).
    JobOrder itemOrder = new JobOrder();
    itemOrder.setId(orderId);
    itemOrder.setType(JobOrderType.ITEM);
    itemOrder.setVersion(1L);
    itemOrder.setHandle("Tester");
    // The requester-update notification reads the responsible + requesting org-unit refs, so stamp
    // both (a bare order would NPE in publishJobOrderUpdatedByRequester).
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
    // requiredMaterialIds: {kept, removed} before the rebuild, {kept} after -> `removed` is
    // unlinked.
    // Chained thenReturn (not varargs) to avoid the unchecked generic-array varargs warning.
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
                    UUID.randomUUID(), UUID.randomUUID(), 1, List.of(), 1, null)),
            1L);

    jobOrderService.updateItemJobOrderAsRequester(orderId, dto);

    org.mockito.InOrder inOrder =
        org.mockito.Mockito.inOrder(jobOrderRepository, inventoryItemRepository);
    inOrder.verify(jobOrderRepository).saveAndFlush(itemOrder);
    inOrder.verify(inventoryItemRepository).unlinkJobOrderMaterial(orderId, removedMaterial);
    verify(inventoryItemRepository, never()).unlinkJobOrderMaterial(orderId, keptMaterial);
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
    // Given — reproduces the root cause of the 409 bug:
    // completeJobOrderWithinTransaction() modifies jobOrder in-memory (status, priority),
    // then calls normalizePriorities() which issues a PESSIMISTIC_WRITE lock query via
    // lockAllJobOrders(). Without a flush() before that query, the DB still holds the old
    // @Version value while Hibernate has already incremented it in-memory, causing an
    // ObjectOptimisticLockingFailureException on the final transaction flush.
    // Fix: jobOrderRepository.flush() is called before normalizePriorities().
    jobOrder.setStatus(JobOrderStatus.OPEN);
    jobOrder.setPriority(1);
    when(jobOrderRepository.lockAllJobOrders()).thenReturn(new ArrayList<>(List.of(jobOrder)));

    // When — must not throw any exception
    assertDoesNotThrow(() -> jobOrderService.completeJobOrderWithinTransaction(jobOrder));

    // Then — flush() must be called BEFORE lockAllJobOrders() to sync the @Version to DB
    var inOrder = inOrder(jobOrderRepository);
    inOrder.verify(jobOrderRepository).flush();
    inOrder.verify(jobOrderRepository).lockAllJobOrders();

    assertEquals(JobOrderStatus.COMPLETED, jobOrder.getStatus());
    assertNull(jobOrder.getPriority());
    verify(inventoryItemRepository).unlinkJobOrder(orderId);
  }

  @Test
  void completeJobOrderWithinTransaction_ShouldNotNormalize_WhenAlreadyTerminal() {
    // Given — if the order is already COMPLETED, normalizePriorities() must NOT be called
    jobOrder.setStatus(JobOrderStatus.COMPLETED);
    jobOrder.setPriority(null);

    // When
    assertDoesNotThrow(() -> jobOrderService.completeJobOrderWithinTransaction(jobOrder));

    // Then — no flush, no lock query, no unlink since wasTerminal=true
    verify(jobOrderRepository, never()).flush();
    verify(jobOrderRepository, never()).lockAllJobOrders();
    verify(inventoryItemRepository, never()).unlinkJobOrder(any());
  }

  @Test
  void getInventoryItemsForJobOrderMaterial_ShouldReturnMappedDtos() {
    // Given
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
            100,
            10.0,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(inventoryItemRepository.findByJobOrderIdAndMaterialId(orderId, materialId))
        .thenReturn(List.of(item));
    when(inventoryItemMapper.toDto(item)).thenReturn(itemDto);

    // When
    List<InventoryItemDto> result =
        jobOrderQueryService.getInventoryItemsForJobOrderMaterial(orderId, materialId);

    // Then
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
    // Given
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderRepository.save(any(JobOrder.class))).thenReturn(jobOrder);

    // When
    jobOrderService.unlinkMaterial(orderId, materialId);

    // Then
    verify(inventoryItemRepository).unlinkJobOrderMaterial(orderId, materialId);
    verify(jobOrderRepository).save(jobOrder);
    assertTrue(
        jobOrder.getMaterials().isEmpty(), "Material should have been removed from job order");
  }

  @Test
  void unlinkMaterial_WhenJobOrderNotFound_ShouldThrowNotFound() {
    // Given
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.empty());

    // When / Then
    NotFoundException ex =
        assertThrows(
            NotFoundException.class, () -> jobOrderService.unlinkMaterial(orderId, materialId));
    verify(inventoryItemRepository, never()).unlinkJobOrderMaterial(any(), any());
  }

  @Test
  void unlinkMaterial_WhenMaterialNotLinked_ShouldThrowNotFound() {
    // Given
    UUID otherMaterialId = UUID.randomUUID();
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));

    // When / Then
    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> jobOrderService.unlinkMaterial(orderId, otherMaterialId));
    verify(inventoryItemRepository, never()).unlinkJobOrderMaterial(any(), any());
  }

  @Test
  void unlinkInventoryItem_ShouldSetJobOrderToNull() {
    // Given
    UUID inventoryItemId = UUID.randomUUID();
    de.greluc.krt.profit.basetool.backend.model.InventoryItem item =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    item.setId(inventoryItemId);
    item.setJobOrder(jobOrder);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(item));

    // When
    jobOrderService.unlinkInventoryItem(orderId, inventoryItemId);

    // Then
    assertNull(
        ((de.greluc.krt.profit.basetool.backend.model.InventoryItem) item).getJobOrder(),
        "JobOrder should be null after unlinking");
    verify(jobOrderRepository).findById(orderId);
    verify(inventoryItemRepository).findById(inventoryItemId);
  }

  @Test
  void unlinkInventoryItem_WhenJobOrderNotFound_ShouldThrowNotFound() {
    // Given
    UUID inventoryItemId = UUID.randomUUID();
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.empty());

    // When / Then
    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> jobOrderService.unlinkInventoryItem(orderId, inventoryItemId));
    verify(inventoryItemRepository, never()).findById(any());
  }

  @Test
  void unlinkInventoryItem_WhenInventoryItemNotFound_ShouldThrowNotFound() {
    // Given
    UUID inventoryItemId = UUID.randomUUID();
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.empty());

    // When / Then
    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> jobOrderService.unlinkInventoryItem(orderId, inventoryItemId));
  }

  @Test
  void unlinkInventoryItem_WhenItemNotLinkedToOrder_ShouldThrowNotFound() {
    // Given
    UUID inventoryItemId = UUID.randomUUID();
    UUID otherOrderId = UUID.randomUUID();
    JobOrder otherOrder = new JobOrder();
    otherOrder.setId(otherOrderId);

    de.greluc.krt.profit.basetool.backend.model.InventoryItem item =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    item.setId(inventoryItemId);
    item.setJobOrder(otherOrder);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(inventoryItemRepository.findById(inventoryItemId)).thenReturn(Optional.of(item));

    // When / Then
    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> jobOrderService.unlinkInventoryItem(orderId, inventoryItemId));
  }

  @Test
  void
      getInventoryItemsForJobOrderMaterial_ShouldReturnItemsSortedByOwnerAscQualityDescLocationAscAmountDesc() {
    // Given
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

    // Same owner "Alpha", same quality 80, different location → ArcCorp before Baijini
    InventoryItemDto dto1 =
        new InventoryItemDto(
            i1.getId(),
            userAlpha,
            null,
            locB,
            80,
            5.0,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null);
    // Same owner "Alpha", higher quality 90 → comes before quality 80
    InventoryItemDto dto2 =
        new InventoryItemDto(
            i2.getId(),
            userAlpha,
            null,
            locA,
            90,
            3.0,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null);
    // Owner "Beta" → after all "Alpha" entries
    InventoryItemDto dto3 =
        new InventoryItemDto(
            i3.getId(),
            userBeta,
            null,
            locA,
            70,
            20.0,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null);
    // Same owner "Alpha", same quality 80, same location ArcCorp, higher amount → comes before
    // lower amount
    InventoryItemDto dto4 =
        new InventoryItemDto(
            i4.getId(),
            userAlpha,
            null,
            locA,
            80,
            10.0,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
            null);

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(jobOrder));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(inventoryItemRepository.findByJobOrderIdAndMaterialId(orderId, materialId))
        .thenReturn(List.of(i1, i2, i3, i4));
    when(inventoryItemMapper.toDto(i1)).thenReturn(dto1);
    when(inventoryItemMapper.toDto(i2)).thenReturn(dto2);
    when(inventoryItemMapper.toDto(i3)).thenReturn(dto3);
    when(inventoryItemMapper.toDto(i4)).thenReturn(dto4);

    // When
    List<InventoryItemDto> result =
        jobOrderQueryService.getInventoryItemsForJobOrderMaterial(orderId, materialId);

    // Then
    // Expected order: dto2 (Alpha, q90, ArcCorp, 3), dto4 (Alpha, q80, ArcCorp, 10), dto1 (Alpha,
    // q80, Baijini, 5), dto3 (Beta, q70, ArcCorp, 20)
    assertNotNull(result);
    assertEquals(4, result.size());
    assertEquals(dto2.id(), result.get(0).id(), "1st: Alpha, quality 90, ArcCorp");
    assertEquals(dto4.id(), result.get(1).id(), "2nd: Alpha, quality 80, ArcCorp, amount 10");
    assertEquals(dto1.id(), result.get(2).id(), "3rd: Alpha, quality 80, Baijini, amount 5");
    assertEquals(dto3.id(), result.get(3).id(), "4th: Beta, quality 70, ArcCorp");
  }

  @Test
  void getOrphanedLinkedInventoryReturnsOnlyLinksWhoseMaterialIsNotRequired() {
    // REQ-ORDERS-019: of the inventory linked to the order, only the item whose material is NOT a
    // requirement is returned (the invisible, orphaned link — the Torite -> #71 case).
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
            661,
            0.18,
            false,
            orderId,
            71,
            null,
            null,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            1L,
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

  // ---------------------------------------------------------------
  // updateJobOrderStatus — COMPLETED audit edge-gating (JobOrderService.java:568-582)
  // ---------------------------------------------------------------

  @Test
  void updateJobOrderStatus_openToCompleted_recordsJobOrderCompletedNotStatusChanged() {
    // A genuine OPEN -> COMPLETED manual transition crosses the completion EDGE
    // (previousStatus != COMPLETED), so the endpoint records exactly one JOB_ORDER_COMPLETED
    // (autoCompleted=false) and NEVER a JOB_ORDER_STATUS_CHANGED — the same single-event funnel the
    // auto-completion path uses. Gating on the edge (not on status alone) is what keeps a real
    // completion from being misclassified as a plain status change.
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

    // Exactly one JOB_ORDER_COMPLETED, carrying the from-status and the autoCompleted=false marker.
    verify(auditService)
        .record(
            eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.JOB_ORDER_COMPLETED),
            eq(orderId),
            any(),
            any(),
            argThat(d -> d != null && d.toString().equals("from=OPEN autoCompleted=false")));
    // The manual completion is NOT also emitted as a STATUS_CHANGED (no duplicate audit row).
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
    // An idempotent re-save of an already-COMPLETED order does NOT cross the completion edge
    // (previousStatus == COMPLETED), so it is a plain JOB_ORDER_STATUS_CHANGED and must NEVER emit
    // a
    // second JOB_ORDER_COMPLETED — otherwise every no-op PUT status=COMPLETED would double-count a
    // completion in the audit log and any basetool_* completion metric derived from it.
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

    // A no-op completed->completed re-save records STATUS_CHANGED (from == to), not COMPLETED.
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
    // Not a terminal EDGE, so no priority reshuffle / inventory unlink runs on the idempotent save.
    verify(inventoryItemRepository, never()).unlinkJobOrder(any());
  }

  // ---------------------------------------------------------------
  // reassignResponsibleOrgUnit — non-admin escalation gate (JobOrderService.java:1310-1320)
  // ---------------------------------------------------------------

  @Test
  void reassignResponsibleOrgUnit_NonAdmin_EscalatesOwnSquadronToSk_succeeds() {
    // The one move a non-admin logistician/officer may make: escalate an order responsible to a
    // squadron they may edit up to a Spezialkommando. All three sub-conditions hold
    // (currentIsSquadron && targetIsSpecialCommand && mayEditCurrent), so the gate lets it through
    // and the responsible org unit is flipped to the SK target.
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
    // The order is now SK-responsible, so mapToDtoWithStock enriches it with the claim view.
    when(materialClaimService.getClaimBucketsForOrder(any(JobOrder.class)))
        .thenReturn(java.util.List.of());

    jobOrderService.reassignResponsibleOrgUnit(orderId, targetId);

    assertSame(target, jobOrder.getResponsibleOrgUnit());
    verify(jobOrderRepository).save(jobOrder);
  }

  @Test
  void reassignResponsibleOrgUnit_NonAdmin_ToAnotherSquadron_throwsAccessDenied() {
    // targetIsSpecialCommand regression guard: even from an editable own squadron, a non-admin may
    // NOT hand the order to another squadron — only escalate to an SK. The target is a squadron, so
    // the gate denies it and nothing is persisted (cross-tenant escalation prevented).
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
    // currentIsSquadron regression guard: a non-admin may not mutate an order already responsible
    // to
    // an SK (currentIsSquadron == false), even when the target is a profit-eligible SK and the
    // caller may edit the current unit. The gate denies it and nothing is persisted.
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
    // mayEditCurrent regression guard: escalating to an SK is allowed only for a squadron the
    // caller
    // may edit. A foreign squadron's order (canEditOrgUnit == false) must be denied so a
    // logistician
    // cannot escalate another squadron's order across the tenant boundary.
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

  // ---------------------------------------------------------------
  // updateItemJobOrder — item-order edit (item lines + metadata)
  // ---------------------------------------------------------------

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
                  UUID.randomUUID(), UUID.randomUUID(), 1, List.of(), 1, null)),
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
      // mapToDtoWithStock reads the item projections for an ITEM order.
      when(jobOrderItemService.toItemDtos(any())).thenReturn(List.of());
      when(jobOrderItemService.aggregateMaterials(any())).thenReturn(List.of());
      // Each line builds a distinct managed JobOrderItem.
      when(jobOrderItemService.buildItemLine(any()))
          .thenAnswer(inv -> new de.greluc.krt.profit.basetool.backend.model.JobOrderItem());

      // Two lines, the second adopted as a sub-assembly of the first (parentClientLineId = 1).
      de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto dto =
          new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto(
              null,
              null,
              "edited",
              null,
              List.of(
                  new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                      UUID.randomUUID(), UUID.randomUUID(), 1, List.of(), 1, null),
                  new de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto(
                      UUID.randomUUID(), UUID.randomUUID(), 2, List.of(), 2, 1)),
              1L);

      jobOrderService.updateItemJobOrder(orderId, dto);

      // Both lines were (re-)built and attached, and the orphan-claim reconciliation ran.
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
    void happyPath_enrichesAggregatedMaterialsWithCollectionStock() {
      // #595: the order overview shows an item order's aggregated material list with collection
      // progress, so every aggregated bucket must carry currentStock — the order-linked inventory
      // summed at the bucket's quality floor (GOOD -> 650), exactly like the MATERIAL rows.
      JobOrder order = itemOrder();
      when(jobOrderRepository.findById(orderId)).thenReturn(java.util.Optional.of(order));
      when(jobOrderRepository.save(any(JobOrder.class))).thenAnswer(inv -> inv.getArgument(0));
      // An ITEM base DTO with no MATERIAL lines, so only the aggregated path computes stock here.
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

  // ---------------------------------------------------------------
  // updateBlueprintVariantCounting — REQ-ORDERS-021 / #822
  // ---------------------------------------------------------------

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
