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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderLinkedStockRows;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderLinkedStockWriteObserver;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryOrderWrites;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryStockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryStockReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMapper;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import de.greluc.krt.profit.basetool.backend.support.QualityTierFixtures;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for the list, page and reference getters of {@link JobOrderService} and for {@code
 * addAssignee} / {@code removeAssignee}.
 */
@ExtendWith({
  MockitoExtension.class,
  de.greluc.krt.profit.basetool.backend.support.InventoryWritePlanExtension.class
})
class JobOrderServiceAssigneeAndListTest {

  @Mock private ProfitHierarchyService profitHierarchy;
  @Mock private JobOrderResponsibilityGuard responsibilityGuard;
  @Mock private LiveSyncPublisher liveSyncPublisher;

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private OrderLinkedStockRows orderLinkedStockRows;
  @Mock private UserRepository userRepository;
  @Mock private SquadronRepository squadronRepository;
  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private JobOrderAccessPolicy jobOrderAccessPolicy;
  @Mock private JobOrderMapper jobOrderMapper;
  @Mock private de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronMapper squadronMapper;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private JobOrderItemService jobOrderItemService;
  @Mock private MaterialClaimService materialClaimService;
  @Mock private JobOrderChangeEffects effects;

  @Mock private AuditService auditService;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @InjectMocks private JobOrderStockProjectionService jobOrderStockProjectionService;
  private JobOrderAssigneeService jobOrderAssigneeService;

  private JobOrderService service;

  private JobOrderQueryService queryService;

  private static final UUID JOB_ORDER_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID PROCESSING_UNIT_ID = UUID.randomUUID();

