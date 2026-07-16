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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryStackDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryItemStackAggregate;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Coverage for the {@code catalog=ITEM} read family of {@link InventoryAggregationService} — {@link
 * InventoryAggregationService#getMyAggregatedItemInventory}, {@link
 * InventoryAggregationService#getAllAggregatedItemInventory}, {@link
 * InventoryAggregationService#getAggregatedItemInventory} and {@link
 * InventoryAggregationService#getAllItemInventory} (V220, REQ-INV-028/029) with the private item
 * assembly ({@code buildGroupedFromItemStacks} / {@code buildItemGroup} / {@code
 * mapItemAggregateRefs}). The sibling {@code InventoryItemServiceAggregateTest} pins the material
 * assembly only; the controller tests mock this service and the Testcontainers data tests stop at
 * the SQL projection, so the item grouping logic itself had no unit coverage. Verified over mocked
 * repositories:
 *
 * <ul>
 *   <li>filter routing: {@code gameItemIds} / {@code jobOrderIds} flip the corresponding {@code
 *       hasX} flag (an empty list counts as no filter), the mutually exclusive personal-narrowing
 *       toggles pass through unchanged, and the squadron-wide variants forward the caller's {@link
 *       ScopePredicate} triple verbatim;
 *   <li>assembly: the per-stack aggregates group into one {@link GroupedInventoryDto} per game item
 *       (summed total, per-stack entry counts, {@code null} quality figures — items carry no
 *       quality dimension), the items sort alphabetically by name, and the stacks reuse the shared
 *       material comparator whose quality key coalesces the item stacks' constant {@code null} to
 *       zero, degrading the order to location asc / amount desc without an NPE;
 *   <li>tuple mapping: the aggregated view projects raw {@code Object[]} rows into game-item {@link
 *       AggregatedInventoryDto}s with null-coalesced sums, and the flat list maps entities through
 *       the inventory-item mapper.
 * </ul>
 *
 * <p>The SQL grouping itself (catalog split, quality-less stack key, the appended {@code
 * gameItem.name} sort on Postgres) is a data-layer concern covered by {@code
 * InventoryItemStackQueryDataTest}, not by this mocked unit.
 */
