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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryStackDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Coverage for {@link InventoryItemService#getAllAggregatedInventory} and the private assembly
 * helpers ({@code buildGroupedFromStacks} / {@code buildMaterialGroup} / {@code mapAggregateRefs}).
 * Since the append-only Lager moved its grouping and aggregate math into SQL (ADR-0003,
 * REQ-INV-002), the database now returns one {@link InventoryStackAggregate} per stock identity
 * with {@code SUM(amount)}, the amount-weighted quality sum, {@code MAX(quality)} and the entry
 * count already computed. This unit test therefore verifies the two remaining service
 * responsibilities over a mocked repository:
 *
 * <ul>
 *   <li>filter routing: each of {@code materialIds} / {@code minQuality} / {@code jobOrderIds} /
 *       {@code missionIds} flips the corresponding {@code hasX} flag and is or isn't forwarded to
 *       {@link InventoryItemRepository#findGlobalStacks};
 *   <li>assembly: the per-stack aggregates are grouped into materials, each stack's mean quality is
 *       derived as {@code weightedQualitySum / totalAmount} (rounded HALF_UP to two decimals), the
 *       stacks are ordered quality-desc / location-asc / amount-desc, and the materials are ordered
 *       alphabetically — with the material-wide totals accumulated from the raw SQL sums.
 * </ul>
 *
 * <p>The correctness of the SQL grouping itself (that two rows differing only in owning squadron or
 * the personal flag form separate stacks, that the {@code GROUP BY} executes on Postgres) is a
 * data-layer concern covered by {@code InventoryItemStackQueryTest} and the seeded integration
 * tests, not by this mocked unit.
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemServiceAggregateTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private MissionFinanceEntryRepository missionFinanceEntryRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private MaterialMapper materialMapper;
  @Mock private OwnerScopeService ownerScopeService;

  @InjectMocks private InventoryAggregationService service;

  // ---------------------------------------------------------------------
  // filter routing
  // ---------------------------------------------------------------------

  @Nested
  class FilterRoutingTests {

    @Test
    void allFiltersNull_passesFalseFlagsAndNullLists() {
      stubFindGlobalStacks();

      service.getAllAggregatedInventory(null, null);

      verify(inventoryItemRepository)
          .findGlobalStacks(
              eq(false),
              isNull(),
              isNull(),
              eq(false),
              isNull(),
              eq(false),
              isNull(),
              anyBoolean(),
              any(),
              any());
    }

    @Test
    void emptyMaterialList_treatedAsNoFilter() {
      stubFindGlobalStacks();

      service.getAllAggregatedInventory(List.of(), null);

      verify(inventoryItemRepository)
          .findGlobalStacks(
              eq(false),
              isNull(),
              isNull(),
              eq(false),
              isNull(),
              eq(false),
              isNull(),
              anyBoolean(),
              any(),
              any());
    }

    @Test
    void nonEmptyMaterialList_setsFlagAndPassesIds() {
      UUID matId = UUID.randomUUID();
      stubFindGlobalStacks();

      service.getAllAggregatedInventory(List.of(matId), null);

      verify(inventoryItemRepository)
          .findGlobalStacks(
              eq(true),
              eq(List.of(matId)),
              isNull(),
              eq(false),
              isNull(),
              eq(false),
              isNull(),
              anyBoolean(),
              any(),
              any());
    }

    @Test
    void jobOrderAndMissionLists_routedSeparately() {
      UUID jobId = UUID.randomUUID();
      UUID missionId = UUID.randomUUID();
      stubFindGlobalStacks();

      service.getAllAggregatedInventory(null, 500, List.of(jobId), List.of(missionId));

      verify(inventoryItemRepository)
          .findGlobalStacks(
              eq(false),
              isNull(),
              eq(500),
              eq(true),
              eq(List.of(jobId)),
              eq(true),
              eq(List.of(missionId)),
              anyBoolean(),
              any(),
              any());
    }
  }

  // ---------------------------------------------------------------------
  // assembly — material grouping, weighted average, ordering
  // ---------------------------------------------------------------------

  @Nested
  class AssemblyTests {

    @Test
    void singleStack_singleMaterial_isProjectedWithItsAggregates() {
      Material mat = material("Quantanium");
      // total 100 @ quality 500 -> weighted sum 50000 -> mean 500
      stubGlobalStacks(agg(mat, location("ARC-L1"), 500, 100.0, 50_000.0, 500, 1));
      stubRefMapper();

      List<GroupedInventoryDto> result = service.getAllAggregatedInventory(null, null);

      assertEquals(1, result.size());
      GroupedInventoryDto group = result.get(0);
      assertEquals("Quantanium", group.material().name());
      assertEquals(100.0, group.totalAmount());
      assertEquals(500.0, group.averageQuality());
      assertEquals(500, group.maxQuality());
      assertEquals(1, group.stacks().size());
      InventoryStackDto stack = group.stacks().get(0);
      assertEquals("ARC-L1", stack.location().name());
      assertEquals(500, stack.quality());
      assertEquals(100.0, stack.totalAmount());
      assertEquals(500.0, stack.averageQuality());
      assertEquals(1, stack.entryCount());
    }

    @Test
    void weightedAverageQuality_isAccumulatedFromRawSqlSums() {
      Material mat = material("Quantanium");
      // (amount 100, q 400 -> wsum 40000) and (amount 300, q 500 -> wsum 150000)
      // material mean = (40000 + 150000) / (100 + 300) = 475
      stubGlobalStacks(
          agg(mat, location("ARC-L1"), 400, 100.0, 40_000.0, 400, 1),
          agg(mat, location("ARC-L2"), 500, 300.0, 150_000.0, 500, 1));
      stubRefMapper();

      GroupedInventoryDto group = service.getAllAggregatedInventory(null, null).get(0);
      assertEquals(400.0, group.totalAmount());
      assertEquals(475.0, group.averageQuality(), "weighted mean = 190000 / 400 = 475.0");
      assertEquals(500, group.maxQuality());
    }

    @Test
    void perStackMeanQuality_roundedHalfUpToTwoDecimals() {
      Material mat = material("Quantanium");
      // wsum 451 over total 3 -> 150.333... -> 150.33
      stubGlobalStacks(agg(mat, location("ARC-L1"), 200, 3.0, 451.0, 200, 3));
      stubRefMapper();

      InventoryStackDto stack =
          service.getAllAggregatedInventory(null, null).get(0).stacks().get(0);
      assertEquals(150.33, stack.averageQuality(), "Math.round(150.3333 * 100) / 100 = 150.33");
    }

    @Test
    void totalAmountZero_meanQualityIsZeroNotNaN() {
      Material mat = material("Quantanium");
      stubGlobalStacks(agg(mat, location("ARC-L1"), 500, 0.0, 0.0, 500, 1));
      stubRefMapper();

      GroupedInventoryDto group = service.getAllAggregatedInventory(null, null).get(0);
      assertEquals(0.0, group.averageQuality(), "div-by-zero guard clamps to 0.0, not NaN");
      assertNotNull(group.averageQuality());
      assertTrue(!Double.isNaN(group.averageQuality()));
      assertEquals(0.0, group.stacks().get(0).averageQuality());
    }

    @Test
    void stacksWithinMaterial_orderedByQualityDescLocationAscAmountDesc() {
      Material mat = material("Quantanium");
      InventoryStackAggregate a = agg(mat, location("B"), 500, 10.0, 5_000.0, 500, 1);
      InventoryStackAggregate b = agg(mat, location("A"), 500, 10.0, 5_000.0, 500, 1);
      InventoryStackAggregate c = agg(mat, location("A"), 500, 20.0, 10_000.0, 500, 1);
      InventoryStackAggregate d = agg(mat, location("A"), 300, 100.0, 30_000.0, 300, 1);
      stubGlobalStacks(d, a, b, c);
      stubRefMapper();

      List<InventoryStackDto> stacks =
          service.getAllAggregatedInventory(null, null).get(0).stacks();

      // q500 first (locA amt20, locA amt10, locB amt10), then q300 last
      assertEquals("A", stacks.get(0).location().name());
      assertEquals(20.0, stacks.get(0).totalAmount(), "locA q500 highest amount first");
      assertEquals("A", stacks.get(1).location().name());
      assertEquals(10.0, stacks.get(1).totalAmount());
      assertEquals("B", stacks.get(2).location().name(), "locB after locA at equal quality");
      assertEquals(300, stacks.get(3).quality(), "lowest quality sorts last");
    }

    @Test
    void materials_sortedAlphabeticallyByName() {
      stubGlobalStacks(
          agg(material("Laranite"), location("L"), 500, 10.0, 5_000.0, 500, 1),
          agg(material("Agricium"), location("L"), 500, 10.0, 5_000.0, 500, 1),
          agg(material("Zeyneh"), location("L"), 500, 10.0, 5_000.0, 500, 1),
          agg(material("Beryl"), location("L"), 500, 10.0, 5_000.0, 500, 1));
      stubRefMapper();

      List<GroupedInventoryDto> result = service.getAllAggregatedInventory(null, null);

      assertEquals(
          List.of("Agricium", "Beryl", "Laranite", "Zeyneh"),
          result.stream().map(g -> g.material().name()).toList());
    }
  }

  // ---------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------

  /** Stubs the scoped stack query to return no stacks (for the filter-routing verifications). */
  private void stubFindGlobalStacks() {
    lenient()
        .when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    lenient()
        .when(
            inventoryItemRepository.findGlobalStacks(
                anyBoolean(),
                any(),
                any(),
                anyBoolean(),
                any(),
                anyBoolean(),
                any(),
                anyBoolean(),
                any(),
                any()))
        .thenReturn(List.of());
  }

  /** Stubs the scoped stack query to return the given per-stack aggregates. */
  private void stubGlobalStacks(InventoryStackAggregate... aggregates) {
    lenient()
        .when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(inventoryItemRepository.findGlobalStacks(
            anyBoolean(),
            any(),
            any(),
            anyBoolean(),
            any(),
            anyBoolean(),
            any(),
            anyBoolean(),
            any(),
            any()))
        .thenReturn(List.of(aggregates));
  }

  /**
   * Rigs the inventory-item mapper used by {@code mapAggregateRefs}: the service feeds it a
   * transient probe {@link InventoryItem} carrying the stack's identity entities, so the mock reads
   * the probe's material / location / quality back into the reference DTOs the assembly needs. Only
   * the fields the assertions touch are projected; the rest stay null.
   */
  private void stubRefMapper() {
    lenient()
        .when(inventoryItemMapper.toDto(any(InventoryItem.class)))
        .thenAnswer(
            invocation -> {
              InventoryItem probe = invocation.getArgument(0);
              Material m = probe.getMaterial();
              Location l = probe.getLocation();
              return new InventoryItemDto(
                  null,
                  null,
                  m != null
                      ? new MaterialReferenceDto(m.getId(), m.getName(), m.getQuantityType())
                      : null,
                  l != null ? new LocationReferenceDto(l.getId(), l.getName()) : null,
                  probe.getQuality(),
                  null,
                  probe.getPersonal(),
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
                  null,
                  null);
            });
  }

  private static InventoryStackAggregate agg(
      Material material,
      Location location,
      Integer quality,
      double totalAmount,
      double weightedQualitySum,
      int maxQuality,
      long entryCount) {
    return new InventoryStackAggregate(
        material,
        null,
        location,
        quality,
        false,
        null,
        totalAmount,
        weightedQualitySum,
        maxQuality,
        entryCount);
  }

  private static Material material(String name) {
    Material m = new Material();
    m.setId(UUID.nameUUIDFromBytes(("mat:" + name).getBytes(StandardCharsets.UTF_8)));
    m.setName(name);
    m.setQuantityType(QuantityType.SCU);
    return m;
  }

  private static Location location(String name) {
    Location l = new Location();
    l.setId(UUID.nameUUIDFromBytes(("loc:" + name).getBytes(StandardCharsets.UTF_8)));
    l.setName(name);
    return l;
  }
}
