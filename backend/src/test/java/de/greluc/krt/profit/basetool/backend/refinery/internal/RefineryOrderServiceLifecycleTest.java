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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationNotOfferedException;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.refinery.api.MissionParticipantRequiredException;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Lifecycle and CRUD tests for {@link RefineryOrderService}: the not-found read, the self,
 * cross-user, all-orders and mission lists, create validation, update with version and owner checks
 * and partial semantics, and delete as a status flip to CANCELED.
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
  @Mock private OrgUnitStampingService orgUnitStampingService;

  @Mock private AuditService auditService;

  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private UserService userService;
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
    lenient().when(userService.currentActor()).thenReturn(ActorRef.system());
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

    @ParameterizedTest
    @MethodSource("de.greluc.krt.profit.basetool.backend.catalogue.api.NotOfferedPlaces#places")
    void throwsLocationNotOffered_whenNoPickerOffersThePlace(Location place) {
      place.setSpaceStation(refineryLocation.getSpaceStation());
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
      when(locationRepository.findById(place.getId())).thenReturn(Optional.of(place));
      RefineryOrder incoming = new RefineryOrder();
      incoming.setLocation(place);

      assertThrows(
          LocationNotOfferedException.class,
          () -> service.createRefineryOrder(OWNER_ID, incoming, null));
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void throwsIllegalArgument_whenLocationHasNoRefinery() {
      Location notARefinery = new Location();
      notARefinery.setId(LOCATION_ID);

      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(notARefinery));

      assertThrows(
          BadRequestException.class,
          () -> service.createRefineryOrder(OWNER_ID, freshOrderWithLocation(), null));
    }

    @Test
    void onBehalf_stampedUnitWithoutALogisticianGrant_isRefused() {
      Squadron unitB = new Squadron();
      unitB.setId(UUID.randomUUID());
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
      when(orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(owner, unitB.getId()))
          .thenReturn(unitB);
      doThrow(new AccessDeniedException("no"))
          .when(ownerScopeService)
          .requireLogisticianInOwner(unitB);

      assertThrows(
          AccessDeniedException.class,
          () ->
              service.createRefineryOrderOnBehalf(
                  OWNER_ID, freshOrderWithLocation(), unitB.getId()));
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void onBehalf_ownerlessStampForANonAdmin_isRefused() {
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
      when(orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(owner, null))
          .thenReturn(null);
      doThrow(new AccessDeniedException("no"))
          .when(ownerScopeService)
          .requireLogisticianInOwner(null);

      assertThrows(
          AccessDeniedException.class,
          () -> service.createRefineryOrderOnBehalf(OWNER_ID, freshOrderWithLocation(), null));
    }

    @Test
    void onBehalf_stampedUnitWithALogisticianGrant_isCreated() {
      Squadron unitA = new Squadron();
      unitA.setId(UUID.randomUUID());
      Location cityLoc = new Location();
      cityLoc.setId(LOCATION_ID);
      City city = new City();
      city.setHasRefineryTerminal(true);
      cityLoc.setCity(city);
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
      when(orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(owner, unitA.getId()))
          .thenReturn(unitA);
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(cityLoc));
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      RefineryOrder result =
          service.createRefineryOrderOnBehalf(OWNER_ID, freshOrderWithLocation(), unitA.getId());

      assertSame(unitA, result.getOwningOrgUnit());
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
          BadRequestException.class,
          () -> service.createRefineryOrder(OWNER_ID, freshOrderWithLocation(), null));
    }

    @Test
    void missionLookupFails_throwsNotFound() {
      stubUserAndLocation();
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.empty());

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setMissionId(MISSION_ID);

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

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setMissionId(MISSION_ID);

      RefineryOrder result = service.createRefineryOrder(OWNER_ID, incoming, null);
      assertEquals(MISSION_ID, result.getMissionId());
    }

    @Test
    void missionLinked_ownerNotParticipant_isRejectedWithoutSave() {
      stubUserAndLocation();
      Mission mission = new Mission();
      mission.setId(MISSION_ID);
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(missionParticipantRepository.findByMissionIdAndUserId(MISSION_ID, OWNER_ID))
          .thenReturn(Optional.empty());

      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setMissionId(MISSION_ID);

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
      incoming.setMissionId(null);

      RefineryOrder result = service.createRefineryOrder(OWNER_ID, incoming, null);
      assertNull(result.getMissionId());
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

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () -> service.createRefineryOrder(OWNER_ID, incoming, null));
      assertEquals("error.refinery_order.input_material_not_raw", ex.getMessage());
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
          BadRequestException.class, () -> service.createRefineryOrder(OWNER_ID, incoming, null));
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
    void clientSuppliedServerManagedFields_areIgnoredOnCreate() {
      stubUserAndLocation();
      when(refineryOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      User stranger = new User();
      stranger.setId(OTHER_USER_ID);
      RefineryOrder incoming = freshOrderWithLocation();
      incoming.setId(UUID.randomUUID());
      incoming.setVersion(7L);
      incoming.setOwner(stranger);
      incoming.setStatus(RefineryOrderStatus.COMPLETED);
      incoming.setStoredAt(Instant.now());

      RefineryOrder result = service.createRefineryOrder(OWNER_ID, incoming, null);

      assertEquals(RefineryOrderStatus.OPEN, result.getStatus());
      assertNull(result.getStoredAt());
      assertNull(result.getId());
      assertNull(result.getVersion());
      assertSame(owner, result.getOwner());
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
    void throwsOptimisticLockingFailure_whenTheGameDataCounterIsStale() {
      RefineryOrder existing = newSavedOrder();
      existing.setGameDataVersion(5L);

      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));

      RefineryOrder incoming = new RefineryOrder();
      incoming.setGameDataVersion(2L);

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> updateEveryPart(OWNER_ID, incoming, false));
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void aStaleOrderVersionIsNotChecked() {
      RefineryOrder existing = newSavedOrder();
      existing.setVersion(5L);

      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setVersion(2L);
      updateEveryPart(OWNER_ID, incoming, false);

      verify(refineryOrderRepository).save(existing);
    }

    @Test
    void storedOrder_cannotBeReopenedByUpdate() {
      RefineryOrder existing = newSavedOrder();
      existing.setStatus(RefineryOrderStatus.COMPLETED);
      existing.setStoredAt(java.time.Instant.now());
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));

      RefineryOrder incoming = new RefineryOrder();
      incoming.setStatus(RefineryOrderStatus.OPEN);

      BadRequestException ex =
          assertThrows(BadRequestException.class, () -> updateEveryPart(OWNER_ID, incoming, false));
      assertEquals("error.refinery_order.stored_status_locked", ex.getMessage());
      assertEquals(RefineryOrderStatus.COMPLETED, existing.getStatus());
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void storedOrder_keepingItsStatus_canStillBeUpdated() {
      RefineryOrder existing = newSavedOrder();
      existing.setStatus(RefineryOrderStatus.COMPLETED);
      existing.setStoredAt(java.time.Instant.now());
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setStatus(RefineryOrderStatus.COMPLETED);

      updateEveryPart(OWNER_ID, incoming, false);

      verify(refineryOrderRepository).save(existing);
    }

    @Test
    void throwsAccessDenied_whenNonLogisticianIsNotOwner() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));

      assertThrows(
          AccessDeniedException.class,
          () -> updateEveryPart(OTHER_USER_ID, new RefineryOrder(), false));
    }

    @Test
    void throwsAccessDenied_whenOwnerIsNull_andNotLogistician() {
      RefineryOrder existing = newSavedOrder();
      existing.setOwner(null);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));

      assertThrows(
          AccessDeniedException.class, () -> updateEveryPart(OWNER_ID, new RefineryOrder(), false));
    }

    @Test
    void logisticianCanUpdateAnyOrder() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      updateEveryPart(OTHER_USER_ID, new RefineryOrder(), true);

      verify(refineryOrderRepository).save(existing);
    }

    @Test
    void anUpdateByAnotherMemberTellsTheOwnerAndAChangedRunTimeClearsTheReadyNotice() {
      RefineryOrder existing = newSavedOrder();
      existing.setStartedAt(Instant.parse("2026-01-01T10:00:00Z"));
      existing.setDurationMinutes(30L);
      existing.setReadyNotifiedAt(Instant.parse("2026-01-01T10:30:00Z"));
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);
      when(userService.currentActor()).thenReturn(new ActorRef(OTHER_USER_ID, "Bob"));
      RefineryOrder details = new RefineryOrder();
      details.setStartedAt(Instant.parse("2026-01-01T10:00:00Z"));
      details.setDurationMinutes(90L);

      updateEveryPart(OTHER_USER_ID, details, true);

      assertNull(existing.getReadyNotifiedAt());
      assertEquals(
          List.of(
              NotificationEventType.REFINERY_ORDER_READY_CLEARED,
              NotificationEventType.REFINERY_ORDER_CHANGED_BY_OTHER),
          publishedTypes());
    }

    @Test
    void anUpdateByTheOwnerWithTheSameRunTimeKeepsTheMarkerAndTellsNobody() {
      RefineryOrder existing = newSavedOrder();
      existing.setStartedAt(Instant.parse("2026-01-01T10:00:00Z"));
      existing.setDurationMinutes(30L);
      Instant marker = Instant.parse("2026-01-01T10:30:00Z");
      existing.setReadyNotifiedAt(marker);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);
      RefineryOrder details = new RefineryOrder();
      details.setStartedAt(Instant.parse("2026-01-01T10:00:00Z"));
      details.setDurationMinutes(30L);

      updateEveryPart(OWNER_ID, details, false);

      assertEquals(marker, existing.getReadyNotifiedAt());
      assertEquals(List.of(), publishedTypes());
    }

    @Test
    void anUpdateThatCancelsTheOrderNamesTheCancellation() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);
      when(userService.currentActor()).thenReturn(new ActorRef(OTHER_USER_ID, "Bob"));
      RefineryOrder details = new RefineryOrder();
      details.setStatus(RefineryOrderStatus.CANCELED);

      updateEveryPart(OTHER_USER_ID, details, true);

      assertEquals("CANCELED", changeCodeOfLastNotice());
    }

    @Test
    void locationProvided_isLookedUpAndValidated() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(refineryLocation));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setLocation(refineryLocation);

      updateEveryPart(OWNER_ID, incoming, false);

      assertSame(refineryLocation, existing.getLocation());
    }

    @ParameterizedTest
    @MethodSource("de.greluc.krt.profit.basetool.backend.catalogue.api.NotOfferedPlaces#places")
    void locationKeptOnAPlaceNoPickerOffers_stillSaves(Location place) {
      place.setSpaceStation(refineryLocation.getSpaceStation());
      RefineryOrder existing = newSavedOrder();
      existing.setLocation(place);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(locationRepository.findById(place.getId())).thenReturn(Optional.of(place));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setLocation(place);

      updateEveryPart(OWNER_ID, incoming, false);

      assertSame(place, existing.getLocation());
      verify(refineryOrderRepository).save(existing);
    }

    @ParameterizedTest
    @MethodSource("de.greluc.krt.profit.basetool.backend.catalogue.api.NotOfferedPlaces#places")
    void locationMovedOntoAPlaceNoPickerOffers_isRefused(Location place) {
      place.setSpaceStation(refineryLocation.getSpaceStation());
      RefineryOrder existing = newSavedOrder();
      existing.setLocation(refineryLocation);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(locationRepository.findById(place.getId())).thenReturn(Optional.of(place));

      RefineryOrder incoming = new RefineryOrder();
      incoming.setLocation(place);

      assertThrows(
          LocationNotOfferedException.class, () -> updateEveryPart(OWNER_ID, incoming, false));
      assertSame(refineryLocation, existing.getLocation());
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void missionExplicitlySetToNull_clearsExistingMission() {
      RefineryOrder existing = newSavedOrder();
      existing.setMissionId(MISSION_ID);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setMissionId(null);

      updateEveryPart(OWNER_ID, incoming, false);
      assertNull(existing.getMissionId());
      verifyNoInteractions(missionParticipantRepository);
    }

    @Test
    void missionReferenceWithoutAnId_leavesTheLinkAsItWas() {
      RefineryOrder existing = newSavedOrder();
      existing.setMissionId(MISSION_ID);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setMissionReferenceWithoutId(true);

      updateEveryPart(OWNER_ID, incoming, false);

      assertEquals(MISSION_ID, existing.getMissionId());
      verifyNoInteractions(missionParticipantRepository);
    }

    @Test
    void missionChanged_ownerIsParticipant_isAccepted() {
      RefineryOrder existing = newSavedOrder();
      Mission mission = new Mission();
      mission.setId(MISSION_ID);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(missionParticipantRepository.findByMissionIdAndUserId(MISSION_ID, OWNER_ID))
          .thenReturn(Optional.of(new MissionParticipant()));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setMissionId(MISSION_ID);

      updateEveryPart(OWNER_ID, incoming, false);
      assertEquals(MISSION_ID, existing.getMissionId());
    }

    @Test
    void missionChanged_ownerNotParticipant_isRejectedEvenForALogistician() {
      RefineryOrder existing = newSavedOrder();
      Mission mission = new Mission();
      mission.setId(MISSION_ID);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(missionRepository.findById(MISSION_ID)).thenReturn(Optional.of(mission));
      when(missionParticipantRepository.findByMissionIdAndUserId(MISSION_ID, OWNER_ID))
          .thenReturn(Optional.empty());

      RefineryOrder incoming = new RefineryOrder();
      incoming.setMissionId(MISSION_ID);

      assertThrows(
          MissionParticipantRequiredException.class,
          () -> updateEveryPart(OTHER_USER_ID, incoming, true));
      assertNull(existing.getMissionId());
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void missionUnchanged_isNotRechecked() {
      RefineryOrder existing = newSavedOrder();
      existing.setMissionId(MISSION_ID);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setMissionId(MISSION_ID);

      updateEveryPart(OWNER_ID, incoming, false);

      assertEquals(MISSION_ID, existing.getMissionId());
      verifyNoInteractions(missionParticipantRepository);
      verify(missionRepository, never()).findById(any());
    }

    @Test
    void refiningMethodExplicitlySetToNull_clearsExisting() {
      RefineryOrder existing = newSavedOrder();
      existing.setRefiningMethod(new RefiningMethod());
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      updateEveryPart(OWNER_ID, new RefineryOrder(), false);
      assertNull(existing.getRefiningMethod());
    }

    @Test
    void statusProvided_isApplied() {
      RefineryOrder existing = newSavedOrder();
      existing.setStatus(RefineryOrderStatus.OPEN);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setStatus(RefineryOrderStatus.IN_PROGRESS);

      updateEveryPart(OWNER_ID, incoming, false);
      assertEquals(RefineryOrderStatus.IN_PROGRESS, existing.getStatus());
    }

    @Test
    void statusNullInIncoming_preservesExistingStatus() {
      RefineryOrder existing = newSavedOrder();
      existing.setStatus(RefineryOrderStatus.OPEN);
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      updateEveryPart(OWNER_ID, new RefineryOrder(), false);
      assertEquals(
          RefineryOrderStatus.OPEN,
          existing.getStatus(),
          "null incoming status must NOT overwrite the existing one");
    }

    @Test
    void zeroMoneyFields_normalisedToNullOnUpdate() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryOrder incoming = new RefineryOrder();
      incoming.setExpenses(0.0);
      incoming.setOtherExpenses(0.0);
      incoming.setOreSales(0.0);

      updateEveryPart(OWNER_ID, incoming, false);
      assertNull(existing.getExpenses());
      assertNull(existing.getOtherExpenses());
      assertNull(existing.getOreSales());
    }

    @Test
    void goodsWithoutAnIdReplaceTheGoodsTheRequestDoesNotName() {
      RefineryGood preexisting = heldGood(UUID.randomUUID());
      RefineryOrder existing = newSavedOrder();
      existing.setGoods(new HashSet<>(Set.of(preexisting)));

      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(rawInput));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryGood replacement = newGoodWithInput(INPUT_MATERIAL_ID);
      RefineryOrder incoming = new RefineryOrder();
      incoming.setGoods(new HashSet<>(Set.of(replacement)));

      updateEveryPart(OWNER_ID, incoming, false);

      assertEquals(1, existing.getGoods().size());
      assertSame(replacement, existing.getGoods().iterator().next());
    }

    @Test
    void aGoodCarryingAHeldIdIsChangedInPlaceAndKeepsItsId() {
      UUID heldId = UUID.randomUUID();
      RefineryGood held = heldGood(heldId);
      RefineryOrder existing = newSavedOrder();
      existing.setGoods(new HashSet<>(Set.of(held)));

      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(rawInput));
      when(refineryOrderRepository.save(existing)).thenReturn(existing);

      RefineryGood edited = newGoodWithInput(INPUT_MATERIAL_ID);
      edited.setId(heldId);
      edited.setInputQuantity(40);
      edited.setOutputQuantity(30);
      edited.setQuality(800);
      RefineryGood added = newGoodWithInput(INPUT_MATERIAL_ID);
      RefineryOrder incoming = new RefineryOrder();
      incoming.setGoods(new HashSet<>(Set.of(edited, added)));

      updateEveryPart(OWNER_ID, incoming, false);

      assertEquals(2, existing.getGoods().size());
      assertTrue(existing.getGoods().stream().anyMatch(good -> good == held));
      assertEquals(heldId, held.getId());
      assertEquals(40, held.getInputQuantity());
      assertEquals(30, held.getOutputQuantity());
      assertEquals(800, held.getQuality());
      assertSame(rawInput, held.getInputMaterial());
    }

    @Test
    void aGoodCarryingAnIdTheOrderDoesNotHoldIsRefused() {
      RefineryOrder existing = newSavedOrder();
      existing.setGoods(new HashSet<>(Set.of(heldGood(UUID.randomUUID()))));

      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(materialRepository.findById(INPUT_MATERIAL_ID)).thenReturn(Optional.of(rawInput));

      RefineryGood foreign = newGoodWithInput(INPUT_MATERIAL_ID);
      foreign.setId(UUID.randomUUID());
      RefineryOrder incoming = new RefineryOrder();
      incoming.setGoods(new HashSet<>(Set.of(foreign)));

      BadRequestException ex =
          assertThrows(BadRequestException.class, () -> updateEveryPart(OWNER_ID, incoming, false));
      assertEquals("error.refinery_order.good_unknown", ex.getMessage());
      verify(refineryOrderRepository, never()).save(any());
    }
  }

  @Nested
  class DeleteRefineryOrderTests {

    @Test
    void throwsNotFound_whenOrderDoesNotExist() {
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class, () -> service.deleteRefineryOrder(OWNER_ID, ORDER_ID, false));
    }

    @Test
    void throwsAccessDenied_whenNonLogisticianIsNotOwner() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));

      assertThrows(
          AccessDeniedException.class,
          () -> service.deleteRefineryOrder(OTHER_USER_ID, ORDER_ID, false));
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void ownerCanCancel_orderTransitionsToCanceled() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));

      service.deleteRefineryOrder(OWNER_ID, ORDER_ID, false);

      assertEquals(RefineryOrderStatus.CANCELED, existing.getStatus());
      verify(refineryOrderRepository).save(existing);
    }

    @Test
    void aCancellationByAnotherMemberTellsTheOwnerAndClearsTheReadyNotice() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));
      when(userService.currentActor()).thenReturn(new ActorRef(OTHER_USER_ID, "Bob"));

      service.deleteRefineryOrder(OTHER_USER_ID, ORDER_ID, true);

      assertEquals(
          List.of(
              NotificationEventType.REFINERY_ORDER_READY_CLEARED,
              NotificationEventType.REFINERY_ORDER_CHANGED_BY_OTHER),
          publishedTypes());
      assertEquals("CANCELED", changeCodeOfLastNotice());
    }

    @Test
    void aCancellationByTheOwnerOnlyClearsTheReadyNotice() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));

      service.deleteRefineryOrder(OWNER_ID, ORDER_ID, false);

      assertEquals(List.of(NotificationEventType.REFINERY_ORDER_READY_CLEARED), publishedTypes());
    }

    @Test
    void logisticianCanCancelAnyOrder() {
      RefineryOrder existing = newSavedOrder();
      when(refineryOrderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(existing));

      service.deleteRefineryOrder(OTHER_USER_ID, ORDER_ID, true);

      assertEquals(RefineryOrderStatus.CANCELED, existing.getStatus());
      verify(refineryOrderRepository).save(existing);
    }
  }

  @Nested
  class CreateOrderPickerDelegationTests {

    @Test
    void createRefineryOrder_delegatesPickerResolutionToOwnerScopeService() {
      UUID userId = UUID.randomUUID();
      UUID pickedOrgUnitId = UUID.randomUUID();

      User user = new User();
      user.setId(userId);
      when(userRepository.findById(userId)).thenReturn(Optional.of(user));

      de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron resolved =
          new de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron();
      resolved.setId(pickedOrgUnitId);
      when(orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(user, pickedOrgUnitId))
          .thenReturn(resolved);

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      SpaceStation station = new SpaceStation();
      station.setHasRefineryTerminal(true);
      loc.setSpaceStation(station);
      RefineryOrder transientOrder = new RefineryOrder();
      transientOrder.setLocation(loc);
      when(locationRepository.findById(loc.getId())).thenReturn(Optional.of(loc));
      when(refineryOrderRepository.save(any(RefineryOrder.class)))
          .thenAnswer(i -> i.getArgument(0));

      RefineryOrder saved = service.createRefineryOrder(userId, transientOrder, pickedOrgUnitId);

      assertSame(
          resolved,
          saved.getOwningOrgUnit(),
          "the picker output must be honoured verbatim, not user.getSquadron()");
    }

    @Test
    void createRefineryOrder_stripsClientSuppliedIdAndVersion() {
      UUID userId = UUID.randomUUID();
      User user = new User();
      user.setId(userId);
      user.setDisplayName("PII Owner Name");
      when(userRepository.findById(userId)).thenReturn(Optional.of(user));

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      SpaceStation station = new SpaceStation();
      station.setHasRefineryTerminal(true);
      loc.setSpaceStation(station);
      when(locationRepository.findById(loc.getId())).thenReturn(Optional.of(loc));
      when(refineryOrderRepository.save(any(RefineryOrder.class)))
          .thenAnswer(i -> i.getArgument(0));

      RefineryOrder transientOrder = new RefineryOrder();
      transientOrder.setLocation(loc);
      transientOrder.setId(UUID.randomUUID());
      transientOrder.setVersion(7L);

      service.createRefineryOrder(userId, transientOrder, null);

      ArgumentCaptor<RefineryOrder> captor = ArgumentCaptor.forClass(RefineryOrder.class);
      verify(refineryOrderRepository).save(captor.capture());
      assertNull(
          captor.getValue().getId(),
          "client-supplied id must be nulled so save() does an INSERT, not a merge UPSERT");
      assertNull(
          captor.getValue().getVersion(), "client-supplied version must be nulled on create");

      ArgumentCaptor<String> labelCaptor = ArgumentCaptor.forClass(String.class);
      verify(auditService)
          .record(
              eq(
                  de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType
                      .REFINERY_ORDER_CREATED),
              any(),
              labelCaptor.capture(),
              any(),
              any());
      org.junit.jupiter.api.Assertions.assertFalse(
          labelCaptor.getValue().contains("PII Owner Name"),
          "refinery audit subjectLabel must not contain the owner's personal name");
    }
  }

  private List<NotificationEventType> publishedTypes() {
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, atLeast(0)).publishEvent(published.capture());
    return published.getAllValues().stream()
        .map(event -> ((NoticeEvent) event).eventType())
        .toList();
  }

  private String changeCodeOfLastNotice() {
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, atLeast(1)).publishEvent(published.capture());
    NoticeEvent last = (NoticeEvent) published.getAllValues().getLast();
    return last.renderParams().get("changeCode");
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

  private RefineryOrder updateEveryPart(UUID userId, RefineryOrder details, boolean isLogistician) {
    return service.updateRefineryOrderParts(
        userId, ORDER_ID, details, EnumSet.allOf(RefineryOrderPart.class), isLogistician);
  }

  private RefineryGood heldGood(UUID id) {
    RefineryGood good = new RefineryGood();
    good.setId(id);
    good.setInputMaterial(rawInput);
    good.setInputQuantity(10);
    good.setOutputQuantity(10);
    good.setQuality(500);
    return good;
  }

  private static RefineryGood newGoodWithInput(UUID inputMaterialId) {
    RefineryGood good = new RefineryGood();
    Material inputStub = new Material();
    inputStub.setId(inputMaterialId);
    good.setInputMaterial(inputStub);
    return good;
  }
}
