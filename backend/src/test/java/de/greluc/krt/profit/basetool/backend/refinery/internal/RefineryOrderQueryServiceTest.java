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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.blueprint.api.CraftabilitySlice;
import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefineryYield;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefineryYieldRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryYieldBonusDto;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link RefineryOrderQueryService}: the single-order read, the self, cross-user,
 * all-orders and mission lists, the pending yield slices and the per-location yield bonus.
 */
@ExtendWith(MockitoExtension.class)
class RefineryOrderQueryServiceTest {

  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private RefineryYieldRepository refineryYieldRepository;
  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AccessGateService accessGateService;
  @InjectMocks private RefineryOrderQueryService service;

  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final UUID MISSION_ID = UUID.randomUUID();

  @Nested
  class GetRefineryOrderTests {

    @Test
    void returnsOrder_whenPresent() {
      RefineryOrder order = new RefineryOrder();
      order.setId(ORDER_ID);
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

      assertSame(order, service.getRefineryOrder(ORDER_ID));
    }

    @Test
    void throwsNotFound_whenAbsent() {
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.getRefineryOrder(ORDER_ID));
    }
  }

  @Nested
  class ListAndPageTests {

    private final Pageable pageable = PageRequest.of(0, 10);

    @Test
    void getMyRefineryOrders_withEmptyStatusList_filtersOnEveryStatus() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(refineryOrderRepository.findOwnedFiltered(
              eq(OWNER_ID),
              eq(EnumSet.allOf(RefineryOrderStatus.class)),
              eq(false),
              any(Instant.class),
              isNull(),
              eq(pageable)))
          .thenReturn(page);

      assertEquals(
          1,
          service
              .getMyRefineryOrders(OWNER_ID, List.of(), false, null, pageable)
              .getTotalElements());
    }

    @Test
    void getMyRefineryOrders_withNullStatusListAndBlankQuery_filtersOnEveryStatusWithoutSearch() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(refineryOrderRepository.findOwnedFiltered(
              eq(OWNER_ID),
              eq(EnumSet.allOf(RefineryOrderStatus.class)),
              eq(false),
              any(Instant.class),
              isNull(),
              eq(pageable)))
          .thenReturn(page);

      assertEquals(
          1,
          service.getMyRefineryOrders(OWNER_ID, null, false, "   ", pageable).getTotalElements());
    }

    @Test
    void getMyRefineryOrders_withStatusesReadyAndQuery_passesThemAsOneFilteredQuery() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      Instant before = Instant.now();
      when(refineryOrderRepository.findOwnedFiltered(
              eq(OWNER_ID),
              eq(EnumSet.of(RefineryOrderStatus.OPEN)),
              eq(true),
              any(Instant.class),
              eq("%laranite%"),
              eq(pageable)))
          .thenReturn(page);

      assertEquals(
          1,
          service
              .getMyRefineryOrders(
                  OWNER_ID, List.of(RefineryOrderStatus.OPEN), true, " Laranite ", pageable)
              .getTotalElements());
      ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
      verify(refineryOrderRepository)
          .findOwnedFiltered(any(), any(), anyBoolean(), now.capture(), any(), any());
      assertFalse(now.getValue().isBefore(before));
    }

    @Test
    void searchPattern_escapesLikeWildcardsAndLowerCases() {
      assertEquals("%50\\%\\_a%", RefineryOrderQueryService.searchPattern("50%_A"));
      assertNull(RefineryOrderQueryService.searchPattern(null));
      assertNull(RefineryOrderQueryService.searchPattern(" "));
    }

    @Test
    void getUserRefineryOrdersScoped_adminAllScope_forwardsAdminAllScopeToScopedQuery() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(accessGateService.currentLogisticianScope())
          .thenReturn(new ScopePredicate(true, null, Set.of()));
      when(refineryOrderRepository.findByOwnerIdScoped(OWNER_ID, true, null, Set.of(), pageable))
          .thenReturn(page);

      assertEquals(1, service.getUserRefineryOrdersScoped(OWNER_ID, pageable).getTotalElements());
      verify(refineryOrderRepository, never())
          .findOwnedFiltered(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void getUserRefineryOrdersScoped_nonAdmin_forwardsOnlyTheLogisticianUnits() {
      UUID callerStaffelA = UUID.randomUUID();
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(accessGateService.currentLogisticianScope())
          .thenReturn(new ScopePredicate(false, null, Set.of(callerStaffelA)));
      when(refineryOrderRepository.findByOwnerIdScoped(
              OWNER_ID, false, null, Set.of(callerStaffelA), pageable))
          .thenReturn(page);

      assertEquals(1, service.getUserRefineryOrdersScoped(OWNER_ID, pageable).getTotalElements());
      verify(refineryOrderRepository, never())
          .findOwnedFiltered(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void getUserRefineryOrdersScoped_pinnedCaller_forwardsActiveOrgUnitId() {
      UUID pinned = UUID.randomUUID();
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(accessGateService.currentLogisticianScope())
          .thenReturn(new ScopePredicate(false, pinned, Set.of()));
      when(refineryOrderRepository.findByOwnerIdScoped(OWNER_ID, false, pinned, Set.of(), pageable))
          .thenReturn(page);

      assertEquals(1, service.getUserRefineryOrdersScoped(OWNER_ID, pageable).getTotalElements());
      verify(refineryOrderRepository, never())
          .findOwnedFiltered(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void getAllRefineryOrders_emptyStatusList_filtersOnEveryStatusInTheCallersScope() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
      when(refineryOrderRepository.findFilteredScoped(
              eq(EnumSet.allOf(RefineryOrderStatus.class)),
              eq(false),
              any(Instant.class),
              isNull(),
              eq(true),
              isNull(),
              eq(Set.of()),
              eq(pageable)))
          .thenReturn(page);

      assertEquals(
          1, service.getAllRefineryOrders(List.of(), false, null, pageable).getTotalElements());
    }

    @Test
    void getAllRefineryOrders_withStatusesReadyAndQuery_forwardsThemWithTheCallersScope() {
      UUID callerStaffel = UUID.randomUUID();
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN))
          .thenReturn(new ScopePredicate(false, null, Set.of(callerStaffel)));
      when(refineryOrderRepository.findFilteredScoped(
              eq(EnumSet.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS)),
              eq(true),
              any(Instant.class),
              eq("%arc-l1%"),
              eq(false),
              isNull(),
              eq(Set.of(callerStaffel)),
              eq(pageable)))
          .thenReturn(page);

      assertEquals(
          1,
          service
              .getAllRefineryOrders(
                  List.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS),
                  true,
                  "ARC-L1",
                  pageable)
              .getTotalElements());
    }

    @Test
    void getMissionRefineryOrdersForCaller_listsTheLogisticianUnitsAndTheCallersOwnOrdersOnce() {
      UUID squadronA = UUID.randomUUID();
      when(accessGateService.currentLogisticianScope())
          .thenReturn(new ScopePredicate(false, null, Set.of(squadronA)));
      RefineryOrder inUnitA = orderWithId();
      RefineryOrder ownElsewhere = orderWithId();
      when(refineryOrderRepository.findByMissionIdScoped(
              MISSION_ID, false, null, Set.of(squadronA)))
          .thenReturn(List.of(inUnitA));
      when(refineryOrderRepository.findByMissionIdAndOwnerId(MISSION_ID, OWNER_ID))
          .thenReturn(List.of(inUnitA, ownElsewhere));

      List<RefineryOrder> result = service.getMissionRefineryOrdersForCaller(MISSION_ID, OWNER_ID);

      assertEquals(List.of(inUnitA, ownElsewhere), result);
    }

    @Test
    void getMissionRefineryOrdersForCaller_withoutALogisticianGrant_returnsOnlyTheOwnOrders() {
      when(accessGateService.currentLogisticianScope())
          .thenReturn(new ScopePredicate(false, null, Set.of()));
      RefineryOrder own = orderWithId();
      when(refineryOrderRepository.findByMissionIdAndOwnerId(MISSION_ID, OWNER_ID))
          .thenReturn(List.of(own));

      assertEquals(List.of(own), service.getMissionRefineryOrdersForCaller(MISSION_ID, OWNER_ID));
      verify(refineryOrderRepository, never())
          .findByMissionIdScoped(any(), anyBoolean(), any(), any());
    }

    private static RefineryOrder orderWithId() {
      RefineryOrder order = new RefineryOrder();
      order.setId(UUID.randomUUID());
      return order;
    }
  }

  @Nested
  class OwnedOpenRefineryYieldSlicesTests {

    private final UUID scuMaterialId = UUID.randomUUID();
    private final UUID pieceMaterialId = UUID.randomUUID();

    @Test
    void poolsConvertsScuAndMergesAcrossOrders() {
      Material scuMat = materialWithType(scuMaterialId, QuantityType.SCU);
      Material pieceMat = materialWithType(pieceMaterialId, QuantityType.PIECE);

      RefineryOrder order1 = new RefineryOrder();
      order1.setGoods(new HashSet<>(Set.of(good(scuMat, 100, 250), good(pieceMat, 50, 7))));

      RefineryOrder order2 = new RefineryOrder();
      order2.setGoods(new HashSet<>(Set.of(good(scuMat, 100, 150))));

      when(refineryOrderRepository.findOwnedWithGoodsByStatusIn(
              OWNER_ID, List.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS)))
          .thenReturn(List.of(order1, order2));

      List<CraftabilitySlice> slices = service.pendingYieldSlices(OWNER_ID);

      assertEquals(2, slices.size(), "one slice per (material, quality) pair");
      assertEquals(
          4.0d,
          totalScuFor(slices, scuMaterialId, 100),
          1e-9,
          "250+150 units at 100 units/SCU pool to 2.5+1.5 = 4.0 SCU");
      assertEquals(
          7.0d,
          totalScuFor(slices, pieceMaterialId, 50),
          1e-9,
          "non-SCU output passes through unconverted");
    }

    @Test
    void skipsNullMaterialNullQualityNullQuantityNonPositiveScuAndNullGoods() {
      Material scuMat = materialWithType(scuMaterialId, QuantityType.SCU);

      RefineryGood valid = good(scuMat, 100, 300);
      RefineryGood nullMaterial = good(null, 100, 200);
      RefineryGood nullQuality = good(scuMat, null, 200);
      RefineryGood nullQuantity = good(scuMat, 100, null);
      RefineryGood zeroScu = good(scuMat, 100, 0);

      RefineryOrder order = new RefineryOrder();
      order.setGoods(
          new HashSet<>(Set.of(valid, nullMaterial, nullQuality, nullQuantity, zeroScu)));

      RefineryOrder nullGoodsOrder = new RefineryOrder();
      nullGoodsOrder.setGoods(null);

      when(refineryOrderRepository.findOwnedWithGoodsByStatusIn(
              OWNER_ID, List.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS)))
          .thenReturn(List.of(order, nullGoodsOrder));

      List<CraftabilitySlice> slices = service.pendingYieldSlices(OWNER_ID);

      assertEquals(1, slices.size(), "only the fully-populated positive-SCU good yields a slice");
      CraftabilitySlice only = slices.get(0);
      assertEquals(scuMaterialId, only.materialId());
      assertEquals(Integer.valueOf(100), only.quality());
      assertEquals(3.0d, only.totalScu(), 1e-9);
    }

    private Material materialWithType(UUID id, QuantityType type) {
      Material m = new Material();
      m.setId(id);
      m.setQuantityType(type);
      return m;
    }

    private RefineryGood good(Material outputMaterial, Integer quality, Integer outputQuantity) {
      RefineryGood g = new RefineryGood();
      g.setOutputMaterial(outputMaterial);
      g.setQuality(quality);
      g.setOutputQuantity(outputQuantity);
      return g;
    }

    private Double totalScuFor(List<CraftabilitySlice> slices, UUID materialId, int quality) {
      for (CraftabilitySlice slice : slices) {
        if (materialId.equals(slice.materialId())
            && Integer.valueOf(quality).equals(slice.quality())) {
          return slice.totalScu();
        }
      }
      return null;
    }
  }

  /**
   * Tests for the UEX-derived yield-bonus lookup. The contract: pick the right name field
   * (city.name vs spaceStation.name) and map back to {@code materialId → yieldBonus}; never
   * fabricate data for a location that has no city/station hook into the universe sync.
   */
  @Nested
  class GetYieldBonusByMaterialForLocationTests {

    @Test
    void nullLocation_returnsEmpty() {
      assertTrue(service.getYieldBonusByMaterialForLocation(null).isEmpty());
    }

    @Test
    void locationWithoutCityAndStation_returnsEmpty() {
      Location naked = new Location();
      naked.setId(UUID.randomUUID());
      naked.setName("Custom");

      Map<UUID, RefineryYieldBonusDto> result = service.getYieldBonusByMaterialForLocation(naked);

      assertTrue(result.isEmpty());
    }

    @Test
    void cityLocation_queriesByCityName_andReturnsMaterialBonusMap() {
      UUID matA = UUID.randomUUID();
      UUID matB = UUID.randomUUID();
      Material a = new Material();
      a.setId(matA);
      Material b = new Material();
      b.setId(matB);

      RefineryYield y1 = new RefineryYield();
      y1.setMaterial(a);
      y1.setYieldBonus(5);
      y1.setYieldBonusWeek(4);
      y1.setYieldBonusMonth(3);
      RefineryYield y2 = new RefineryYield();
      y2.setMaterial(b);
      y2.setYieldBonus(-3);

      City lorville = new City();
      lorville.setName("Lorville");

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      loc.setCity(lorville);

      when(refineryYieldRepository.findAllForLocation(eq("Lorville"), eq(null)))
          .thenReturn(List.of(y1, y2));

      Map<UUID, RefineryYieldBonusDto> result = service.getYieldBonusByMaterialForLocation(loc);

      assertEquals(2, result.size());
      assertEquals(new RefineryYieldBonusDto(5, 4, 3), result.get(matA));
      assertEquals(new RefineryYieldBonusDto(-3, null, null), result.get(matB));
    }

    @Test
    void spaceStationLocation_queriesByStationName() {
      UUID matA = UUID.randomUUID();
      Material a = new Material();
      a.setId(matA);

      RefineryYield y = new RefineryYield();
      y.setMaterial(a);
      y.setYieldBonus(2);

      SpaceStation arcL1 = new SpaceStation();
      arcL1.setName("ARC-L1 Wide Forest Station");

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      loc.setSpaceStation(arcL1);

      when(refineryYieldRepository.findAllForLocation(eq(null), eq("ARC-L1 Wide Forest Station")))
          .thenReturn(List.of(y));

      Map<UUID, RefineryYieldBonusDto> result = service.getYieldBonusByMaterialForLocation(loc);

      assertEquals(1, result.size());
      assertEquals(2, result.get(matA).percent());
    }

    @Test
    void zeroBonusValue_isPreserved_notTreatedAsMissing() {
      UUID matA = UUID.randomUUID();
      Material a = new Material();
      a.setId(matA);

      RefineryYield y = new RefineryYield();
      y.setMaterial(a);
      y.setYieldBonus(0);

      SpaceStation station = new SpaceStation();
      station.setName("CRU-L1");

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      loc.setSpaceStation(station);

      when(refineryYieldRepository.findAllForLocation(eq(null), eq("CRU-L1")))
          .thenReturn(List.of(y));

      Map<UUID, RefineryYieldBonusDto> result = service.getYieldBonusByMaterialForLocation(loc);

      assertTrue(result.containsKey(matA));
      assertEquals(0, result.get(matA).percent());
    }

    @Test
    void byLocationId_nullId_returnsEmpty() {
      assertTrue(service.getYieldBonusByMaterialForLocationId(null).isEmpty());
    }

    @Test
    void byLocationId_unknownId_returnsEmpty() {
      UUID missing = UUID.randomUUID();
      when(locationRepository.findById(missing)).thenReturn(Optional.empty());

      Map<UUID, RefineryYieldBonusDto> result =
          service.getYieldBonusByMaterialForLocationId(missing);

      assertTrue(result.isEmpty());
    }

    @Test
    void byLocationId_delegatesToLocationVariant() {
      UUID matA = UUID.randomUUID();
      Material a = new Material();
      a.setId(matA);

      RefineryYield y = new RefineryYield();
      y.setMaterial(a);
      y.setYieldBonus(7);

      City lorville = new City();
      lorville.setName("Lorville");

      UUID locId = UUID.randomUUID();
      Location loc = new Location();
      loc.setId(locId);
      loc.setCity(lorville);

      when(locationRepository.findById(locId)).thenReturn(Optional.of(loc));
      when(refineryYieldRepository.findAllForLocation(eq("Lorville"), eq(null)))
          .thenReturn(List.of(y));

      Map<UUID, RefineryYieldBonusDto> result = service.getYieldBonusByMaterialForLocationId(locId);

      assertEquals(1, result.size());
      assertEquals(7, result.get(matA).percent());
    }

    @Test
    void yieldWithNullMaterial_isSkipped() {
      RefineryYield orphan = new RefineryYield();
      orphan.setMaterial(null);
      orphan.setYieldBonus(99);

      City city = new City();
      city.setName("Area18");

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      loc.setCity(city);

      when(refineryYieldRepository.findAllForLocation(eq("Area18"), eq(null)))
          .thenReturn(List.of(orphan));

      Map<UUID, RefineryYieldBonusDto> result = service.getYieldBonusByMaterialForLocation(loc);

      assertTrue(result.isEmpty());
    }
  }
}
