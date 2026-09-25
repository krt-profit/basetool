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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.MissionParticipantRequiredException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.RefineryGood;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Lifecycle / CRUD test for {@link RefineryOrderService}, complementing the existing {@code
 * RefineryOrderServiceTest} which focuses on {@code storeRefineryOrder}. Covers:
 *
 * <ul>
 *   <li>{@link RefineryOrderService#getRefineryOrder} not-found path.
 *   <li>{@link RefineryOrderService#getMyRefineryOrders} (self list, with/without status filter).
 *   <li>{@link RefineryOrderService#getUserRefineryOrdersScoped} (cross-user oversight list — the
 *       org-unit-scoped path that closes finding SEC-01).
 *   <li>{@link RefineryOrderService#getAllRefineryOrders} (both overloads and with/without status
 *       filter).
 *   <li>{@link RefineryOrderService#getMissionRefineryOrdersScoped} (org-unit-scoped logistician
 *       path) and {@link RefineryOrderService#getMissionRefineryOrders(UUID, UUID)} (owner-filtered
 *       path).
 *   <li>{@link RefineryOrderService#createRefineryOrder} — every validation branch (User / Location
 *       / Mission / RefiningMethod lookups, location-must-have-refinery, goods validation including
 *       RAW-input-only, output-must-match-refined-of-input, output fallback chain), plus the {@code
 *       zeroToNull} normalisation of the optional money fields.
 *   <li>{@link RefineryOrderService#updateRefineryOrder} — version-check (which fires
 *       <em>before</em> the owner check), owner-check (non-logistician), logistician bypass,
 *       partial-update semantics (Location / Mission / RefiningMethod set or cleared), goods
 *       replacement.
 *   <li>{@link RefineryOrderService#deleteRefineryOrder} — actually a status flip to CANCELED, plus
 *       owner-check / logistician bypass.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RefineryOrderServiceLifecycleTest {

  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private UserRepository userRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private RefiningMethodRepository refiningMethodRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuditService auditService;
  @InjectMocks private RefineryOrderService service;

  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final UUID OTHER_USER_ID = UUID.randomUUID();
  private static final UUID LOCATION_ID = UUID.randomUUID();
  private static final UUID MISSION_ID = UUID.randomUUID();
  private static final UUID METHOD_ID = UUID.randomUUID();
  private static final UUID INPUT_MATERIAL_ID = UUID.randomUUID();
  private static final UUID OUTPUT_MATERIAL_ID = UUID.randomUUID();

  private User owner;
  private Location refineryLocation;
  private Material rawInput;

  @BeforeEach
  void setUpEntities() {
    owner = new User();
    owner.setId(OWNER_ID);
    owner.setUsername("alice");

    refineryLocation = new Location();
    refineryLocation.setId(LOCATION_ID);
    refineryLocation.setName("ARC-L1");
    SpaceStation station = new SpaceStation();
    station.setHasRefineryTerminal(true);
    refineryLocation.setSpaceStation(station);

    rawInput = new Material();
    rawInput.setId(INPUT_MATERIAL_ID);
    rawInput.setName("Quantanium");
    rawInput.setType(MaterialType.RAW);
  }

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
    void getMyRefineryOrders_withEmptyStatusList_callsOwnerOnlyVariant() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(refineryOrderRepository.findByOwnerId(OWNER_ID, pageable)).thenReturn(page);

      assertEquals(
          1, service.getMyRefineryOrders(OWNER_ID, List.of(), pageable).getTotalElements());
      verify(refineryOrderRepository, never()).findByOwnerIdAndStatusIn(any(), any(), any());
    }

    @Test
    void getMyRefineryOrders_withNullStatusList_callsOwnerOnlyVariant() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(refineryOrderRepository.findByOwnerId(OWNER_ID, pageable)).thenReturn(page);

      assertEquals(1, service.getMyRefineryOrders(OWNER_ID, null, pageable).getTotalElements());
    }

    @Test
    void getMyRefineryOrders_withStatusList_routesToStatusFilter() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(refineryOrderRepository.findByOwnerIdAndStatusIn(
              OWNER_ID, List.of(RefineryOrderStatus.OPEN), pageable))
          .thenReturn(page);

      assertEquals(
          1,
          service
              .getMyRefineryOrders(OWNER_ID, List.of(RefineryOrderStatus.OPEN), pageable)
              .getTotalElements());
      verify(refineryOrderRepository, never()).findByOwnerId(any(), any());
    }

    @Test
    void getUserRefineryOrdersScoped_adminAllScope_forwardsAdminAllScopeToScopedQuery() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(true, null, Set.of()));
      when(refineryOrderRepository.findByOwnerIdScoped(OWNER_ID, true, null, Set.of(), pageable))
          .thenReturn(page);

      assertEquals(1, service.getUserRefineryOrdersScoped(OWNER_ID, pageable).getTotalElements());
      verify(refineryOrderRepository, never()).findByOwnerId(any(), any());
    }

    @Test
    void getUserRefineryOrdersScoped_nonAdminMemberUnion_forwardsOnlyTheCallersOrgUnits() {
      UUID callerStaffelA = UUID.randomUUID();
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(false, null, Set.of(callerStaffelA)));
      when(refineryOrderRepository.findByOwnerIdScoped(
              OWNER_ID, false, null, Set.of(callerStaffelA), pageable))
          .thenReturn(page);

      assertEquals(1, service.getUserRefineryOrdersScoped(OWNER_ID, pageable).getTotalElements());
      verify(refineryOrderRepository, never()).findByOwnerId(any(), any());
    }

    @Test
    void getUserRefineryOrdersScoped_pinnedCaller_forwardsActiveOrgUnitId() {
      UUID pinned = UUID.randomUUID();
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(false, pinned, Set.of()));
      when(refineryOrderRepository.findByOwnerIdScoped(OWNER_ID, false, pinned, Set.of(), pageable))
          .thenReturn(page);

      assertEquals(1, service.getUserRefineryOrdersScoped(OWNER_ID, pageable).getTotalElements());
      verify(refineryOrderRepository, never()).findByOwnerId(any(), any());
    }

    @Test
    void getAllRefineryOrders_emptyStatusList_callsFindAll() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
      when(refineryOrderRepository.findAllScoped(true, null, java.util.Set.of(), pageable))
          .thenReturn(page);

      assertEquals(1, service.getAllRefineryOrders(List.of(), pageable).getTotalElements());
      verify(refineryOrderRepository, never())
          .findByStatusInScoped(any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void getAllRefineryOrders_withStatuses_callsFindByStatusIn() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
      when(refineryOrderRepository.findByStatusInScoped(
              List.of(RefineryOrderStatus.COMPLETED), true, null, java.util.Set.of(), pageable))
          .thenReturn(page);

      assertEquals(
          1,
          service
              .getAllRefineryOrders(List.of(RefineryOrderStatus.COMPLETED), pageable)
              .getTotalElements());
    }

    @Test
    void getAllRefineryOrders_secondOverload_callsFindAll() {
      Page<RefineryOrder> page = new PageImpl<>(List.of(new RefineryOrder()));
      when(ownerScopeService.currentScopePredicate())
          .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
      when(refineryOrderRepository.findAllScoped(true, null, java.util.Set.of(), pageable))
          .thenReturn(page);

      assertEquals(1, service.getAllRefineryOrders(pageable).getTotalElements());
    }

    @Test
    void getMissionRefineryOrdersScoped_passesCallerScope_soForeignSquadronOrdersAreUnreachable() {
      UUID squadronA = UUID.randomUUID();
      UUID squadronB = UUID.randomUUID();
      ScopePredicate squadronAscope = new ScopePredicate(false, null, Set.of(squadronA));
      when(ownerScopeService.currentScopePredicate()).thenReturn(squadronAscope);
      RefineryOrder squadronAorder = new RefineryOrder();
      when(refineryOrderRepository.findByMissionIdScoped(
              MISSION_ID, false, null, Set.of(squadronA)))
          .thenReturn(List.of(squadronAorder));

      List<RefineryOrder> result = service.getMissionRefineryOrdersScoped(MISSION_ID);

      assertEquals(List.of(squadronAorder), result);
      verify(refineryOrderRepository)
          .findByMissionIdScoped(MISSION_ID, false, null, Set.of(squadronA));
      verify(refineryOrderRepository, never())
          .findByMissionIdScoped(MISSION_ID, false, null, Set.of(squadronB));
      verify(refineryOrderRepository, never()).findByMissionId(any(UUID.class));
    }

    @Test
    void getMissionRefineryOrders_filteredByOwner_delegatesToCombinedQuery() {
      RefineryOrder o = new RefineryOrder();
      when(refineryOrderRepository.findByMissionIdAndOwnerId(MISSION_ID, OWNER_ID))
          .thenReturn(List.of(o));

      assertEquals(List.of(o), service.getMissionRefineryOrders(MISSION_ID, OWNER_ID));
    }
  }

  @Nested
  class CreateRefineryOrderTests {

    @Test
    void throwsNotFound_whenUserDoesNotExist() {
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      RefineryOrder incoming = new RefineryOrder();
      incoming.setLocation(refineryLocation);

      assertThrows(
          NotFoundException.class, () -> service.createRefineryOrder(OWNER_ID, incoming, null));
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void throwsBadRequest_whenLocationIsNull() {
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));

      assertThrows(
          BadRequestException.class,
          () -> service.createRefineryOrder(OWNER_ID, new RefineryOrder(), null));
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void throwsBadRequest_whenLocationHasNullId() {
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));

      RefineryOrder incoming = new RefineryOrder();
      Location withoutId = new Location();
      withoutId.setId(null);
      incoming.setLocation(withoutId);

      assertThrows(
          BadRequestException.class, () -> service.createRefineryOrder(OWNER_ID, incoming, null));
    }

    @Test
    void throwsNotFound_whenLocationLookupFails() {
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> service.createRefineryOrder(OWNER_ID, freshOrderWithLocation(), null));
    }

    @Test
    void throwsIllegalArgument_whenLocationHasNoRefinery() {
      Location notARefinery = new Location();
      notARefinery.setId(LOCATION_ID);

      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(notARefinery));

      assertThrows(
          IllegalArgumentException.class,
          () -> service.createRefineryOrder(OWNER_ID, freshOrderWithLocation(), null));
    }

    @Test
    void locationWithCityRefinery_isAccepted() {
      Location cityLoc = new Location();
      cityLoc.setId(LOCATION_ID);
      City city = new City();
      city.setHasRefineryTerminal(true);
      cityLoc.setCity(city);

      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(cityLoc));
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryOrder result = service.createRefineryOrder(OWNER_ID, freshOrderWithLocation(), null);

      assertSame(cityLoc, result.getLocation());
    }

    @Test
    void locationWithSpaceStationRefinery_isAccepted() {
      stubUserAndLocation();
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryOrder result = service.createRefineryOrder(OWNER_ID, freshOrderWithLocation(), null);

      assertSame(refineryLocation, result.getLocation());
    }

    @Test
    void locationWithCity_butHasRefineryFalse_isRejected() {
      Location cityLoc = new Location();
      cityLoc.setId(LOCATION_ID);
      City city = new City();
      city.setHasRefineryTerminal(false);
      cityLoc.setCity(city);

      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(cityLoc));

      assertThrows(
          IllegalArgumentException.class,
          () -> service.createRefineryOrder(OWNER_ID, freshOrderWithLocation(), null));
    }

    @Test
    void missionLookupFails_throwsNotFound() {
      stubUserAndLocation();
      Mission missionRef = new Mission();
      missionRef.setId(MISSION_ID);

      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.empty());

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setMission(missionRef);

      assertThrows(
          NotFoundException.class, () -> service.createRefineryOrder(OWNER_ID, incoming, null));
    }

    @Test
    void missionLinked_ownerIsParticipant_isAccepted() {
      stubUserAndLocation();
      Mission mission = new Mission();
      mission.setId(MISSION_ID);
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(missionParticipantRepository.findByMissionIdAndUserId(MISSION_ID, OWNER_ID))
          .thenReturn(Optional.of(new MissionParticipant()));
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      Mission missionRef = new Mission();
      missionRef.setId(MISSION_ID);
      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setMission(missionRef);

      RefineryOrder result = service.createRefineryOrder(OWNER_ID, incoming, null);
      assertSame(mission, result.getMission());
    }

    @Test
    void missionLinked_ownerNotParticipant_isRejectedWithoutSave() {
      stubUserAndLocation();
      Mission mission = new Mission();
      mission.setId(MISSION_ID);
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(missionParticipantRepository.findByMissionIdAndUserId(MISSION_ID, OWNER_ID))
          .thenReturn(Optional.empty());

      Mission missionRef = new Mission();
      missionRef.setId(MISSION_ID);
      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setMission(missionRef);

      MissionParticipantRequiredException ex =
          assertThrows(
              MissionParticipantRequiredException.class,
              () -> service.createRefineryOrder(OWNER_ID, incoming, null));
      assertEquals("MISSION_PARTICIPANT_REQUIRED", ex.code());
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void missionNull_setsOrderMissionToNull() {
      stubUserAndLocation();
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setMission(null);

      RefineryOrder result = service.createRefineryOrder(OWNER_ID, incoming, null);
      assertNull(result.getMission());
    }

    @Test
    void refiningMethodLookupFails_throwsNotFound() {
      stubUserAndLocation();
      RefiningMethod methodRef = new RefiningMethod();
      methodRef.setId(METHOD_ID);

      when(refiningMethodRepository.findById(METHOD_ID)).thenReturn(Optional.empty());

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setRefiningMethod(methodRef);

      assertThrows(
          NotFoundException.class, () -> service.createRefineryOrder(OWNER_ID, incoming, null));
    }

    @Test
    void goodWithNullInputMaterial_throwsBadRequest() {
      stubUserAndLocation();
      RefineryGood bad = new RefineryGood();
      bad.setInputMaterial(null);

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setGoods(new HashSet<>(Set.of(bad)));

      assertThrows(
          BadRequestException.class, () -> service.createRefineryOrder(OWNER_ID, incoming, null));
    }

    @Test
    void goodWithNonRawInput_throwsIllegalArgument() {
      Material refined = new Material();
      refined.setId(INPUT_MATERIAL_ID);
      refined.setType(MaterialType.REFINED);
      refined.setIsManualRawMaterial(false);
      refined.setName("RefinedOre");

      stubUserAndLocation();
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(refined));

      RefineryGood bad = newGoodWithInput(INPUT_MATERIAL_ID);
      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setGoods(new HashSet<>(Set.of(bad)));

      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> service.createRefineryOrder(OWNER_ID, incoming, null));
      assert ex.getMessage().contains("RAW");
    }

    @Test
    void goodWithNonRawInputButManualRawFlagTrue_isAccepted() {
      Material flagged = new Material();
      flagged.setId(INPUT_MATERIAL_ID);
      flagged.setType(MaterialType.REFINED);
      flagged.setIsManualRawMaterial(true);

      stubUserAndLocation();
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(flagged));
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryGood good = newGoodWithInput(INPUT_MATERIAL_ID);
      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setGoods(new HashSet<>(Set.of(good)));

      service.createRefineryOrder(OWNER_ID, incoming, null);

      assertSame(flagged, good.getInputMaterial());
    }

    @Test
    void goodWithoutOutputMaterial_andNoRefinedMaterialOnInput_fallsBackToInputItself() {
      stubUserAndLocation();
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(rawInput));
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryGood good = newGoodWithInput(INPUT_MATERIAL_ID);
      good.setOutputMaterial(null);
      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setGoods(new HashSet<>(Set.of(good)));

      service.createRefineryOrder(OWNER_ID, incoming, null);
      assertSame(rawInput, good.getOutputMaterial());
    }

    @Test
    void goodWithoutOutputMaterial_butInputHasRefinedMaterial_usesRefined() {
      Material refinedOf = new Material();
      refinedOf.setId(OUTPUT_MATERIAL_ID);
      refinedOf.setType(MaterialType.REFINED);
      rawInput.setRefinedMaterial(refinedOf);

      stubUserAndLocation();
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(rawInput));
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryGood good = newGoodWithInput(INPUT_MATERIAL_ID);
      good.setOutputMaterial(null);
      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setGoods(new HashSet<>(Set.of(good)));

      service.createRefineryOrder(OWNER_ID, incoming, null);
      assertSame(refinedOf, good.getOutputMaterial());
    }

    @Test
    void goodWithExplicitOutputMatchingRefinedOfInput_isAccepted() {
      Material refinedOf = new Material();
      refinedOf.setId(OUTPUT_MATERIAL_ID);
      rawInput.setRefinedMaterial(refinedOf);

      stubUserAndLocation();
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(rawInput));
      when(materialRepository.findById(OUTPUT_MATERIAL_ID)).thenReturn(Optional.of(refinedOf));
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryGood good = newGoodWithInput(INPUT_MATERIAL_ID);
      Material outputStub = new Material();
      outputStub.setId(OUTPUT_MATERIAL_ID);
      good.setOutputMaterial(outputStub);

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setGoods(new HashSet<>(Set.of(good)));

      service.createRefineryOrder(OWNER_ID, incoming, null);
      assertSame(refinedOf, good.getOutputMaterial());
    }

    @Test
    void goodWithExplicitOutputThatDoesNotMatchRefined_throwsIllegalArgument() {
      Material refinedOf = new Material();
      refinedOf.setId(OUTPUT_MATERIAL_ID);
      rawInput.setRefinedMaterial(refinedOf);

      UUID otherOutputId = UUID.randomUUID();
      Material wrongOutput = new Material();
      wrongOutput.setId(otherOutputId);

      stubUserAndLocation();
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(rawInput));
      when(materialRepository.findById(otherOutputId)).thenReturn(Optional.of(wrongOutput));

      RefineryGood good = newGoodWithInput(INPUT_MATERIAL_ID);
      Material outputStub = new Material();
      outputStub.setId(otherOutputId);
      good.setOutputMaterial(outputStub);

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setGoods(new HashSet<>(Set.of(good)));

      assertThrows(
          IllegalArgumentException.class,
          () -> service.createRefineryOrder(OWNER_ID, incoming, null));
    }

    @Test
    void outputMaterialLookupFails_throwsNotFound() {
      stubUserAndLocation();
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(rawInput));
      when(materialRepository.findById(OUTPUT_MATERIAL_ID)).thenReturn(Optional.empty());

      RefineryGood good = newGoodWithInput(INPUT_MATERIAL_ID);
      Material outputStub = new Material();
      outputStub.setId(OUTPUT_MATERIAL_ID);
      good.setOutputMaterial(outputStub);

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setGoods(new HashSet<>(Set.of(good)));

      assertThrows(
          NotFoundException.class, () -> service.createRefineryOrder(OWNER_ID, incoming, null));
    }

    @Test
    void startedAtNull_defaultsToInstantNow() {
      stubUserAndLocation();
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      Instant before = Instant.now();
      RefineryOrder result = service.createRefineryOrder(OWNER_ID, freshOrderWithLocation(), null);
      Instant after = Instant.now();

      assertNotNull(result.getStartedAt());
      assert !result.getStartedAt().isBefore(before);
      assert !result.getStartedAt().isAfter(after);
    }

    @Test
    void startedAtProvided_isPreserved() {
      Instant explicit = Instant.parse("2026-01-01T00:00:00Z");
      stubUserAndLocation();
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setStartedAt(explicit);

      RefineryOrder result = service.createRefineryOrder(OWNER_ID, incoming, null);
      assertEquals(explicit, result.getStartedAt());
    }

    @Test
    void zeroMoneyFields_areNormalisedToNull() {
      stubUserAndLocation();
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setExpenses(0.0);
      incoming.setOtherExpenses(0.0);
      incoming.setOreSales(0.0);

      RefineryOrder result = service.createRefineryOrder(OWNER_ID, incoming, null);
      assertNull(result.getExpenses());
      assertNull(result.getOtherExpenses());
      assertNull(result.getOreSales());
    }

    @Test
    void positiveMoneyFields_arePreserved() {
      stubUserAndLocation();
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setExpenses(100.0);
      incoming.setOtherExpenses(50.0);
      incoming.setOreSales(1000.0);

      RefineryOrder result = service.createRefineryOrder(OWNER_ID, incoming, null);
      assertEquals(100.0, result.getExpenses());
      assertEquals(50.0, result.getOtherExpenses());
      assertEquals(1000.0, result.getOreSales());
    }
  }

  @Nested
  class UpdateRefineryOrderTests {

    @Test
    void throwsOptimisticLockingFailure_whenVersionMismatch() {
      RefineryOrder existing = newSavedOrder();
      existing.setVersion(5L);

      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

      RefineryOrder incoming = new RefineryOrder();
      incoming.setVersion(2L);

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> service.updateRefineryOrder(OWNER_ID, ORDER_ID, incoming, false));
    }

    @Test
    void nullVersion_bypassesOptimisticCheck() {
      RefineryOrder existing = newSavedOrder();
      existing.setVersion(5L);

      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      service.updateRefineryOrder(OWNER_ID, ORDER_ID, new RefineryOrder(), false);

      verify(refineryOrderRepository).save(existing);
    }

    @Test
    void throwsAccessDenied_whenNonLogisticianIsNotOwner() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

      assertThrows(
          AccessDeniedException.class,
          () -> service.updateRefineryOrder(OTHER_USER_ID, ORDER_ID, new RefineryOrder(), false));
    }

    @Test
    void throwsAccessDenied_whenOwnerIsNull_andNotLogistician() {
      RefineryOrder existing = newSavedOrder();
      existing.setOwner(null);
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

      assertThrows(
          AccessDeniedException.class,
          () -> service.updateRefineryOrder(OWNER_ID, ORDER_ID, new RefineryOrder(), false));
    }

    @Test
    void logisticianCanUpdateAnyOrder() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      service.updateRefineryOrder(OTHER_USER_ID, ORDER_ID, new RefineryOrder(), true);

      verify(refineryOrderRepository).save(existing);
    }

    @Test
    void locationProvided_isLookedUpAndValidated() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(refineryLocation));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setLocation(refineryLocation);

      service.updateRefineryOrder(OWNER_ID, ORDER_ID, incoming, false);

      assertSame(refineryLocation, existing.getLocation());
    }

    @Test
    void missionExplicitlySetToNull_clearsExistingMission() {
      RefineryOrder existing = newSavedOrder();
      existing.setMission(new Mission());
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setMission(null);

      service.updateRefineryOrder(OWNER_ID, ORDER_ID, incoming, false);
      assertNull(existing.getMission());
      verifyNoInteractions(missionParticipantRepository);
    }

    @Test
    void missionChanged_ownerIsParticipant_isAccepted() {
      RefineryOrder existing = newSavedOrder();
      Mission mission = new Mission();
      mission.setId(MISSION_ID);
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(missionParticipantRepository.findByMissionIdAndUserId(MISSION_ID, OWNER_ID))
          .thenReturn(Optional.of(new MissionParticipant()));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      Mission missionRef = new Mission();
      missionRef.setId(MISSION_ID);
      RefineryOrder incoming = new RefineryOrder();
      incoming.setMission(missionRef);

      service.updateRefineryOrder(OWNER_ID, ORDER_ID, incoming, false);
      assertSame(mission, existing.getMission());
    }

    @Test
    void missionChanged_ownerNotParticipant_isRejectedEvenForALogistician() {
      RefineryOrder existing = newSavedOrder();
      Mission mission = new Mission();
      mission.setId(MISSION_ID);
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(missionParticipantRepository.findByMissionIdAndUserId(MISSION_ID, OWNER_ID))
          .thenReturn(Optional.empty());

      Mission missionRef = new Mission();
      missionRef.setId(MISSION_ID);
      RefineryOrder incoming = new RefineryOrder();
      incoming.setMission(missionRef);

      assertThrows(
          MissionParticipantRequiredException.class,
          () -> service.updateRefineryOrder(OTHER_USER_ID, ORDER_ID, incoming, true));
      assertNull(existing.getMission());
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void missionUnchanged_isNotRechecked() {
      RefineryOrder existing = newSavedOrder();
      Mission linked = new Mission();
      linked.setId(MISSION_ID);
      existing.setMission(linked);
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      Mission missionRef = new Mission();
      missionRef.setId(MISSION_ID);
      RefineryOrder incoming = new RefineryOrder();
      incoming.setMission(missionRef);

      service.updateRefineryOrder(OWNER_ID, ORDER_ID, incoming, false);

      assertSame(linked, existing.getMission());
      verifyNoInteractions(missionParticipantRepository);
      verify(missionRepository, never()).findById(any());
    }

    @Test
    void refiningMethodExplicitlySetToNull_clearsExisting() {
      RefineryOrder existing = newSavedOrder();
      existing.setRefiningMethod(new RefiningMethod());
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      service.updateRefineryOrder(OWNER_ID, ORDER_ID, new RefineryOrder(), false);
      assertNull(existing.getRefiningMethod());
    }

    @Test
    void statusProvided_isApplied() {
      RefineryOrder existing = newSavedOrder();
      existing.setStatus(RefineryOrderStatus.OPEN);
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setStatus(RefineryOrderStatus.IN_PROGRESS);

      service.updateRefineryOrder(OWNER_ID, ORDER_ID, incoming, false);
      assertEquals(RefineryOrderStatus.IN_PROGRESS, existing.getStatus());
    }

    @Test
    void statusNullInIncoming_preservesExistingStatus() {
      RefineryOrder existing = newSavedOrder();
      existing.setStatus(RefineryOrderStatus.OPEN);
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      service.updateRefineryOrder(OWNER_ID, ORDER_ID, new RefineryOrder(), false);
      assertEquals(
          RefineryOrderStatus.OPEN,
          existing.getStatus(),
          "null incoming status must NOT overwrite the existing one");
    }

    @Test
    void zeroMoneyFields_normalisedToNullOnUpdate() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setExpenses(0.0);
      incoming.setOtherExpenses(0.0);
      incoming.setOreSales(0.0);

      service.updateRefineryOrder(OWNER_ID, ORDER_ID, incoming, false);
      assertNull(existing.getExpenses());
      assertNull(existing.getOtherExpenses());
      assertNull(existing.getOreSales());
    }

    @Test
    void goodsReplacement_clearsAndReadsAllValidatedGoods() {
      RefineryGood preexisting = new RefineryGood();
      RefineryOrder existing = newSavedOrder();
      existing.setGoods(new HashSet<>(Set.of(preexisting)));

      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(rawInput));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryGood replacement = newGoodWithInput(INPUT_MATERIAL_ID);
      RefineryOrder incoming = new RefineryOrder();
      incoming.setGoods(new HashSet<>(Set.of(replacement)));

      service.updateRefineryOrder(OWNER_ID, ORDER_ID, incoming, false);

      assertEquals(1, existing.getGoods().size());
      assert !existing.getGoods().contains(preexisting);
    }
  }

  @Nested
  class DeleteRefineryOrderTests {

    @Test
    void throwsNotFound_whenOrderDoesNotExist() {
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class, () -> service.deleteRefineryOrder(OWNER_ID, ORDER_ID, false));
    }

    @Test
    void throwsAccessDenied_whenNonLogisticianIsNotOwner() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

      assertThrows(
          AccessDeniedException.class,
          () -> service.deleteRefineryOrder(OTHER_USER_ID, ORDER_ID, false));
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void ownerCanCancel_orderTransitionsToCanceled() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

      service.deleteRefineryOrder(OWNER_ID, ORDER_ID, false);

      assertEquals(RefineryOrderStatus.CANCELED, existing.getStatus());
      verify(refineryOrderRepository).save(existing);
    }

    @Test
    void logisticianCanCancelAnyOrder() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(existing));

      service.deleteRefineryOrder(OTHER_USER_ID, ORDER_ID, true);

      assertEquals(RefineryOrderStatus.CANCELED, existing.getStatus());
      verify(refineryOrderRepository).save(existing);
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

      List<OwnedStockSlice> slices = service.getOwnedOpenRefineryYieldSlices(OWNER_ID);

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

      List<OwnedStockSlice> slices = service.getOwnedOpenRefineryYieldSlices(OWNER_ID);

      assertEquals(1, slices.size(), "only the fully-populated positive-SCU good yields a slice");
      OwnedStockSlice only = slices.get(0);
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

    private Double totalScuFor(List<OwnedStockSlice> slices, UUID materialId, int quality) {
      for (OwnedStockSlice slice : slices) {
        if (materialId.equals(slice.materialId())
            && Integer.valueOf(quality).equals(slice.quality())) {
          return slice.totalScu();
        }
      }
      return null;
    }
  }

  private RefineryOrder newSavedOrder() {
    RefineryOrder o = new RefineryOrder();
    o.setId(ORDER_ID);
    o.setVersion(1L);
    o.setOwner(owner);
    o.setStatus(RefineryOrderStatus.OPEN);
    o.setGoods(new HashSet<>());
    return o;
  }

  private RefineryOrder freshOrderWithLocation() {
    RefineryOrder o = new RefineryOrder();
    o.setLocation(refineryLocation);
    return o;
  }

  private void stubUserAndLocation() {
    when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
    when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(refineryLocation));
  }

  private static RefineryGood newGoodWithInput(UUID inputMaterialId) {
    RefineryGood good = new RefineryGood();
    Material inputStub = new Material();
    inputStub.setId(inputMaterialId);
    good.setInputMaterial(inputStub);
    return good;
  }
}
