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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.mapper.PersonalInventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryItem;
import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UexLocationDto;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalInventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpaceStationRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class PersonalInventoryItemServiceTest {

  private static final UUID OWNER = UUID.fromString("0e000001-0000-4000-8000-000000000001");
  private static final UUID OTHER = UUID.fromString("0e000002-0000-4000-8000-000000000002");

  @Mock private PersonalInventoryItemRepository repository;

  /**
   * Use the real MapStruct-generated mapper – its behavior is part of the service's contract (e.g.,
   * snapshot vs. ownerSub propagation) and we do not want a stub to mask wiring mistakes.
   */
  @Spy
  private PersonalInventoryItemMapper mapper = Mappers.getMapper(PersonalInventoryItemMapper.class);

  @Mock private CityRepository cityRepository;

  @Mock private SpaceStationRepository spaceStationRepository;

  @Mock private AuditService auditService;
  @InjectMocks private PersonalInventoryItemService service;

  private City lorville;
  private SpaceStation portOlisar;

  @BeforeEach
  void setUp() {
    lorville = new City();
    lorville.setIdCity(42);
    lorville.setName("Lorville");
    lorville.setStarSystemName("Stanton");
    lorville.setPlanetName("Hurston");

    portOlisar = new SpaceStation();
    portOlisar.setIdSpaceStation(99);
    portOlisar.setName("Port Olisar");
    portOlisar.setStarSystemName("Stanton");
  }

  // -------------------------------------------------------------------- listOwn

  @Test
  void listOwnShouldQueryRepositoryWithOwnerSubFilter() {
    // Given
    Pageable pageable = PageRequest.of(0, 10);
    when(repository.findAllByOwnerSub(eq(OWNER), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(sample(OWNER, 1L))));

    // When
    Page<PersonalInventoryItemResponse> result = service.listOwn(OWNER, null, pageable);

    // Then
    assertEquals(1, result.getTotalElements());
    verify(repository).findAllByOwnerSub(OWNER, pageable);
    verify(repository, never()).findAll(any(Pageable.class));
  }

  @Test
  void listOwnShouldDelegateToFilteredQueryWhenSearchTermIsProvided() {
    Pageable pageable = PageRequest.of(0, 10);
    when(repository.findAllByOwnerSubAndNameContainingIgnoreCase(
            eq(OWNER), eq("med"), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of()));

    service.listOwn(OWNER, "  med  ", pageable);

    verify(repository).findAllByOwnerSubAndNameContainingIgnoreCase(OWNER, "med", pageable);
    verify(repository, never()).findAllByOwnerSub(any(), any());
  }

  // -------------------------------------------------------------------- createOwn

  @Test
  void createOwnShouldStampOwnerSubFromAuthAndResolveSnapshotFromUex() {
    // Given
    when(cityRepository.findByIdCity(42)).thenReturn(Optional.of(lorville));
    when(repository.save(any(PersonalInventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest(
            "Medkit", "first aid", 42, PersonalInventoryLocationType.CITY, 3);

    // When
    PersonalInventoryItemResponse result = service.createOwn(OWNER, req);

    // Then
    ArgumentCaptor<PersonalInventoryItem> captor =
        ArgumentCaptor.forClass(PersonalInventoryItem.class);
    verify(repository).save(captor.capture());
    PersonalInventoryItem persisted = captor.getValue();
    assertEquals(
        OWNER,
        persisted.getOwnerSub(),
        "ownerSub must be taken from the JWT, not from the request");
    assertEquals(
        "Lorville",
        persisted.getLocationNameSnapshot(),
        "snapshot must be denormalized from the local UEX mirror");
    assertEquals(3, result.quantity());
    // REQ-AUDIT-001: a personal-inventory create records exactly one PERSONAL_INVENTORY_CREATED.
    verify(auditService)
        .record(
            eq(
                de.greluc.krt.profit.basetool.backend.model.AuditEventType
                    .PERSONAL_INVENTORY_CREATED),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void createOwnShouldRejectUnknownLocationWithEntityNotFound() {
    when(spaceStationRepository.findByIdSpaceStation(404)).thenReturn(Optional.empty());

    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest(
            "Ghost", null, 404, PersonalInventoryLocationType.SPACE_STATION, 1);

    assertThrows(EntityNotFoundException.class, () -> service.createOwn(OWNER, req));
    verify(repository, never()).save(any());
  }

  // -------------------------------------------------------------------- updateOwn

  @Test
  void updateOwnShouldUseOwnerScopedLookupAndApplyChanges() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItem managed = sample(OWNER, 7L);
    managed.setId(id);
    managed.setLocationUexId(42);
    managed.setLocationType(PersonalInventoryLocationType.CITY);
    managed.setLocationNameSnapshot("Lorville");

    when(repository.findByIdAndOwnerSub(id, OWNER)).thenReturn(Optional.of(managed));
    when(repository.save(any(PersonalInventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest(
            "Renamed", "note", 42, PersonalInventoryLocationType.CITY, 5, 7L);

    // When
    PersonalInventoryItemResponse result = service.updateOwn(OWNER, id, req);

    // Then – no UEX lookup needed because the location did not change
    verify(cityRepository, never()).findByIdCity(any());
    verify(spaceStationRepository, never()).findByIdSpaceStation(any());
    assertEquals("Renamed", result.name());
    assertEquals(5, result.quantity());
  }

  @Test
  void updateOwnShouldThrow409OnVersionMismatch() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItem managed = sample(OWNER, 5L);
    managed.setId(id);
    when(repository.findByIdAndOwnerSub(id, OWNER)).thenReturn(Optional.of(managed));

    PersonalInventoryItemUpdateRequest staleReq =
        new PersonalInventoryItemUpdateRequest(
            "x", null, 42, PersonalInventoryLocationType.CITY, 1, /* stale */ 4L);

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> service.updateOwn(OWNER, id, staleReq));
    verify(repository, never()).save(any());
  }

  @Test
  void updateOwnShouldRefreshSnapshotWhenLocationChanges() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItem managed = sample(OWNER, 3L);
    managed.setId(id);
    managed.setLocationUexId(42);
    managed.setLocationType(PersonalInventoryLocationType.CITY);
    managed.setLocationNameSnapshot("Lorville");

    when(repository.findByIdAndOwnerSub(id, OWNER)).thenReturn(Optional.of(managed));
    when(spaceStationRepository.findByIdSpaceStation(99)).thenReturn(Optional.of(portOlisar));
    when(repository.save(any(PersonalInventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest(
            "Same", null, 99, PersonalInventoryLocationType.SPACE_STATION, 1, 3L);

    PersonalInventoryItemResponse result = service.updateOwn(OWNER, id, req);

    assertEquals(
        "Port Olisar",
        result.locationName(),
        "Changing location must refresh the denormalized snapshot");
    verify(spaceStationRepository).findByIdSpaceStation(99);
  }

  // ------------------------------------------------------ DATA ISOLATION

  @Test
  void anotherUserMustNotSeeForeignItem() {
    UUID id = UUID.randomUUID();
    // The repository correctly returns empty when filtered by the wrong owner.
    when(repository.findByIdAndOwnerSub(id, OTHER)).thenReturn(Optional.empty());

    assertThrows(EntityNotFoundException.class, () -> service.getOwn(OTHER, id));
  }

  @Test
  void anotherUserMustNotUpdateForeignItem() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndOwnerSub(id, OTHER)).thenReturn(Optional.empty());

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest(
            "x", null, 42, PersonalInventoryLocationType.CITY, 1, 0L);

    assertThrows(EntityNotFoundException.class, () -> service.updateOwn(OTHER, id, req));
    verify(repository, never()).save(any());
  }

  @Test
  void anotherUserMustNotDeleteForeignItem() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndOwnerSub(id, OTHER)).thenReturn(Optional.empty());

    assertThrows(EntityNotFoundException.class, () -> service.deleteOwn(OTHER, id));
    verify(repository, never()).delete(any());
  }

  // ------------------------------------------------------ ADMIN PATH

  @Test
  void adminUpdateForUserDoesNotEnforceOwnerScope() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItem foreign = sample(OWNER, 1L);
    foreign.setId(id);
    foreign.setLocationUexId(42);
    foreign.setLocationType(PersonalInventoryLocationType.CITY);
    foreign.setLocationNameSnapshot("Lorville");

    when(repository.findById(id)).thenReturn(Optional.of(foreign));
    when(repository.save(any(PersonalInventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest(
            "Admin Touched", null, 42, PersonalInventoryLocationType.CITY, 9, 1L);

    PersonalInventoryItemResponse result = service.updateForUser(id, req);

    assertEquals("Admin Touched", result.name());
    // Owner sub on the entity is preserved, even though the admin is a different user.
    assertEquals(OWNER, foreign.getOwnerSub());
  }

  // ------------------------------------------------------ UEX search

  @Test
  void searchLocationsShouldMergeAndCapResults() {
    when(cityRepository.findAll()).thenReturn(List.of(lorville));
    when(spaceStationRepository.findAll()).thenReturn(List.of(portOlisar));

    List<UexLocationDto> all = service.searchLocations(null, 25);
    assertEquals(2, all.size());

    List<UexLocationDto> filtered = service.searchLocations("port", 25);
    assertEquals(1, filtered.size());
    assertEquals(PersonalInventoryLocationType.SPACE_STATION, filtered.get(0).type());
  }

  // ------------------------------------------------------ helpers

  private static PersonalInventoryItem sample(UUID ownerSub, long version) {
    PersonalInventoryItem e =
        PersonalInventoryItem.builder()
            .ownerSub(ownerSub)
            .name("Item")
            .note(null)
            .locationUexId(42)
            .locationType(PersonalInventoryLocationType.CITY)
            .locationNameSnapshot("Lorville")
            .quantity(1)
            .build();
    e.setVersion(version);
    return e;
  }
}