  @BeforeEach
  void stubMapperEchoingEmptyMaterials() {
    jobOrderAssigneeService =
        new JobOrderAssigneeService(
            jobOrderRepository,
            responsibilityGuard,
            userRepository,
            orgUnitMembershipRepository,
            auditService,
            jobOrderStockProjectionService,
            org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
            actingUserService());
    service =
        new JobOrderService(
            jobOrderRepository,
            profitHierarchy,
            responsibilityGuard,
            jobOrderAccessPolicy,
            new JobOrderMaterialLines(materialRepository, null),
            new InventoryStockReads(
                inventoryItemRepository,
                inventoryItemMapper,
                null,
                null,
                org.mockito.Mockito.mock(
                    de.greluc.krt.profit.basetool.backend.inventory.api.StockViewerAccess.class),
                org.mockito.Mockito.mock(
                    de.greluc.krt.profit.basetool.backend.inventory.internal
                        .InventoryStockPlanningReads.class)),
            new InventoryStockCommands(
                new InventoryOrderWrites(
                    inventoryItemRepository,
                    org.mockito.Mockito.mock(OrderLinkedStockWriteObserver.class)),
                inventoryItemRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            jobOrderAssigneeService,
            null,
            null,
            null,
            null,
            effects,
            materialClaimService,
            auditService,
            jobOrderItemService,
            jobOrderStockProjectionService,
            null,
            actingUserService(),
            new JobOrderEditor(
                jobOrderRepository,
                profitHierarchy,
                org.mockito.Mockito.mock(MaterialPromiseCoordinator.class),
                org.mockito.Mockito.mock(
                    de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands.class),
                org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class),
                responsibilityGuard,
                jobOrderAccessPolicy,
                auditService,
                effects,
                jobOrderStockProjectionService,
                org.mockito.Mockito.mock(
                    de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderUnitHistory
                        .class)),
            liveSyncPublisher,
            org.mockito.Mockito.mock(
                de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderUnitHistory.class));
    lenient().when(jobOrderAccessPolicy.canEdit(any(), any())).thenReturn(true);
    queryService =
        new JobOrderQueryService(
            jobOrderRepository,
            materialRepository,
            new InventoryStockReads(
                inventoryItemRepository,
                inventoryItemMapper,
                null,
                null,
                org.mockito.Mockito.mock(
                    de.greluc.krt.profit.basetool.backend.inventory.api.StockViewerAccess.class),
                org.mockito.Mockito.mock(
                    de.greluc.krt.profit.basetool.backend.inventory.internal
                        .InventoryStockPlanningReads.class)),
            requestScopeResolver,
            jobOrderMapper,
            squadronMapper,
            jobOrderItemService,
            jobOrderStockProjectionService,
            null,
            org.mockito.Mockito.mock(
                de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService.class));
    lenient()
        .when(jobOrderMapper.toDtoWithAuthority(any(JobOrder.class), any()))
        .thenAnswer(inv -> {
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
              null,
              false,
              false,
              null,
              false);
        });
    lenient().when(orderLinkedStockRows.materialStockOfOrders(any())).thenReturn(List.of());
    lenient().when(materialClaimService.getClaimBucketsForOrders(any())).thenReturn(Map.of());
  }

  @Nested
  class GetAllJobOrdersTests {

    private final PageRequest pageable = PageRequest.of(0, 10);
    private final ScopePredicate adminAllScope = new ScopePredicate(true, null, Set.of());

    private final List<JobOrderStatus> allStatuses = List.of(JobOrderStatus.values());

    @BeforeEach
    void stubScope() {
      lenient()
          .when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(adminAllScope);
      lenient().when(requestScopeResolver.currentScopePredicate()).thenReturn(adminAllScope);
      lenient().when(requestScopeResolver.canViewJobOrders()).thenReturn(true);
    }

    @Test
    void nullStatusList_passesFullEnumSet() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(page);

      Page<JobOrderDto> result = queryService.getAllJobOrders(null, pageable);

      assertEquals(1, result.getTotalElements());
      verify(jobOrderRepository)
          .findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable);
    }

    @Test
    void emptyStatusList_passesFullEnumSet() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(List.of(), pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable);
    }

    @Test
    void populatedStatusList_forwardsStatusesAndScope() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      when(jobOrderRepository.findScopedJobOrders(
              List.of(JobOrderStatus.OPEN),
              true,
              Set.of(new UUID(0L, 0L)),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(List.of(JobOrderStatus.OPEN), pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              List.of(JobOrderStatus.OPEN),
              true,
              Set.of(new UUID(0L, 0L)),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable);
    }

    @Test
    void populatedStatusListWithSquadronId_passesSquadronDisplayFilter() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      UUID squadronId = UUID.randomUUID();
      when(jobOrderRepository.findScopedJobOrders(
              List.of(JobOrderStatus.OPEN),
              false,
              Set.of(squadronId),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(List.of(JobOrderStatus.OPEN), Set.of(squadronId), pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              List.of(JobOrderStatus.OPEN),
              false,
              Set.of(squadronId),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable);
    }

    @Test
    void emptyStatusListWithSquadronId_keepsDisplayFilterAndFullEnumSet() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      UUID squadronId = UUID.randomUUID();
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses,
              false,
              Set.of(squadronId),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(List.of(), Set.of(squadronId), pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              allStatuses,
              false,
              Set.of(squadronId),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable);
    }

    @Test
    void nonAdminScope_forwardsMemberUnionToRepository() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      UUID sqA = UUID.randomUUID();
      UUID sqB = UUID.randomUUID();
      Set<UUID> union = Set.of(sqA, sqB);
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(false, null, union));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              union,
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(null, pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              union,
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable);
    }

    @Test
    void toProcess_unpinnedIncludesCurrentLeadershipAndContextualUnitReach() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      UUID staffel = UUID.randomUUID();
      UUID sk = UUID.randomUUID();
      UUID cascaded = UUID.randomUUID();
      Set<UUID> union = Set.of(staffel, sk, cascaded);
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(false, null, union));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              true,
              union,
              true,
              null,
              union,
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(page);

      Page<JobOrderDto> result = queryService.getAllJobOrders(null, null, true, pageable);

      assertEquals(1, result.getTotalElements());
      verify(jobOrderRepository)
          .findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              true,
              union,
              true,
              null,
              union,
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable);
    }

    @Test
    void toProcess_pinned_forwardsOnlyThePinnedUnit() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      UUID pinned = UUID.randomUUID();
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(false, pinned, Set.of()));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              true,
              Set.of(pinned),
              true,
              pinned,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(null, null, true, pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              true,
              Set.of(pinned),
              true,
              pinned,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable);
    }

    @Test
    void toProcess_withAProcessingUnit_forwardsOnlyThatMembership() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      UUID staffel = UUID.randomUUID();
      UUID sk = UUID.randomUUID();
      Set<UUID> union = Set.of(staffel, sk);
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(false, null, union));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              true,
              Set.of(sk),
              true,
              null,
              union,
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(page);

      Page<JobOrderDto> result = queryService.getAllJobOrders(null, null, true, sk, pageable);

      assertEquals(1, result.getTotalElements());
    }

    @Test
    void toProcess_withAProcessingUnitOutsideTheMemberships_returnsAnEmptyPageWithoutQuerying() {
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(false, null, Set.of(UUID.randomUUID())));

      Page<JobOrderDto> result =
          queryService.getAllJobOrders(null, null, true, UUID.randomUUID(), pageable);

      assertEquals(0, result.getTotalElements());
      verifyNoInteractions(jobOrderRepository);
    }

    @Test
    void toProcess_withoutAnyUnit_returnsAnEmptyPageWithoutQuerying() {
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(false, null, Set.of()));

      Page<JobOrderDto> result = queryService.getAllJobOrders(null, null, true, pageable);

      assertEquals(0, result.getTotalElements());
      verifyNoInteractions(jobOrderRepository);
    }

    @Test
    void toProcessWithoutGlobalProfitReadKeepsItsCurrentUnitWork() {
      when(requestScopeResolver.canViewJobOrders()).thenReturn(false);
      Set<UUID> unitIds = Set.of(PROCESSING_UNIT_ID);
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(false, null, unitIds));
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      order.setResponsibleOrgUnit(processingUnit());
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              true,
              unitIds,
              false,
              null,
              unitIds,
              new UUID(0L, 0L),
              List.of(JobOrderType.values()),
              pageable))
          .thenReturn(new PageImpl<>(List.of(order)));

      Page<JobOrderDto> result = queryService.getAllJobOrders(null, null, true, pageable);

      assertEquals(1, result.getTotalElements());
      assertEquals(JOB_ORDER_ID, result.getContent().getFirst().id());
    }

    @Test
    void listPath_batchesStockOncePerPageAndAvoidsPerMaterialSum() {
      Page<JobOrder> page =
          new PageImpl<>(
              List.of(newJobOrder(JobOrderStatus.OPEN), newJobOrder(JobOrderStatus.IN_PROGRESS)));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(null, pageable);

      verify(orderLinkedStockRows, times(1)).materialStockOfOrders(any());
    }

    @Test
    void listPath_sumsBatchedStockAtEachBucketsQualityFloor() {
      UUID orderId = JOB_ORDER_ID;
      UUID matNoFloor = UUID.randomUUID();
      UUID matFloor650 = UUID.randomUUID();

      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses,
              true,
              Set.of(new UUID(0L, 0L)),
              false,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              new UUID(0L, 0L),
              java.util.List.of(JobOrderType.values()),
              pageable))
          .thenReturn(new PageImpl<>(List.of(order)));
      when(jobOrderMapper.toDtoWithAuthority(eq(order), any()))
          .thenReturn(
              jobOrderDtoWithMaterials(
                  order,
                  materialLine(matNoFloor, null, 100.0),
                  materialLine(matFloor650, 650, 100.0)));
      when(orderLinkedStockRows.materialStockOfOrders(any()))
          .thenReturn(
              List.of(
                  stockRow(orderId, matNoFloor, 300, 10.0),
                  stockRow(orderId, matNoFloor, null, 5.0),
                  stockRow(orderId, matNoFloor, 900, 20.0),
                  stockRow(orderId, matNoFloor, 900, null),
                  stockRow(orderId, matFloor650, 640, 7.0),
                  stockRow(orderId, matFloor650, 650, 3.0),
                  stockRow(orderId, matFloor650, 900, 4.0),
                  stockRow(orderId, matFloor650, null, 99.0)));

      Page<JobOrderDto> result = queryService.getAllJobOrders(null, pageable);

      Map<UUID, Double> stockByMaterial =
          result.getContent().get(0).materials().stream()
              .collect(Collectors.toMap(m -> m.material().id(), JobOrderMaterialDto::currentStock));
      assertEquals(
          35.0,
          stockByMaterial.get(matNoFloor),
          "no floor: every grade (incl. ungraded) counts, null-amount row skipped (10 + 5 + 20)");
      assertEquals(
          7.0,
          stockByMaterial.get(matFloor650),
          "floor 650: only non-null quality >= 650 counts (3 + 4); below-floor and null-quality"
              + " out");
    }
  }

  @Nested
  class FindAllActiveReferenceTests {

    @BeforeEach
    void currentUnitReadScope() {
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(false, null, Set.of(PROCESSING_UNIT_ID)));
    }

    @Test
    void emptyRepositoryResult_returnsEmptyList() {
      when(requestScopeResolver.canViewJobOrders()).thenReturn(true);
      when(jobOrderRepository.findReadableActiveWithMaterials(
              true, null, Set.of(PROCESSING_UNIT_ID), new UUID(0L, 0L)))
          .thenReturn(List.of());

      assertTrue(queryService.findAllActiveReference(false).isEmpty());
    }

    @Test
    void callerWithoutConcreteReadGetsNoReferenceHits() {
      when(requestScopeResolver.canViewJobOrders()).thenReturn(false);
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(false, null, Set.of()));
      when(jobOrderRepository.findReadableActiveWithMaterials(
              false, null, Set.of(), new UUID(0L, 0L)))
          .thenReturn(List.of());

      assertTrue(queryService.findAllActiveReference(false).isEmpty());
      verify(jobOrderRepository)
          .findReadableActiveWithMaterials(false, null, Set.of(), new UUID(0L, 0L));
    }

    @Test
    void theReferenceLookupUsesOnlyDatabaseAdmittedRows() {
      when(requestScopeResolver.canViewJobOrders()).thenReturn(false);
      when(jobOrderRepository.findReadableActiveWithMaterials(
              false, null, Set.of(PROCESSING_UNIT_ID), new UUID(0L, 0L)))
          .thenReturn(List.of());

      assertTrue(queryService.findAllActiveReference(false).isEmpty());
      verify(jobOrderRepository)
          .findReadableActiveWithMaterials(
              false, null, Set.of(PROCESSING_UNIT_ID), new UUID(0L, 0L));
    }

    @Test
    void orderWithNullMaterials_emitsEmptyMaterialsList() {
      JobOrder o = newJobOrder(JobOrderStatus.OPEN);
      o.setMaterials(null);
      when(requestScopeResolver.canViewJobOrders()).thenReturn(true);
      when(jobOrderRepository.findReadableActiveWithMaterials(
              true, null, Set.of(PROCESSING_UNIT_ID), new UUID(0L, 0L)))
          .thenReturn(List.of(o));

      List<JobOrderReferenceDto> result = queryService.findAllActiveReference(false);

      assertEquals(1, result.size());
      assertTrue(
          result.get(0).materials().isEmpty(),
          "null materials on the entity must surface as an empty list, NOT NPE");
    }

    @Test
    void orderWithMaterials_mapsThroughTheMapper() {
      JobOrder o = newJobOrder(JobOrderStatus.OPEN);
      JobOrderMaterial mat = new JobOrderMaterial();
      mat.setId(UUID.randomUUID());
      mat.setMaterial(new de.greluc.krt.profit.basetool.backend.catalogue.api.Material());
      o.setMaterials(new HashSet<>(Set.of(mat)));
      when(requestScopeResolver.canViewJobOrders()).thenReturn(true);
      when(jobOrderRepository.findReadableActiveWithMaterials(
              true, null, Set.of(PROCESSING_UNIT_ID), new UUID(0L, 0L)))
          .thenReturn(List.of(o));
      when(jobOrderMapper.toDto(mat))
          .thenReturn(
              new de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialDto(
                  mat.getId(),
                  null,
                  650,
                  QualityTierFixtures.goodDto(),
                  1.0,
                  0.0,
                  List.of(),
                  null,
                  0L));

      List<JobOrderReferenceDto> result = queryService.findAllActiveReference(false);

      assertEquals(1, result.size());
      assertEquals(1, result.get(0).materials().size());
    }
  }

  @Nested
  class GetJobOrderByIdTests {

    @Test
    void happyPath_returnsDto() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));

      JobOrderDto dto = queryService.getJobOrderById(JOB_ORDER_ID);

      assertEquals(JOB_ORDER_ID, dto.id());
    }

    @Test
    void notFound_throwsNotFoundException() {
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.empty());

      NotFoundException ex =
          assertThrows(NotFoundException.class, () -> queryService.getJobOrderById(JOB_ORDER_ID));
      assertTrue(
          ex.getMessage().contains(JOB_ORDER_ID.toString()),
          "the missing id must be part of the message for diagnostics");
    }
  }

  @Nested
  class AddAssigneeTests {

    @Test
    void happyPath_addsUserToAssigneesAndSaves() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      User user = newUser(USER_ID);
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(jobOrderRepository.saveAndFlush(order)).thenReturn(order);

      service.addAssignee(JOB_ORDER_ID, USER_ID);

      assertTrue(
          order.getAssignees().stream().anyMatch(a -> USER_ID.equals(a.getUser().getId())),
          "user must be added as an assignee edge");
      verify(jobOrderRepository).saveAndFlush(order);
    }

    @Test
    void externalAssignee_addingANonMemberOfTheProcessingUnitRecordsTheExternalEvent() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      order.setResponsibleOrgUnit(processingUnit());
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(newUser(USER_ID)));
      when(jobOrderRepository.saveAndFlush(order)).thenReturn(order);
      when(orgUnitMembershipRepository.existsByIdUserIdAndIdOrgUnitId(USER_ID, PROCESSING_UNIT_ID))
          .thenReturn(false);

      service.addAssignee(JOB_ORDER_ID, USER_ID);

      verify(auditService)
          .record(
              eq(AuditEventType.JOB_ORDER_EXTERNAL_ASSIGNEE_ADDED),
              eq(JOB_ORDER_ID),
              any(),
              eq(USER_ID),
              argThat(
                  d -> AuditDetails.of("assignee", USER_ID).toString().equals(String.valueOf(d))));
    }

    @Test
    void externalAssignee_addingADirectMemberRecordsTheRegularEvent() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      order.setResponsibleOrgUnit(processingUnit());
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(newUser(USER_ID)));
      when(jobOrderRepository.saveAndFlush(order)).thenReturn(order);
      when(orgUnitMembershipRepository.existsByIdUserIdAndIdOrgUnitId(USER_ID, PROCESSING_UNIT_ID))
          .thenReturn(true);

      service.addAssignee(JOB_ORDER_ID, USER_ID);

      verify(auditService)
          .record(
              eq(AuditEventType.JOB_ORDER_ASSIGNEE_ADDED),
              eq(JOB_ORDER_ID),
              any(),
              eq(USER_ID),
              argThat(
                  d -> AuditDetails.of("assignee", USER_ID).toString().equals(String.valueOf(d))));
    }

    @Test
    void addingAnInactiveAccount_isRejected() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      User pending = newUser(USER_ID);
      pending.setApprovalStatus(ApprovalStatus.PENDING);
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pending));

      assertThrows(BadRequestException.class, () -> service.addAssignee(JOB_ORDER_ID, USER_ID));
      verify(jobOrderRepository, never()).saveAndFlush(any());
    }

    @Test
    void addingAnAccountGoneFromKeycloak_isRejected() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      User gone = newUser(USER_ID);
      gone.setEnabledInKeycloak(false);
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(gone));

      assertThrows(BadRequestException.class, () -> service.addAssignee(JOB_ORDER_ID, USER_ID));
      verify(jobOrderRepository, never()).saveAndFlush(any());
    }

    @Test
    void notFoundJobOrder_throwsBeforeUserLookup() {
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.addAssignee(JOB_ORDER_ID, USER_ID));
      verify(userRepository, never()).findById(any());
      verify(jobOrderRepository, never()).saveAndFlush(any());
    }

    @Test
    void notFoundUser_throwsNotFoundException() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.addAssignee(JOB_ORDER_ID, USER_ID));
      verify(jobOrderRepository, never()).saveAndFlush(any());
    }

    @Test
    void addingExistingAssignee_isIdempotent() {
      User user = newUser(USER_ID);
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      assigneeEdge(order, user);

      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));

      service.addAssignee(JOB_ORDER_ID, USER_ID);

      assertEquals(
          1, order.getAssignees().size(), "re-adding the same user must not duplicate the edge");
      verify(userRepository, never()).findById(any());
      verify(jobOrderRepository, never()).saveAndFlush(any());
    }
  }

  @Nested
  class RemoveAssigneeTests {

    @Test
    void happyPath_removesAssigneeAndSaves() {
      User user = newUser(USER_ID);
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      assigneeEdge(order, user);

      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(jobOrderRepository.saveAndFlush(order)).thenReturn(order);

      service.removeAssignee(JOB_ORDER_ID, USER_ID);

      assertTrue(order.getAssignees().isEmpty());
      verify(jobOrderRepository).saveAndFlush(order);
    }

    @Test
    void externalAssignee_removingANonMemberOfTheProcessingUnitRecordsTheExternalEvent() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      order.setResponsibleOrgUnit(processingUnit());
      assigneeEdge(order, newUser(USER_ID));
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(jobOrderRepository.saveAndFlush(order)).thenReturn(order);
      when(orgUnitMembershipRepository.existsByIdUserIdAndIdOrgUnitId(USER_ID, PROCESSING_UNIT_ID))
          .thenReturn(false);

      service.removeAssignee(JOB_ORDER_ID, USER_ID);

      verify(auditService)
          .record(
              eq(AuditEventType.JOB_ORDER_EXTERNAL_ASSIGNEE_REMOVED),
              eq(JOB_ORDER_ID),
              any(),
              eq(USER_ID),
              argThat(
                  d -> AuditDetails.of("assignee", USER_ID).toString().equals(String.valueOf(d))));
    }

    @Test
    void removingNonAssignee_isANoOpButStillSaves() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);

      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(jobOrderRepository.saveAndFlush(order)).thenReturn(order);

      service.removeAssignee(JOB_ORDER_ID, USER_ID);

      assertTrue(order.getAssignees().isEmpty());
      verify(jobOrderRepository).saveAndFlush(order);
    }

    @Test
    void notFoundJobOrder_throws() {
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.removeAssignee(JOB_ORDER_ID, USER_ID));
      verify(jobOrderRepository, never()).saveAndFlush(any());
    }
  }

  @Nested
  class AssigneeNoteTests {

    @Test
    void setNote_trimsAndFlushes() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      JobOrderAssignee edge = assigneeEdge(order, newUser(USER_ID));
      edge.setVersion(3L);

      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(jobOrderRepository.saveAndFlush(order)).thenReturn(order);

      service.updateAssigneeNote(JOB_ORDER_ID, USER_ID, "  works Friday  ", 3L);

      assertEquals("works Friday", edge.getNote(), "note is stored stripped");
      verify(jobOrderRepository).saveAndFlush(order);
    }

    @Test
    void setBlankNote_clearsIt() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      JobOrderAssignee edge = assigneeEdge(order, newUser(USER_ID));
      edge.setNote("old");

      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(jobOrderRepository.saveAndFlush(order)).thenReturn(order);

      service.updateAssigneeNote(JOB_ORDER_ID, USER_ID, "   ", null);

      assertNull(edge.getNote(), "a blank note clears the value");
    }

    @Test
    void deleteNote_clearsNote() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      JobOrderAssignee edge = assigneeEdge(order, newUser(USER_ID));
      edge.setNote("old");
      edge.setVersion(5L);

      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));
      when(jobOrderRepository.saveAndFlush(order)).thenReturn(order);

      service.deleteAssigneeNote(JOB_ORDER_ID, USER_ID, 5L);

      assertNull(edge.getNote());
    }

    @Test
    void staleVersion_throwsOptimisticLock() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      JobOrderAssignee edge = assigneeEdge(order, newUser(USER_ID));
      edge.setVersion(7L);

      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> service.updateAssigneeNote(JOB_ORDER_ID, USER_ID, "x", 3L));
      verify(jobOrderRepository, never()).saveAndFlush(any());
    }

    @Test
    void unknownAssignee_throwsNotFound() {
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.of(order));

      assertThrows(
          NotFoundException.class,
          () -> service.updateAssigneeNote(JOB_ORDER_ID, USER_ID, "x", null));
      verify(jobOrderRepository, never()).saveAndFlush(any());
    }
  }

  private JobOrder newJobOrder(JobOrderStatus status) {
    JobOrder o = new JobOrder();
    o.setId(JOB_ORDER_ID);
    o.setStatus(status);
    o.setVersion(1L);
    return o;
  }

  /**
   * Builds one order-linked stock row with its own inventory item id.
   *
   * @param orderId the order the row is linked to
   * @param materialId the row's material
   * @param quality the row's quality, or {@code null}
   * @param amount the row's amount, or {@code null}
   * @return the stock row
   */
  private static JobOrderMaterialStockRow stockRow(
      UUID orderId, UUID materialId, Integer quality, Double amount) {
    return new JobOrderMaterialStockRow(orderId, UUID.randomUUID(), materialId, quality, amount);
  }

  /**
   * Builds a minimal {@link JobOrderMaterialDto} line for the list-path stock test: only {@code
   * material().id()} (the stock-index key) and the quality tier's floor matter to the stock
   * projection; {@code currentStock} starts at {@code 0.0} and is the value the service must
   * overwrite from the page-batched index.
   *
   * @param materialId the material identity the stock rows are keyed by.
   * @param minQuality the bucket's quality floor, or {@code null} for the base tier.
   * @param requiredAmount the line's required amount; it exceeds the linked stock, so every
   *     qualifying unit counts.
   * @return the material line DTO.
   */
  private JobOrderMaterialDto materialLine(
      UUID materialId, Integer minQuality, double requiredAmount) {
    MaterialDto material =
        new MaterialDto(
            materialId,
            "mat-" + materialId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L);
    QualityTierDto tier =
        minQuality == null
            ? QualityTierFixtures.noneDto()
            : QualityTierFixtures.dto(QualityTierFixtures.tier("Q" + minQuality, minQuality));
    return new JobOrderMaterialDto(
        UUID.randomUUID(), material, minQuality, tier, requiredAmount, 0.0, List.of(), null, 0L);
  }

  /**
   * Wraps the given material lines in a {@code MATERIAL}-type {@link JobOrderDto} mirroring the
   * shape the shared mapper stub produces, so the list path enriches real material rows (the
   * default {@code stubMapperEchoingEmptyMaterials} returns none).
   *
   * @param order the order whose scalar fields seed the DTO.
   * @param materials the material lines the list path must stock-enrich.
   * @return the populated order DTO.
   */
  private JobOrderDto jobOrderDtoWithMaterials(JobOrder order, JobOrderMaterialDto... materials) {
    return new JobOrderDto(
        order.getId(),
        order.getDisplayId(),
        null,
        null,
        order.getHandle(),
        order.getComment(),
        order.getPriority(),
        order.getStatus(),
        JobOrderType.MATERIAL,
        order.isCountBlueprintsWithVariants(),
        List.of(materials),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null,
        order.getVersion(),
        null,
        null,
        false,
        false,
        null,
        false);
  }

  private Squadron processingUnit() {
    Squadron unit = new Squadron();
    unit.setId(PROCESSING_UNIT_ID);
    return unit;
  }

  private User newUser(UUID id) {
    User u = new User();
    u.setId(id);
    u.setUsername("user-" + id);
    return u;
  }

  private JobOrderAssignee assigneeEdge(JobOrder order, User user) {
    JobOrderAssignee edge = new JobOrderAssignee();
    edge.setId(UUID.randomUUID());
    edge.setUser(user);
    edge.setVersion(0L);
    order.addAssignee(edge);
    return edge;
  }

  private static UserService actingUserService() {
    UserService userService = org.mockito.Mockito.mock(UserService.class);
    org.mockito.Mockito.lenient()
        .when(userService.currentActor())
        .thenReturn(
            de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef.system());
    return userService;
  }
}
