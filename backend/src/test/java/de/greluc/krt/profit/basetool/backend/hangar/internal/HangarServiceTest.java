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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipDeletionObserver;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipReadinessDto;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class HangarServiceTest {

  @Mock private ShipRepository shipRepository;
  @Mock private ShipTypeRepository shipTypeRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private ShipDeletionObserver shipDeletionObserver;
  @Mock private EntityManager entityManager;
  @Mock private de.greluc.krt.profit.basetool.backend.identity.api.UserRepository userRepository;
  @Mock private de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService ownerScopeService;
  @Mock private ShipTypeMapper shipTypeMapper;
  @Mock private AuditService auditService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private UserService userService;
  @Mock private HangarUnitLiveSync unitLiveSync;

  @InjectMocks private HangarService hangarService;

  @Test
  void testUpdateShipOptimisticLocking_ThrowsWhenVersionsDiffer() {
    UUID userId = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();

    User owner = new User();
    owner.setId(userId);

    Ship ship = new Ship();
    ship.setId(shipId);
    ship.setVersion(1L);
    ship.setOwner(owner);

    when(shipRepository.findById(shipId)).thenReturn(Optional.of(ship));

    ShipRequestDto request =
        new ShipRequestDto("Test", UUID.randomUUID(), "LTI", null, false, 0L, null);

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> hangarService.updateShip(userId, shipId, request));
  }

  @Test
  void getMyShipsFiltered_delegatesToRepositoryWithTrimmedSearchAndFittedFilter() {
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 50);
    Page<Ship> page = new PageImpl<>(List.of(new Ship()));
    when(shipRepository.findByOwnerIdFiltered(userId, "Cutlass", true, pageable)).thenReturn(page);

    Page<Ship> result = hangarService.getMyShipsFiltered(userId, "  Cutlass  ", true, pageable);

    assertEquals(page, result);
    verify(shipRepository).findByOwnerIdFiltered(userId, "Cutlass", true, pageable);
  }

  @Test
  void getMyShipsFiltered_normalisesBlankSearchToNull() {
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 50);
    Page<Ship> page = new PageImpl<>(List.of());
    when(shipRepository.findByOwnerIdFiltered(userId, null, null, pageable)).thenReturn(page);

    hangarService.getMyShipsFiltered(userId, "   ", null, pageable);

    verify(shipRepository).findByOwnerIdFiltered(userId, null, null, pageable);
  }

  @Test
  void getMyReadiness_readsTheOwnersCountsFromOneAggregate() {
    UUID userId = UUID.randomUUID();
    when(shipRepository.readinessOf(userId)).thenReturn(new ShipReadinessDto(13, 9));

    ShipReadinessDto result = hangarService.getMyReadiness(userId);

    assertEquals(13, result.total());
    assertEquals(9, result.fitted());
    verify(shipRepository).readinessOf(userId);
  }

  @Test
  void deleteAllShipsForUser_DeletesAllShipsAndUnlinksUnits() {
    UUID userId = UUID.randomUUID();
    UUID shipId1 = UUID.randomUUID();
    UUID shipId2 = UUID.randomUUID();

    Ship ship1 = new Ship();
    ship1.setId(shipId1);
    Ship ship2 = new Ship();
    ship2.setId(shipId2);

    when(shipRepository.findByOwnerId(userId)).thenReturn(List.of(ship1, ship2));
    when(shipDeletionObserver.beforeShipDelete(shipId1)).thenReturn(1);
    when(shipDeletionObserver.beforeShipDelete(shipId2)).thenReturn(0);

    hangarService.deleteAllShipsForUser(userId);

    verify(shipDeletionObserver).beforeShipDelete(shipId1);
    verify(shipDeletionObserver).beforeShipDelete(shipId2);
    verify(entityManager, times(1)).flush();
    verify(shipRepository, times(1)).deleteAll(List.of(ship1, ship2));
    verify(auditService)
        .record(
            eq(AuditEventType.HANGAR_EMPTIED),
            isNull(),
            isNull(),
            eq(userId),
            argThat(d -> d.toString().contains("detachedUnits=1")));
  }

  @Test
  void deleteAllShipsForUser_NoShips_DoesNothing() {
    UUID userId = UUID.randomUUID();
    when(shipRepository.findByOwnerId(userId)).thenReturn(List.of());

    hangarService.deleteAllShipsForUser(userId);

    verify(shipDeletionObserver, never()).beforeShipDelete(any());
    verify(shipRepository, never()).deleteAll(anyList());
    verify(entityManager, never()).flush();
    verifyNoInteractions(auditService);
  }

  @Test
  void deleteAllShipsForUser_OnlyDeletesOwnShips() {
    UUID userId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();

    Ship ship = new Ship();
    ship.setId(shipId);

    when(shipRepository.findByOwnerId(userId)).thenReturn(List.of(ship));

    hangarService.deleteAllShipsForUser(userId);

    verify(shipRepository, times(1)).findByOwnerId(userId);
    verify(shipRepository, never()).findByOwnerId(otherUserId);
    verify(shipRepository, times(1)).deleteAll(List.of(ship));
  }

  @Test
  void testUpdateShipOptimisticLocking_SucceedsWhenVersionsMatch() {
    UUID userId = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();
    UUID typeId = UUID.randomUUID();

    User owner = new User();
    owner.setId(userId);

    Ship ship = new Ship();
    ship.setId(shipId);
    ship.setVersion(1L);
    ship.setOwner(owner);

    ShipType type = new ShipType();
    type.setId(typeId);

    when(shipRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(shipTypeRepository.findById(typeId)).thenReturn(Optional.of(type));
    when(shipRepository.save(any(Ship.class))).thenReturn(ship);

    ShipRequestDto request = new ShipRequestDto("Test", typeId, "LTI", null, false, 1L, null);

    hangarService.updateShip(userId, shipId, request);

    verify(shipRepository, times(1)).save(ship);
    verify(auditService)
        .record(eq(AuditEventType.HANGAR_SHIP_UPDATED), eq(shipId), any(), eq(userId), any());
  }

  @Test
  void updateShip_withNoChangedField_recordsNothing() {
    UUID userId = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();
    UUID typeId = UUID.randomUUID();
    User owner = new User();
    owner.setId(userId);
    ShipType type = new ShipType();
    type.setId(typeId);
    Ship ship = new Ship();
    ship.setId(shipId);
    ship.setOwner(owner);
    ship.setShipType(type);
    ship.setName("Test");
    ship.setInsurance("LTI");
    when(shipRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(shipTypeRepository.findById(typeId)).thenReturn(Optional.of(type));
    when(shipRepository.save(any(Ship.class))).thenReturn(ship);

    hangarService.updateShip(
        userId, shipId, new ShipRequestDto("Test", typeId, "LTI", null, false, null, null));

    verifyNoInteractions(auditService);
    verifyNoInteractions(unitLiveSync);
  }

  @Test
  void bulkUnitChange_announcesTheOldUnitsAndTheTarget() {
    UUID userId = UUID.randomUUID();
    User owner = new User();
    owner.setId(userId);
    de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron from =
        new de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron();
    from.setId(UUID.randomUUID());
    de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron target =
        new de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron();
    target.setId(UUID.randomUUID());
    Ship inFrom = new Ship();
    inFrom.setId(UUID.randomUUID());
    inFrom.setOwner(owner);
    inFrom.setOwningOrgUnit(from);
    Ship unitless = new Ship();
    unitless.setId(UUID.randomUUID());
    unitless.setOwner(owner);
    List<UUID> ids = List.of(inFrom.getId(), unitless.getId());
    when(shipRepository.lockAllByIdOrderById(ids)).thenReturn(List.of(inFrom, unitless));
    when(ownerScopeService.resolveOwnerMembershipTarget(owner, target.getId())).thenReturn(target);

    hangarService.bulkChangeShipOrgUnit(userId, ids, target.getId());

    verify(unitLiveSync).shipsChanged(java.util.Arrays.asList(target, from, null));
  }

  @Test
  void deleteShip_detachesUnitsAndRecordsBothTrails() {
    UUID userId = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();
    User owner = new User();
    owner.setId(userId);
    ShipType type = new ShipType();
    type.setName("Cutlass Black");
    Ship ship = new Ship();
    ship.setId(shipId);
    ship.setOwner(owner);
    ship.setShipType(type);
    ship.setName("Private free-text name");
    de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron unit =
        new de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron();
    unit.setId(UUID.randomUUID());
    ship.setOwningOrgUnit(unit);
    when(shipRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(shipDeletionObserver.beforeShipDelete(shipId)).thenReturn(1);

    hangarService.deleteShip(userId, shipId);

    verify(unitLiveSync).shipsChanged(List.of(unit));

    verify(shipDeletionObserver).beforeShipDelete(shipId);
    verify(auditService)
        .record(
            eq(AuditEventType.HANGAR_SHIP_DELETED),
            eq(shipId),
            eq("Cutlass Black"),
            eq(userId),
            argThat(d -> d.toString().contains("detachedUnits=1")));
  }

  private static final ActorRef ACTOR = new ActorRef(UUID.randomUUID(), "Ada");

  private List<NoticeEvent> published(int expected) {
    ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(expected)).publishEvent(captured.capture());
    return captured.getAllValues().stream().map(NoticeEvent.class::cast).toList();
  }

  @Test
  void resettingTheFittedMarksTellsEachOwnerOnceWithTheirCount() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    ShipRepository.OwnerShipCount a = org.mockito.Mockito.mock(ShipRepository.OwnerShipCount.class);
    ShipRepository.OwnerShipCount b = org.mockito.Mockito.mock(ShipRepository.OwnerShipCount.class);
    when(a.getOwnerId()).thenReturn(first);
    when(a.getShipCount()).thenReturn(3L);
    when(b.getOwnerId()).thenReturn(second);
    when(b.getShipCount()).thenReturn(1L);
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(shipRepository.countFittedByOwnerScoped(false, null, Set.of())).thenReturn(List.of(a, b));
    when(shipRepository.resetAllFittedScoped(false, null, Set.of())).thenReturn(4);
    UUID unit = UUID.randomUUID();
    when(shipRepository.findFittedOrgUnitIdsScoped(false, null, Set.of()))
        .thenReturn(List.of(unit));
    when(userService.currentActor()).thenReturn(ACTOR);

    hangarService.resetAllFittedStatus();

    verify(unitLiveSync).unitsChanged(List.of(unit));

    List<NoticeEvent> sent = published(2);
    assertEquals(first, sent.get(0).contextRecipientUserId());
    assertEquals("3", sent.get(0).renderParams().get("count"));
    assertEquals(second, sent.get(1).contextRecipientUserId());
    assertEquals(NotificationEventType.HANGAR_FITTED_RESET_FOR_OWNER, sent.get(1).eventType());
  }

  @Test
  void anAdminsShipChangesTellTheMember() {
    UUID member = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();
    ShipType type = new ShipType();
    type.setName("Cutlass Black");
    Ship ship = new Ship();
    ship.setId(shipId);
    ship.setShipType(type);
    HangarService spied = org.mockito.Mockito.spy(hangarService);
    org.mockito.Mockito.doReturn(ship).when(spied).addShip(any(), any());
    org.mockito.Mockito.doReturn(ship).when(spied).updateShip(any(), any(), any());
    org.mockito.Mockito.doReturn(0).when(spied).deleteShip(any(), any());
    when(shipRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(userService.currentActor()).thenReturn(ACTOR);
    ShipRequestDto dto =
        new ShipRequestDto("Test", UUID.randomUUID(), "LTI", null, false, null, null);

    spied.addShipByAdmin(member, dto);
    spied.updateShipByAdmin(member, shipId, dto);
    spied.deleteShipByAdmin(member, shipId);

    List<NoticeEvent> sent = published(3);
    assertEquals(
        List.of("ADDED", "UPDATED", "DELETED"),
        sent.stream().map(e -> e.renderParams().get("changeCode")).toList());
    assertEquals(member, sent.getFirst().contextRecipientUserId());
    assertEquals("Cutlass Black", sent.getFirst().renderParams().get("shipType"));
    assertEquals("Ada", sent.getFirst().renderParams().get("actor"));
  }

  @Test
  void anAdminChangingTheirOwnHangarTellsNobody() {
    UUID shipId = UUID.randomUUID();
    Ship ship = new Ship();
    ship.setId(shipId);
    ShipType type = new ShipType();
    type.setName("Cutlass Black");
    ship.setShipType(type);
    HangarService spied = org.mockito.Mockito.spy(hangarService);
    org.mockito.Mockito.doReturn(ship).when(spied).addShip(any(), any());
    when(userService.currentActor()).thenReturn(ACTOR);

    spied.addShipByAdmin(
        ACTOR.id(), new ShipRequestDto("Test", UUID.randomUUID(), "LTI", null, false, null, null));

    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void resetAllFittedStatus_unpinnedOfficer_resetsOnlyTheUnitsInReach() {
    UUID reachedUnit = UUID.randomUUID();
    UUID foreignUnit = UUID.randomUUID();
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, Set.of(reachedUnit, foreignUnit)));
    when(ownerScopeService.canActAsOfficerIn(reachedUnit)).thenReturn(true);
    when(ownerScopeService.canActAsOfficerIn(foreignUnit)).thenReturn(false);
    when(shipRepository.resetAllFittedScoped(false, null, Set.of(reachedUnit))).thenReturn(2);
    when(userService.currentActor()).thenReturn(ACTOR);

    hangarService.resetAllFittedStatus();

    verify(shipRepository).resetAllFittedScoped(false, null, Set.of(reachedUnit));
  }

  @Test
  void resetAllFittedStatus_pinnedUnit_resetsThePinnedUnitOnly() {
    UUID pinned = UUID.randomUUID();
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, pinned, Set.of(UUID.randomUUID())));
    when(ownerScopeService.canActAsOfficerIn(pinned)).thenReturn(true);
    when(shipRepository.resetAllFittedScoped(false, null, Set.of(pinned))).thenReturn(0);

    hangarService.resetAllFittedStatus();

    verify(shipRepository).resetAllFittedScoped(false, null, Set.of(pinned));
  }

  @Test
  void resetAllFittedStatus_adminAllScope_staysUnnarrowed() {
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(shipRepository.resetAllFittedScoped(true, null, Set.of())).thenReturn(0);

    hangarService.resetAllFittedStatus();

    verify(shipRepository).resetAllFittedScoped(true, null, Set.of());
    verify(ownerScopeService, never()).canActAsOfficerIn(any());
  }

  @Test
  void resetAllFittedStatus_recordsOnlyWhenShipsChanged() {
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(shipRepository.resetAllFittedScoped(false, null, Set.of())).thenReturn(0, 4);
    org.mockito.Mockito.lenient().when(userService.currentActor()).thenReturn(ACTOR);

    hangarService.resetAllFittedStatus();
    verifyNoInteractions(auditService);

    hangarService.resetAllFittedStatus();
    verify(auditService)
        .record(eq(AuditEventType.HANGAR_FITTED_RESET), isNull(), isNull(), isNull(), any());
  }

  @Test
  void addShip_delegatesPickerResolutionToOwnerScopeService() {
    java.util.UUID userId = java.util.UUID.randomUUID();
    java.util.UUID shipTypeId = java.util.UUID.randomUUID();
    java.util.UUID pickedOrgUnitId = java.util.UUID.randomUUID();

    de.greluc.krt.profit.basetool.backend.identity.api.User user =
        new de.greluc.krt.profit.basetool.backend.identity.api.User();
    user.setId(userId);

    de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType shipType =
        new de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType();
    shipType.setId(shipTypeId);

    de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron resolved =
        new de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron();
    resolved.setId(pickedOrgUnitId);

    org.mockito.Mockito.when(userRepository.findPlainById(userId))
        .thenReturn(java.util.Optional.of(user));
    org.mockito.Mockito.when(shipTypeRepository.findById(shipTypeId))
        .thenReturn(java.util.Optional.of(shipType));
    org.mockito.Mockito.when(
            ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, pickedOrgUnitId))
        .thenReturn(resolved);
    org.mockito.Mockito.when(shipRepository.save(any(Ship.class)))
        .thenAnswer(i -> i.getArguments()[0]);

    ShipRequestDto request =
        new ShipRequestDto("Picker Ship", shipTypeId, "LTI", null, false, null, pickedOrgUnitId);

    Ship saved = hangarService.addShip(userId, request);

    org.junit.jupiter.api.Assertions.assertSame(
        resolved,
        saved.getOwningOrgUnit(),
        "picker output must flow through OwnerScopeService.resolveOrgUnitForPickerOutputNullable");
    verify(unitLiveSync).shipsChanged(List.of(resolved));
  }

  @Test
  void addShip_membershiplessUser_savesOwnerlessShip() {
    UUID userId = UUID.randomUUID();
    UUID shipTypeId = UUID.randomUUID();
    de.greluc.krt.profit.basetool.backend.identity.api.User user =
        new de.greluc.krt.profit.basetool.backend.identity.api.User();
    user.setId(userId);
    de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType shipType =
        new de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType();
    shipType.setId(shipTypeId);

    org.mockito.Mockito.when(userRepository.findPlainById(userId))
        .thenReturn(java.util.Optional.of(user));
    org.mockito.Mockito.when(shipTypeRepository.findById(shipTypeId))
        .thenReturn(java.util.Optional.of(shipType));
    org.mockito.Mockito.when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, null))
        .thenReturn(null);
    org.mockito.Mockito.when(shipRepository.save(any(Ship.class)))
        .thenAnswer(i -> i.getArguments()[0]);

    ShipRequestDto request =
        new ShipRequestDto("Ownerless Ship", shipTypeId, "LTI", null, false, null, null);

    Ship saved = hangarService.addShip(userId, request);

    org.junit.jupiter.api.Assertions.assertNull(
        saved.getOwningOrgUnit(), "a membershipless user's ship must be ownerless (null org unit)");
    verify(unitLiveSync).shipsChanged(java.util.Collections.singletonList(null));
    org.junit.jupiter.api.Assertions.assertSame(
        user, saved.getOwner(), "the ship is still attributable through its per-user owner");
  }

  @Test
  void getSquadronOverview_withOwnerDetails_scopesDetailLookupBySamePredicateAsCounts() {
    UUID squadronId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 20);
    when(ownerScopeService.currentUnitOverviewScope())
        .thenReturn(new ScopePredicate(false, squadronId, Set.of()));

    ShipType fighter = new ShipType();
    fighter.setId(UUID.randomUUID());
    fighter.setName("Fighter");

    Page<Object[]> counts =
        new PageImpl<>(List.<Object[]>of(new Object[] {fighter, 1L, 1L}), pageable, 1);
    when(shipRepository.countShipsByType(false, squadronId, Set.of(), null, pageable))
        .thenReturn(counts);

    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("pilot");
    Ship inScopeShip = new Ship();
    inScopeShip.setShipType(fighter);
    inScopeShip.setOwner(owner);
    inScopeShip.setFitted(true);
    inScopeShip.setOwningOrgUnit(unit(squadronId));
    when(ownerScopeService.canActAsOfficerInOwner(squadronId)).thenReturn(true);
    when(shipRepository.findByShipTypeInScoped(List.of(fighter), false, squadronId, Set.of()))
        .thenReturn(List.of(inScopeShip));
    when(shipTypeMapper.toDto(any())).thenReturn(null);

    var page = hangarService.getSquadronOverview(pageable, true, null);

    verify(shipRepository).findByShipTypeInScoped(List.of(fighter), false, squadronId, Set.of());
    assertEquals(1, page.getContent().size());
    assertEquals(1L, page.getContent().get(0).count());
    assertEquals(1, page.getContent().get(0).details().size());
    assertEquals("pilot", page.getContent().get(0).details().get(0).ownerName());
  }

  @Test
  void getSquadronOverview_withOwnerDetails_omitsShipsOfUnitsWithoutOfficerReach() {
    UUID reachedUnit = UUID.randomUUID();
    UUID foreignUnit = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 20);
    when(ownerScopeService.currentUnitOverviewScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(reachedUnit, foreignUnit)));
    ShipType fighter = new ShipType();
    fighter.setId(UUID.randomUUID());
    when(shipRepository.countShipsByType(
            eq(false), isNull(), eq(Set.of(reachedUnit, foreignUnit)), isNull(), eq(pageable)))
        .thenReturn(new PageImpl<>(List.<Object[]>of(new Object[] {fighter, 3L, 0L}), pageable, 1));
    Ship reached = shipOf(fighter, "reached", reachedUnit);
    Ship foreign = shipOf(fighter, "foreign", foreignUnit);
    Ship ownerless = shipOf(fighter, "ownerless", null);
    when(shipRepository.findByShipTypeInScoped(
            eq(List.of(fighter)), eq(false), isNull(), eq(Set.of(reachedUnit, foreignUnit))))
        .thenReturn(List.of(reached, foreign, ownerless));
    when(ownerScopeService.canActAsOfficerInOwner(reachedUnit)).thenReturn(true);
    when(ownerScopeService.canActAsOfficerInOwner(foreignUnit)).thenReturn(false);
    when(ownerScopeService.canActAsOfficerInOwner(null)).thenReturn(false);

    var details = hangarService.getSquadronOverview(pageable, true, null).getContent().get(0);

    assertEquals(3L, details.count());
    assertEquals(
        List.of("reached"),
        details.details().stream().map(SquadronShipDetailDto::ownerName).toList());
  }

  @Test
  void getSquadronOverview_normalizesBlankQueryToNullAndTrimsTerms() {
    UUID squadronId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 10);
    when(ownerScopeService.currentUnitOverviewScope())
        .thenReturn(new ScopePredicate(false, squadronId, Set.of()));
    Page<Object[]> empty = new PageImpl<>(List.of(), pageable, 0);
    when(shipRepository.countShipsByType(
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq(squadronId),
            org.mockito.ArgumentMatchers.eq(Set.of()),
            any(),
            org.mockito.ArgumentMatchers.eq(pageable)))
        .thenReturn(empty);

    hangarService.getSquadronOverview(pageable, false, "   ");
    hangarService.getSquadronOverview(pageable, false, "  Cutlass ");

    verify(shipRepository).countShipsByType(false, squadronId, Set.of(), null, pageable);
    verify(shipRepository).countShipsByType(false, squadronId, Set.of(), "Cutlass", pageable);
  }

  @Test
  void setHomeLocationForMyShips_validHomeLocation_setsAndReturnsCount() {
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    Location location = new Location();
    location.setId(locationId);
    location.setHomeLocation(true);
    location.setHidden(false);

    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(shipRepository.setLocationForOwner(userId, location)).thenReturn(3);

    int updated = hangarService.setHomeLocationForMyShips(userId, locationId);

    assertEquals(3, updated);
    verify(shipRepository, times(1)).setLocationForOwner(userId, location);
    verify(auditService)
        .record(
            eq(AuditEventType.HANGAR_HOME_LOCATION_SET), eq(locationId), any(), eq(userId), any());
  }

  @Test
  void setHomeLocationForMyShips_nonHomeLocation_throwsBadRequestAndDoesNotUpdate() {
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    Location location = new Location();
    location.setId(locationId);
    location.setHomeLocation(false);
    location.setHidden(false);

    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));

    assertThrows(
        BadRequestException.class,
        () -> hangarService.setHomeLocationForMyShips(userId, locationId));
    verify(shipRepository, never()).setLocationForOwner(any(), any());
  }

  @Test
  void setHomeLocationForMyShips_hiddenHomeLocation_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    Location location = new Location();
    location.setId(locationId);
    location.setHomeLocation(true);
    location.setHidden(true);

    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));

    assertThrows(
        BadRequestException.class,
        () -> hangarService.setHomeLocationForMyShips(userId, locationId));
    verify(shipRepository, never()).setLocationForOwner(any(), any());
  }

  @Test
  void setHomeLocationForMyShips_missingLocation_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    when(locationRepository.findById(locationId)).thenReturn(Optional.empty());

    assertThrows(
        BadRequestException.class,
        () -> hangarService.setHomeLocationForMyShips(userId, locationId));
    verify(shipRepository, never()).setLocationForOwner(any(), any());
  }

  private static de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit unit(UUID id) {
    var unit = new de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron();
    unit.setId(id);
    return unit;
  }

  private static Ship shipOf(ShipType type, String ownerName, UUID unitId) {
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername(ownerName);
    Ship ship = new Ship();
    ship.setShipType(type);
    ship.setOwner(owner);
    ship.setOwningOrgUnit(unitId == null ? null : unit(unitId));
    return ship;
  }
}
