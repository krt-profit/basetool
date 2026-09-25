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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
@ExtendWith(MockitoExtension.class)
class JobOrderServiceAssigneeAndListTest {

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private SquadronRepository squadronRepository;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private JobOrderMapper jobOrderMapper;
  @Mock private de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper squadronMapper;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private JobOrderItemService jobOrderItemService;
  @Mock private MaterialClaimService materialClaimService;

  @Mock private AuditService auditService;

  @InjectMocks private JobOrderStockProjectionService jobOrderStockProjectionService;
  private JobOrderAssigneeService jobOrderAssigneeService;

  private JobOrderService service;

  private JobOrderQueryService queryService;

  private static final UUID JOB_ORDER_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @BeforeEach
  void stubMapperEchoingEmptyMaterials() {
    jobOrderAssigneeService =
        new JobOrderAssigneeService(
            jobOrderRepository, userRepository, auditService, jobOrderStockProjectionService);
    service =
        new JobOrderService(
            jobOrderRepository,
            materialRepository,
            inventoryItemRepository,
            jobOrderAssigneeService,
            null,
            null,
            null,
            null,
            materialClaimService,
            auditService,
            jobOrderItemService,
            jobOrderStockProjectionService,
            null);
    queryService =
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
    lenient()
        .when(inventoryItemRepository.findMaterialStockRowsByJobOrderIds(any()))
        .thenReturn(List.of());
    lenient().when(materialClaimService.getClaimBucketsForOrders(any())).thenReturn(Map.of());
  }

  @Nested
  class GetAllJobOrdersTests {

    private final PageRequest pageable = PageRequest.of(0, 10);
    private final ScopePredicate adminAllScope = new ScopePredicate(true, null, Set.of());

    private final List<JobOrderStatus> allStatuses = List.of(JobOrderStatus.values());

    @BeforeEach
    void stubScope() {
      lenient().when(ownerScopeService.currentScopePredicate()).thenReturn(adminAllScope);
      lenient().when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    }

