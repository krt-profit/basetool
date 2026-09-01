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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Coverage for {@link JobOrderService} methods that the main {@code JobOrderServiceTest} doesn't
 * reach: the list/page/reference getters and the {@code addAssignee} / {@code removeAssignee} pair.
 * Previously all of these methods were at 0% coverage according to JaCoCo.
 *
 * <p>Lives as a sibling test so the existing 586-line test file doesn't need to gain a {@code
 * UserRepository} mock (which would dirty its dependency surface for tests that don't need it).
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

  // The stock/claim DTO projection and the assignee lifecycle were extracted to
  // JobOrderStockProjectionService / JobOrderAssigneeService (L2, #921); real instances (built from
  // the same mocks) are wired into the CUT via reflection in setUp() — the assignee service also
  // gets the real projection chained in — so the delegated add/remove/note paths and the list paths
  // keep exercising the real logic.
  @InjectMocks private JobOrderStockProjectionService jobOrderStockProjectionService;
  @InjectMocks private JobOrderAssigneeService jobOrderAssigneeService;

  @InjectMocks private JobOrderService service;

  // Read/write split (#14): the list/reference/detail reads moved to JobOrderQueryService, built
  // from the same mocks with the real stock projection wired in below.
  @InjectMocks private JobOrderQueryService queryService;

  private static final UUID JOB_ORDER_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @BeforeEach
  void stubMapperEchoingEmptyMaterials() {
    ReflectionTestUtils.setField(
        service, "jobOrderStockProjectionService", jobOrderStockProjectionService);
    ReflectionTestUtils.setField(
        jobOrderAssigneeService, "jobOrderStockProjectionService", jobOrderStockProjectionService);
    ReflectionTestUtils.setField(service, "jobOrderAssigneeService", jobOrderAssigneeService);
    ReflectionTestUtils.setField(
        queryService, "jobOrderStockProjectionService", jobOrderStockProjectionService);
    // The service routes nearly every return through mapToDtoWithStock(),
    // which calls jobOrderMapper.toDto(...) and then iterates the result's
    // materials. Return an empty materials list so we don't have to stub
    // the stock-aggregation repository call.
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
                  false);
            });
    // The paged list path batches stock once per page via findMaterialStockRowsByJobOrderIds
    // (REQ-DATA-003); default to an empty index so the routing tests need not model stock.
    lenient()
        .when(inventoryItemRepository.findMaterialStockRowsByJobOrderIds(any()))
        .thenReturn(List.of());
    // The list path also batches SK claims once per page; the routing tests use non-SK orders, so
    // an empty per-order claim map is the right default.
    lenient().when(materialClaimService.getClaimBucketsForOrders(any())).thenReturn(Map.of());
  }

  // ---------------------------------------------------------------
  // getAllJobOrders — status-filter routing + visibility scope (Phase 3, #343)
  // ---------------------------------------------------------------

  @Nested
  class GetAllJobOrdersTests {

    private final PageRequest pageable = PageRequest.of(0, 10);
    // The scope predicate is resolved from OwnerScopeService and pushed into the repository query;
    // every list call must consult it. An admin-all-scope predicate keeps these routing tests
    // focused on the status/squadron-filter forwarding rather than the scope semantics (those live
    // in OwnerScopeServiceTest).
    private final ScopePredicate adminAllScope = new ScopePredicate(true, null, Set.of());

    // No status filter → the service passes the full enum set so the repository IN clause is never
    // bound with an empty collection.
    private final List<JobOrderStatus> allStatuses = List.of(JobOrderStatus.values());

    @BeforeEach
    void stubScope() {
      lenient().when(ownerScopeService.currentScopePredicate()).thenReturn(adminAllScope);
      // These routing tests model a permitted viewer; the profit gate is covered separately in
      // OwnerScopeServiceTest / JobOrderServiceTest, so let the list reach the repository here.
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
      // A non-admin member with a two-OrgUnit membership union: the predicate's memberOrgUnitIds
      // must reach the repository verbatim so the IN-clause scoping applies.
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
      // REQ-DATA-003: the paged list must enrich stock with ONE batched query for the whole page,
      // not the former one-SUM-per-material-per-order fan-out. With two orders on the page the
      // batch
      // query is issued exactly once and the per-material aggregate is never called on this path.
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
      // REQ-DATA-003: the list path sums the page-batched stock rows in memory, reproducing the
      // native COALESCE(SUM(amount), 0) + (:floor IS NULL OR quality >= :floor) semantics for ANY
      // floor — not just the GOOD/NONE pair. matNoFloor counts every grade; matFloor650 keeps only
      // quality >= 650. The defensive null-quality / null-amount branches are exercised too (the
      // columns are NOT NULL in the DB, so they are unreachable in production but must stay safe).
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
                  new JobOrderMaterialStockRow(
                      orderId, matNoFloor, 900, null), // null amount skipped
                  new JobOrderMaterialStockRow(orderId, matFloor650, 640, 7.0), // below floor: out
                  new JobOrderMaterialStockRow(orderId, matFloor650, 650, 3.0), // boundary: in
                  new JobOrderMaterialStockRow(orderId, matFloor650, 900, 4.0), // above floor: in
                  new JobOrderMaterialStockRow(orderId, matFloor650, null, 99.0))); // null q: out

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

  // ---------------------------------------------------------------
  // findAllActiveReference — null-materials ternary
  // ---------------------------------------------------------------

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
      // M-2: the viewer-side profit gate short-circuits before the repository is even touched.
      when(ownerScopeService.canViewJobOrders()).thenReturn(false);

      assertTrue(queryService.findAllActiveReference(false).isEmpty());
      verify(jobOrderRepository, never()).findAllActiveWithMaterials();
    }

    @Test
    void ordersOutOfScope_areFilteredOut() {
      // M-2: a squadron-private order the caller may not see is dropped from the typeahead so it
      // cannot enumerate a foreign squadron's order handle + materials.
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

  // ---------------------------------------------------------------
  // getJobOrderById
  // ---------------------------------------------------------------

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

  // ---------------------------------------------------------------
  // addAssignee
  // ---------------------------------------------------------------

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
      // A user is an assignee at most once per order; re-adding the same user is a no-op that
      // skips both the user lookup and the save.
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

  // ---------------------------------------------------------------
  // removeAssignee
  // ---------------------------------------------------------------

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
      // removeIf finds nothing but does not throw — the save happens regardless. The remove path
      // no longer looks the user up, so an unknown id is simply a no-op.
      JobOrder order = newJobOrder(JobOrderStatus.OPEN);
      // no assignee for USER_ID

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

  // ---------------------------------------------------------------
  // updateAssigneeNote / deleteAssigneeNote (REQ-ORDERS-013)
  // ---------------------------------------------------------------

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

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

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
