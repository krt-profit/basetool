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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.ShipMapper;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

@ExtendWith(MockitoExtension.class)
class HangarServiceTest {

  @Mock private ShipRepository shipRepository;
  @Mock private ShipTypeRepository shipTypeRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private MissionUnitRepository missionUnitRepository;
  @Mock private EntityManager entityManager;
  @Mock private de.greluc.krt.profit.basetool.backend.repository.UserRepository userRepository;
  @Mock private de.greluc.krt.profit.basetool.backend.service.OwnerScopeService ownerScopeService;
  @Mock private ShipMapper shipMapper;

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
        new ShipRequestDto(
            "Test",
            UUID.randomUUID(),
            "LTI",
            null,
            false,
            0L, // different version
            null);

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> hangarService.updateShip(userId, shipId, request));
  }

  // ---- getMyShipsFiltered (REQ-HANGAR-002) ----

  @Test
  void getMyShipsFiltered_delegatesToRepositoryWithTrimmedSearch() {
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 50);
    Page<Ship> page = new PageImpl<>(List.of(new Ship()));
    when(shipRepository.findByOwnerIdFiltered(userId, "Cutlass", pageable)).thenReturn(page);

    // Surrounding whitespace is trimmed before the term reaches the repository.
    Page<Ship> result = hangarService.getMyShipsFiltered(userId, "  Cutlass  ", pageable);

    assertEquals(page, result);
    verify(shipRepository).findByOwnerIdFiltered(userId, "Cutlass", pageable);
  }

  @Test
  void getMyShipsFiltered_normalisesBlankSearchToNull() {
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 50);
    Page<Ship> page = new PageImpl<>(List.of());
    when(shipRepository.findByOwnerIdFiltered(userId, null, pageable)).thenReturn(page);

    // A blank/whitespace-only term means "no filter": the repository receives null, not "".
    hangarService.getMyShipsFiltered(userId, "   ", pageable);

    verify(shipRepository).findByOwnerIdFiltered(userId, null, pageable);
  }

  // ---- deleteAllShipsForUser ----

  @Test
  void deleteAllShipsForUser_DeletesAllShipsAndUnlinksUnits() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID shipId1 = UUID.randomUUID();
    UUID shipId2 = UUID.randomUUID();

    Ship ship1 = new Ship();
    ship1.setId(shipId1);
    Ship ship2 = new Ship();
    ship2.setId(shipId2);

    de.greluc.krt.profit.basetool.backend.model.MissionUnit unit =
        new de.greluc.krt.profit.basetool.backend.model.MissionUnit();
    unit.setShip(ship1);

    when(shipRepository.findByOwnerId(userId)).thenReturn(List.of(ship1, ship2));
    when(missionUnitRepository.findByShipId(shipId1)).thenReturn(List.of(unit));
    when(missionUnitRepository.findByShipId(shipId2)).thenReturn(List.of());

    // When
    hangarService.deleteAllShipsForUser(userId);

    // Then
    verify(missionUnitRepository, times(1)).save(unit);
    assertNull(unit.getShip(), "MissionUnit.ship should be null after unlink");
    verify(entityManager, times(1)).flush();
    verify(shipRepository, times(1)).deleteAll(List.of(ship1, ship2));
  }

  @Test
  void deleteAllShipsForUser_NoShips_DoesNothing() {
    // Given
    UUID userId = UUID.randomUUID();
    when(shipRepository.findByOwnerId(userId)).thenReturn(List.of());

    // When
    hangarService.deleteAllShipsForUser(userId);

    // Then
    verify(missionUnitRepository, never()).findByShipId(any());
    verify(shipRepository, never()).deleteAll(anyList());
    verify(entityManager, never()).flush();
  }

  @Test
  void deleteAllShipsForUser_OnlyDeletesOwnShips() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();

    Ship ship = new Ship();
    ship.setId(shipId);

    // Only ships of userId are returned by repository (user isolation guaranteed by query)
    when(shipRepository.findByOwnerId(userId)).thenReturn(List.of(ship));
    when(missionUnitRepository.findByShipId(shipId)).thenReturn(List.of());

    // When
    hangarService.deleteAllShipsForUser(userId);

    // Then
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

    ShipRequestDto request =
        new ShipRequestDto(
            "Test", typeId, "LTI", null, false, 1L, // matching version
            null);

    hangarService.updateShip(userId, shipId, request);

    verify(shipRepository, times(1)).save(ship);
  }

  // --- R5.d.f addShip picker delegation -----------------------------------

  @Test
  void addShip_delegatesPickerResolutionToOwnerScopeService() {
    java.util.UUID userId = java.util.UUID.randomUUID();
    java.util.UUID shipTypeId = java.util.UUID.randomUUID();
    java.util.UUID pickedOrgUnitId = java.util.UUID.randomUUID();

    de.greluc.krt.profit.basetool.backend.model.User user =
        new de.greluc.krt.profit.basetool.backend.model.User();
    user.setId(userId);

    de.greluc.krt.profit.basetool.backend.model.ShipType shipType =
        new de.greluc.krt.profit.basetool.backend.model.ShipType();
    shipType.setId(shipTypeId);

    de.greluc.krt.profit.basetool.backend.model.Squadron resolved =
        new de.greluc.krt.profit.basetool.backend.model.Squadron();
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
  }

  @Test
  void addShip_membershiplessUser_savesOwnerlessShip() {
    // A user with no org-unit membership (and no picker output) gets a null owning org unit from
    // the resolver — V132 made the column nullable so the ship persists as an ownerless personal
    // ship instead of failing the create with a 400.
    UUID userId = UUID.randomUUID();
    UUID shipTypeId = UUID.randomUUID();
    de.greluc.krt.profit.basetool.backend.model.User user =
        new de.greluc.krt.profit.basetool.backend.model.User();
    user.setId(userId);
    de.greluc.krt.profit.basetool.backend.model.ShipType shipType =
        new de.greluc.krt.profit.basetool.backend.model.ShipType();
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
    org.junit.jupiter.api.Assertions.assertSame(
        user, saved.getOwner(), "the ship is still attributable through its per-user owner");
  }

  // --- getSquadronOverview owner-detail scoping ---------------------------

  @Test
  void getSquadronOverview_withOwnerDetails_scopesDetailLookupBySamePredicateAsCounts() {
    // Given an admin pinned to a single squadron: scope = (adminAllScope=false,
    // activeOrgUnitId=squadronId, memberOrgUnitIds=empty).
    UUID squadronId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 20);
    when(ownerScopeService.currentUnitOverviewScope())
        .thenReturn(new ScopePredicate(false, squadronId, Set.of()));

    ShipType fighter = new ShipType();
    fighter.setId(UUID.randomUUID());
    fighter.setName("Fighter");

    // countShipsByType aggregates one in-scope ship of that type. List.<Object[]>of pins the
    // element type so the lone Object[] is one row, not three Object rows (varargs trap).
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
    when(shipRepository.findByShipTypeInScoped(List.of(fighter), false, squadronId, Set.of()))
        .thenReturn(List.of(inScopeShip));
    when(shipMapper.shipTypeToDto(any())).thenReturn(null);

    // When
    var page = hangarService.getSquadronOverview(pageable, true, null);

    // Then the owner breakdown is loaded by the SCOPED query, fed the exact same scope triple as
    // the counts — never an unscoped lookup that would leak a foreign OrgUnit's ship of a shared
    // type into a pinned squadron's overview.
    verify(shipRepository).findByShipTypeInScoped(List.of(fighter), false, squadronId, Set.of());
    assertEquals(1, page.getContent().size());
    assertEquals(1L, page.getContent().get(0).count());
    assertEquals(1, page.getContent().get(0).details().size());
    assertEquals("pilot", page.getContent().get(0).details().get(0).ownerName());
  }

  @Test
  void getSquadronOverview_normalizesBlankQueryToNullAndTrimsTerms() {
    // covers REQ-HANGAR-001 — a blank filter means "no filter" (the repository's null contract),
    // and surrounding whitespace from the search box never reaches the LIKE pattern.
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

    // When the filter arrives blank / padded
    hangarService.getSquadronOverview(pageable, false, "   ");
    hangarService.getSquadronOverview(pageable, false, "  Cutlass ");

    // Then the repository sees null for blank and the trimmed term otherwise
    verify(shipRepository).countShipsByType(false, squadronId, Set.of(), null, pageable);
    verify(shipRepository).countShipsByType(false, squadronId, Set.of(), "Cutlass", pageable);
  }

  // --- setHomeLocationForMyShips bulk action -------------------------------

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
}