    @Test
    void nullStatusList_passesFullEnumSet() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses, true, Set.of(new UUID(0L, 0L)), true, null, Set.of(), pageable))
          .thenReturn(page);

      Page<JobOrderDto> result = queryService.getAllJobOrders(null, pageable);

      assertEquals(1, result.getTotalElements());
      verify(jobOrderRepository)
          .findScopedJobOrders(
              allStatuses, true, Set.of(new UUID(0L, 0L)), true, null, Set.of(), pageable);
    }

    @Test
    void emptyStatusList_passesFullEnumSet() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses, true, Set.of(new UUID(0L, 0L)), true, null, Set.of(), pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(List.of(), pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              allStatuses, true, Set.of(new UUID(0L, 0L)), true, null, Set.of(), pageable);
    }

    @Test
    void populatedStatusList_forwardsStatusesAndScope() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      when(jobOrderRepository.findScopedJobOrders(
              List.of(JobOrderStatus.OPEN),
              true,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
              pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(List.of(JobOrderStatus.OPEN), pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              List.of(JobOrderStatus.OPEN),
              true,
              Set.of(new UUID(0L, 0L)),
              true,
              null,
              Set.of(),
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
              true,
              null,
              Set.of(),
              pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(List.of(JobOrderStatus.OPEN), Set.of(squadronId), pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              List.of(JobOrderStatus.OPEN),
              false,
              Set.of(squadronId),
              true,
              null,
              Set.of(),
              pageable);
    }

    @Test
    void emptyStatusListWithSquadronId_keepsDisplayFilterAndFullEnumSet() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      UUID squadronId = UUID.randomUUID();
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses, false, Set.of(squadronId), true, null, Set.of(), pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(List.of(), Set.of(squadronId), pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              allStatuses, false, Set.of(squadronId), true, null, Set.of(), pageable);
    }

    @Test
    void nonAdminScope_forwardsMemberUnionToRepository() {
      Page<JobOrder> page = new PageImpl<>(List.of(newJobOrder(JobOrderStatus.OPEN)));
      UUID sqA = UUID.randomUUID();
      UUID sqB = UUID.randomUUID();
      Set<UUID> union = Set.of(sqA, sqB);
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(false, null, union));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses, true, Set.of(new UUID(0L, 0L)), false, null, union, pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(null, pageable);

      verify(jobOrderRepository)
          .findScopedJobOrders(
              allStatuses, true, Set.of(new UUID(0L, 0L)), false, null, union, pageable);
    }

    @Test
    void listPath_batchesStockOncePerPageAndAvoidsPerMaterialSum() {
      Page<JobOrder> page =
          new PageImpl<>(
              List.of(newJobOrder(JobOrderStatus.OPEN), newJobOrder(JobOrderStatus.IN_PROGRESS)));
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses, true, Set.of(new UUID(0L, 0L)), true, null, Set.of(), pageable))
          .thenReturn(page);

      queryService.getAllJobOrders(null, pageable);

      verify(inventoryItemRepository, times(1)).findMaterialStockRowsByJobOrderIds(any());
      verify(inventoryItemRepository, never())
          .sumAmountByMaterialAndJobOrderAndMinQuality(any(), any(), any());
    }

    @Test
    void listPath_sumsBatchedStockAtEachBucketsQualityFloor() {
      UUID orderId = JOB_ORDER_ID;
      UUID matNoFloor = UUID.randomUUID();
      UUID matFloor650 = UUID.randomUUID();

      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      when(jobOrderRepository.findScopedJobOrders(
              allStatuses, true, Set.of(new UUID(0L, 0L)), true, null, Set.of(), pageable))
          .thenReturn(new PageImpl<>(List.of(order)));
      when(jobOrderMapper.toDto(order))
          .thenReturn(
              jobOrderDtoWithMaterials(
                  order,
                  materialLine(matNoFloor, null, 100.0),
                  materialLine(matFloor650, 650, 100.0)));
      when(inventoryItemRepository.findMaterialStockRowsByJobOrderIds(any()))
          .thenReturn(
              List.of(
                  new JobOrderMaterialStockRow(orderId, matNoFloor, 300, 10.0),
                  new JobOrderMaterialStockRow(orderId, matNoFloor, null, 5.0),
                  new JobOrderMaterialStockRow(orderId, matNoFloor, 900, 20.0),
                  new JobOrderMaterialStockRow(orderId, matNoFloor, 900, null),
                  new JobOrderMaterialStockRow(orderId, matFloor650, 640, 7.0),
                  new JobOrderMaterialStockRow(orderId, matFloor650, 650, 3.0),
                  new JobOrderMaterialStockRow(orderId, matFloor650, 900, 4.0),
                  new JobOrderMaterialStockRow(orderId, matFloor650, null, 99.0)));

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

    @Test
    void emptyRepositoryResult_returnsEmptyList() {
      when(ownerScopeService.canViewJobOrders()).thenReturn(true);
      when(jobOrderRepository.findAllActiveWithMaterials()).thenReturn(List.of());

      assertTrue(queryService.findAllActiveReference(false).isEmpty());
    }

    @Test
    void nonProfitMember_getsEmptyListWithoutQueryingRepository() {
      when(ownerScopeService.canViewJobOrders()).thenReturn(false);

      assertTrue(queryService.findAllActiveReference(false).isEmpty());
      verify(jobOrderRepository, never()).findAllActiveWithMaterials();
    }

    @Test
    void ordersOutOfScope_areFilteredOut() {
      JobOrder o = newJobOrder(JobOrderStatus.OPEN);
      when(ownerScopeService.canViewJobOrders()).thenReturn(true);
      when(ownerScopeService.canSeeJobOrder(any(JobOrder.class))).thenReturn(false);
      when(jobOrderRepository.findAllActiveWithMaterials()).thenReturn(List.of(o));

      assertTrue(queryService.findAllActiveReference(false).isEmpty());
    }

    @Test
    void orderWithNullMaterials_emitsEmptyMaterialsList() {
      JobOrder o = newJobOrder(JobOrderStatus.OPEN);
      o.setMaterials(null);
      when(ownerScopeService.canViewJobOrders()).thenReturn(true);
      when(ownerScopeService.canSeeJobOrder(any(JobOrder.class))).thenReturn(true);
      when(jobOrderRepository.findAllActiveWithMaterials()).thenReturn(List.of(o));

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
      mat.setMaterial(new de.greluc.krt.profit.basetool.backend.model.Material());
      o.setMaterials(new HashSet<>(Set.of(mat)));
      when(ownerScopeService.canViewJobOrders()).thenReturn(true);
      when(ownerScopeService.canSeeJobOrder(any(JobOrder.class))).thenReturn(true);
      when(jobOrderRepository.findAllActiveWithMaterials()).thenReturn(List.of(o));
      when(jobOrderMapper.toDto(mat))
          .thenReturn(
              new de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto(
                  mat.getId(), null, 100, 1.0, 0.0, List.of(), null, 0L));

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
   * Builds a minimal {@link JobOrderMaterialDto} line for the list-path stock test: only {@code
   * material().id()} (the stock-index key) and {@code minQuality} (the quality floor) matter to
   * {@link JobOrderService}'s stock resolver; {@code currentStock} starts at {@code 0.0} and is the
   * value the service must overwrite from the page-batched index.
   *
   * @param materialId the material identity the stock rows are keyed by.
   * @param minQuality the bucket's quality floor, or {@code null} for "Keine" (no floor).
   * @param requiredAmount the line's required amount (irrelevant to the sum, carried for realism).
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
    return new JobOrderMaterialDto(
        UUID.randomUUID(), material, minQuality, requiredAmount, 0.0, List.of(), null, 0L);
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
        false);
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
}