@ExtendWith(MockitoExtension.class)
class InventoryAggregationServiceTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private MaterialMapper materialMapper;
  @Mock private OwnerScopeService ownerScopeService;

  @InjectMocks private InventoryAggregationService service;

  // ---------------------------------------------------------------------
  // filter routing — item stack queries
  // ---------------------------------------------------------------------

  @Nested
  class FilterRoutingTests {

    // covers REQ-INV-029 (my grouped item view: null filters pass false flags and null lists)
    @Test
    void myItemStacks_allFiltersNull_passFalseFlagsAndNullLists() {
      UUID userId = UUID.randomUUID();
      stubUser(userId);
      stubUserItemStacks();

      service.getMyAggregatedItemInventory(userId, null, null, false, false);

      verify(inventoryItemRepository)
          .findUserItemStacks(
              eq(userId), eq(false), isNull(), eq(false), isNull(), eq(false), eq(false));
    }

    // covers REQ-INV-029 (my grouped item view: empty filter lists count as no filter)
    @Test
    void myItemStacks_emptyFilterLists_treatedAsNoFilter() {
      UUID userId = UUID.randomUUID();
      stubUser(userId);
      stubUserItemStacks();

      service.getMyAggregatedItemInventory(userId, List.of(), List.of(), false, false);

      verify(inventoryItemRepository)
          .findUserItemStacks(
              eq(userId), eq(false), isNull(), eq(false), isNull(), eq(false), eq(false));
    }

    // covers REQ-INV-029 (my grouped item view: ids set the flags, toggles pass through)
    @Test
    void myItemStacks_nonEmptyFilters_setFlagsAndForwardIdsWithToggles() {
      UUID userId = UUID.randomUUID();
      UUID gameItemId = UUID.randomUUID();
      UUID jobOrderId = UUID.randomUUID();
      stubUser(userId);
      stubUserItemStacks();

      service.getMyAggregatedItemInventory(
          userId, List.of(gameItemId), List.of(jobOrderId), true, false);

      verify(inventoryItemRepository)
          .findUserItemStacks(
              eq(userId),
              eq(true),
              eq(List.of(gameItemId)),
              eq(true),
              eq(List.of(jobOrderId)),
              eq(true),
              eq(false));
    }

    // covers REQ-INV-029 (owner resolution precedes the stack query — unknown user is a 404)
    @Test
    void myItemStacks_unknownUser_throwsNotFound_withoutQuerying() {
      UUID userId = UUID.randomUUID();
      when(userRepository.findById(userId)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> service.getMyAggregatedItemInventory(userId, null, null, false, false));

      verifyNoInteractions(inventoryItemRepository);
    }

    // covers REQ-INV-029 (squadron-wide grouped item view forwards the caller's scope triple)
    @Test
    void allItemStacks_forwardScopeTriple() {
      UUID activeOrgUnitId = UUID.randomUUID();
      UUID memberOrgUnitId = UUID.randomUUID();
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(false, activeOrgUnitId, Set.of(memberOrgUnitId)));
      when(inventoryItemRepository.findGlobalItemStacks(
              anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
          .thenReturn(List.of());

      service.getAllAggregatedItemInventory(null, null);

      // The org-unit scope from OwnerScopeService is the only visibility gate of the wide read —
      // the triple must reach the repository verbatim, never widened to an implicit admin scope.
      verify(inventoryItemRepository)
          .findGlobalItemStacks(
              eq(false),
              isNull(),
              eq(false),
              isNull(),
              eq(false),
              eq(activeOrgUnitId),
              eq(Set.of(memberOrgUnitId)));
    }
  }

  // ---------------------------------------------------------------------
  // assembly — GameItem -> Stack grouping, ordering, null safety
  // ---------------------------------------------------------------------

  @Nested
  class AssemblyTests {

    // covers REQ-INV-028/029 (stacks of one item collapse into one group with the summed total)
    @Test
    void stacksOfOneGameItem_collapseIntoOneGroup_withSummedTotalAndEntryCounts() {
      GameItem coupling = gameItem("Coupling");
      stubGlobalItemStacks(
          agg(coupling, location("ARC-L1"), 2.0, 2L), agg(coupling, location("ARC-L2"), 3.0, 1L));
      stubItemRefMapper();

      List<GroupedInventoryDto> result = service.getAllAggregatedItemInventory(null, null);

      assertEquals(1, result.size());
      GroupedInventoryDto group = result.get(0);
      assertEquals("Coupling", group.gameItem().name());
      assertNull(group.material(), "a game-item group carries no material reference");
      assertEquals(5.0, group.totalAmount());
      // Items carry no quality dimension — the material group's quality figures stay null
      // (REQ-INV-028) instead of surfacing a misleading 0.
      assertNull(group.averageQuality(), "no quality dimension on the item catalog");
      assertNull(group.maxQuality());
      assertEquals(2, group.stacks().size());
      assertEquals(2, group.stacks().get(0).entryCount(), "ARC-L1 stack collapses two entries");
      assertEquals(1, group.stacks().get(1).entryCount());
    }

    // covers REQ-INV-029 (item groups sort alphabetically by item name)
    @Test
    void groups_sortedAlphabeticallyByItemName_withPerItemTotals() {
      GameItem shield = gameItem("Shield");
      GameItem coupling = gameItem("Coupling");
      stubGlobalItemStacks(
          agg(shield, location("L"), 7.0, 1L), agg(coupling, location("L"), 5.0, 1L));
      stubItemRefMapper();

      List<GroupedInventoryDto> result = service.getAllAggregatedItemInventory(null, null);

      assertEquals(
          List.of("Coupling", "Shield"), result.stream().map(g -> g.gameItem().name()).toList());
      assertEquals(5.0, result.get(0).totalAmount());
      assertEquals(7.0, result.get(1).totalAmount());
    }

    // covers REQ-INV-029 (shared stack comparator is null-quality safe for item stacks)
    @Test
    void stacksWithinItem_nullQualityKeyIsSafe_orderedByLocationAscAmountDesc() {
      GameItem coupling = gameItem("Coupling");
      stubGlobalItemStacks(
          agg(coupling, location("B"), 10.0, 1L),
          agg(coupling, location("A"), 10.0, 1L),
          agg(coupling, location("A"), 20.0, 1L));
      stubItemRefMapper();

      List<InventoryStackDto> stacks =
          service.getAllAggregatedItemInventory(null, null).get(0).stacks();

      // The shared STACK_ORDER comparator reads a constant null quality for item stacks and
      // coalesces it to 0 — the order degrades to location asc / amount desc and never NPEs.
      assertEquals("A", stacks.get(0).location().name());
      assertEquals(20.0, stacks.get(0).totalAmount(), "location A, larger amount first");
      assertEquals("A", stacks.get(1).location().name());
      assertEquals(10.0, stacks.get(1).totalAmount());
      assertEquals("B", stacks.get(2).location().name(), "location B after location A");
      assertTrue(
          stacks.stream().allMatch(s -> s.quality() == null), "item stacks expose no quality key");
    }

    // covers REQ-INV-029 (null SQL aggregates coalesce to zero instead of NPEing the roll-up)
    @Test
    void nullAggregateNumbers_coalesceToZero() {
      GameItem coupling = gameItem("Coupling");
      stubGlobalItemStacks(agg(coupling, location("A"), null, null));
      stubItemRefMapper();

      GroupedInventoryDto group = service.getAllAggregatedItemInventory(null, null).get(0);

      assertEquals(0.0, group.totalAmount(), "a null SUM coalesces to 0.0");
      assertEquals(0.0, group.stacks().get(0).totalAmount());
      assertEquals(0, group.stacks().get(0).entryCount(), "a null COUNT coalesces to 0");
    }

    // covers REQ-INV-029 (no stock in scope yields an empty group list, not a null/NPE)
    @Test
    void noStacks_yieldEmptyGroupList() {
      stubGlobalItemStacks();

      assertTrue(service.getAllAggregatedItemInventory(null, null).isEmpty());
    }

    // covers REQ-INV-029 (the owner-scoped variant assembles the identical group shape)
    @Test
    void myItemStacks_assembleTheSameGroupShape() {
      UUID userId = UUID.randomUUID();
      stubUser(userId);
      GameItem coupling = gameItem("Coupling");
      when(inventoryItemRepository.findUserItemStacks(
              any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean()))
          .thenReturn(List.of(agg(coupling, location("A"), 4.0, 3L)));
      stubItemRefMapper();

      List<GroupedInventoryDto> result =
          service.getMyAggregatedItemInventory(userId, null, null, false, false);

      assertEquals(1, result.size());
      assertEquals("Coupling", result.get(0).gameItem().name());
      assertEquals(4.0, result.get(0).totalAmount());
      assertEquals(3, result.get(0).stacks().get(0).entryCount());
    }
  }

  // ---------------------------------------------------------------------
  // aggregated tuple view — Object[] -> AggregatedInventoryDto
  // ---------------------------------------------------------------------

  @Nested
  class AggregatedTupleViewTests {

    // covers REQ-INV-028 (aggregated item rows carry the game-item ref and null quality columns)
    @Test
    void mapsTuples_toGameItemRows_withNullQualityColumns() {
      GameItem coupling = gameItem("Coupling");
      InventoryGameItemReferenceDto ref =
          new InventoryGameItemReferenceDto(coupling.getId(), "Coupling", null, null);
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(true, null, Set.of()));
      when(inventoryItemRepository.getAggregatedItemInventory(
              anyBoolean(), any(), any(), any(Pageable.class)))
          .thenReturn(new PageImpl<>(List.<Object[]>of(new Object[] {coupling, 5.0})));
      when(inventoryItemMapper.gameItemToReferenceDto(coupling)).thenReturn(ref);

      Page<AggregatedInventoryDto> page = service.getAggregatedItemInventory(PageRequest.of(0, 20));

      AggregatedInventoryDto row = page.getContent().get(0);
      assertEquals(ref, row.gameItem());
      assertNull(row.material(), "an item row carries no material");
      assertNull(row.quality(), "no quality dimension on the item catalog");
      assertNull(row.maxQuality());
      assertEquals(5.0, row.amount());
    }

    // covers REQ-INV-028 (a null SUM tuple coalesces to a 0.0 amount)
    @Test
    void nullSum_coalescesToZero() {
      GameItem coupling = gameItem("Coupling");
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(true, null, Set.of()));
      when(inventoryItemRepository.getAggregatedItemInventory(
              anyBoolean(), any(), any(), any(Pageable.class)))
          .thenReturn(new PageImpl<>(List.<Object[]>of(new Object[] {coupling, null})));
      when(inventoryItemMapper.gameItemToReferenceDto(coupling))
          .thenReturn(new InventoryGameItemReferenceDto(coupling.getId(), "Coupling", null, null));

      Page<AggregatedInventoryDto> page = service.getAggregatedItemInventory(PageRequest.of(0, 20));

      assertEquals(0.0, page.getContent().get(0).amount());
    }

    // covers REQ-INV-028/029 (the aggregated read forwards scope triple and pageable verbatim)
    @Test
    void forwardsScopeTripleAndPageable() {
      UUID activeOrgUnitId = UUID.randomUUID();
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(false, activeOrgUnitId, Set.of()));
      when(inventoryItemRepository.getAggregatedItemInventory(
              anyBoolean(), any(), any(), any(Pageable.class)))
          .thenReturn(Page.empty());
      Pageable pageable = PageRequest.of(1, 10);

      service.getAggregatedItemInventory(pageable);

      verify(inventoryItemRepository)
          .getAggregatedItemInventory(eq(false), eq(activeOrgUnitId), eq(Set.of()), eq(pageable));
    }
  }

  // ---------------------------------------------------------------------
  // flat squadron-wide item list
  // ---------------------------------------------------------------------

  @Nested
  class FlatItemListTests {

    // covers REQ-INV-029 (flat catalog=ITEM /all: filter flags + scope triple + mapper projection)
    @Test
    void allItemInventory_forwardsFiltersAndMapsRows() {
      UUID gameItemId = UUID.randomUUID();
      UUID memberOrgUnitId = UUID.randomUUID();
      InventoryItem row = new InventoryItem();
      InventoryItemDto dto =
          new InventoryItemDto(
              UUID.randomUUID(),
              null,
              null,
              null,
              null,
              null,
              3.0,
              false,
              List.of(),
              0.0,
              List.of(),
              0.0,
              null,
              null,
              1L,
              null);
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(false, null, Set.of(memberOrgUnitId)));
      when(inventoryItemRepository.findGlobalItemsByFilters(
              anyBoolean(),
              any(),
              anyBoolean(),
              any(),
              anyBoolean(),
              any(),
              any(),
              any(Pageable.class)))
          .thenReturn(new PageImpl<>(List.of(row)));
      when(inventoryItemMapper.toDto(row)).thenReturn(dto);

      Page<InventoryItemDto> page =
          service.getAllItemInventory(List.of(gameItemId), null, PageRequest.of(0, 20));

      assertEquals(List.of(dto), page.getContent());
      verify(inventoryItemRepository)
          .findGlobalItemsByFilters(
              eq(true),
              eq(List.of(gameItemId)),
              eq(false),
              isNull(),
              eq(false),
              isNull(),
              eq(Set.of(memberOrgUnitId)),
              any(Pageable.class));
    }
  }

  // ---------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------

  /**
   * Stubs the user lookup so the owner-scoped methods resolve the given id to a managed {@link
   * User} carrying exactly that id (the services re-read {@code user.getId()} for the query).
   *
   * @param userId the owner id the test drives the service with
   */
  private void stubUser(UUID userId) {
    User user = new User();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
  }

  /** Stubs the owner-scoped item stack query to return no stacks (filter-routing verifications). */
  private void stubUserItemStacks() {
    when(inventoryItemRepository.findUserItemStacks(
            any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean()))
        .thenReturn(List.of());
  }

  /**
   * Stubs the squadron-wide item stack query to return the given per-stack aggregates under an
   * admin all-scope predicate (the scope stub is lenient because the routing tests override it).
   *
   * @param aggregates the SQL-shaped per-stack rows the mocked repository yields
   */
  private void stubGlobalItemStacks(InventoryItemStackAggregate... aggregates) {
    lenient()
        .when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(inventoryItemRepository.findGlobalItemStacks(
            anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
        .thenReturn(List.of(aggregates));
  }

  /**
   * Rigs the inventory-item mapper used by {@code mapItemAggregateRefs}: the service feeds it a
   * transient probe {@link InventoryItem} carrying the item stack's identity entities, so the mock
   * reads the probe's game item / location / personal flag back into the reference DTOs the
   * assembly needs. The probe's material and quality are a constant {@code null} on the item path
   * (REQ-INV-029) and stay null in the DTO; only the fields the assertions touch are projected.
   */
  private void stubItemRefMapper() {
    lenient()
        .when(inventoryItemMapper.toDto(any(InventoryItem.class)))
        .thenAnswer(
            invocation -> {
              InventoryItem probe = invocation.getArgument(0);
              GameItem gameItem = probe.getGameItem();
              Location location = probe.getLocation();
              return new InventoryItemDto(
                  null,
                  null,
                  null,
                  gameItem != null
                      ? new InventoryGameItemReferenceDto(
                          gameItem.getId(), gameItem.getName(), null, null)
                      : null,
                  location != null
                      ? new LocationReferenceDto(location.getId(), location.getName())
                      : null,
                  probe.getQuality(),
                  null,
                  probe.getPersonal(),
                  List.of(),
                  0.0,
                  List.of(),
                  0.0,
                  null,
                  null,
                  null,
                  null);
            });
  }

  /**
   * Builds one SQL-shaped per-stack item aggregate: the quality-less item stack key (game item,
   * location, non-personal, no owning org unit) with the given pre-computed sums. {@code Double} /
   * {@code Long} boxes deliberately, so the null-coalescing test can feed {@code null} aggregates.
   *
   * @param gameItem the stack's grouping game item
   * @param location the stack's storage location
   * @param totalAmount the pre-summed amount, or {@code null} to exercise the coalescing guard
   * @param entryCount the pre-computed entry count, or {@code null} likewise
   * @return the hand-built projection row
   */
  private static InventoryItemStackAggregate agg(
      GameItem gameItem, Location location, Double totalAmount, Long entryCount) {
    return new InventoryItemStackAggregate(
        gameItem, null, location, false, null, totalAmount, entryCount);
  }

  /**
   * Builds a detached {@link GameItem} with a deterministic name-derived id, so grouping by item id
   * and asserting by item name stay in lockstep.
   *
   * @param name the item's display name (also the id seed)
   * @return the populated catalogue entity
   */
  private static GameItem gameItem(String name) {
    GameItem gameItem = new GameItem();
    gameItem.setId(UUID.nameUUIDFromBytes(("item:" + name).getBytes(StandardCharsets.UTF_8)));
    gameItem.setName(name);
    return gameItem;
  }

  /**
   * Builds a detached {@link Location} with a deterministic name-derived id for the stack key.
   *
   * @param name the location's display name (also the id seed)
   * @return the populated location entity
   */
  private static Location location(String name) {
    Location location = new Location();
    location.setId(UUID.nameUUIDFromBytes(("loc:" + name).getBytes(StandardCharsets.UTF_8)));
    location.setName(name);
    return location;
  }
}
