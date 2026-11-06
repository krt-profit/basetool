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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
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

  /** The real MapStruct mapper, because its behavior is part of the service's contract. */
  @Spy
  private PersonalInventoryItemMapper mapper = Mappers.getMapper(PersonalInventoryItemMapper.class);

  @Mock private AuditService auditService;
  @InjectMocks private PersonalInventoryItemService service;

  private Location lorville;
  private Location portOlisar;

  @BeforeEach
  void setUp() {
    City city = new City();
    city.setName("Lorville");
    city.setStarSystemName("Stanton");
    city.setPlanetName("Hurston");
    lorville = location("Lorville");
    lorville.setCity(city);

    SpaceStation station = new SpaceStation();
    station.setName("Port Olisar");
    station.setStarSystemName("Stanton");
    portOlisar = location("Port Olisar");
    portOlisar.setSpaceStation(station);
  }

  @Test
  void listOwnShouldQueryRepositoryWithOwnerUserIdFilter() {
    Pageable pageable = PageRequest.of(0, 10);
    when(repository.findAllByOwnerUserId(eq(OWNER), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(sample(OWNER, 1L, lorville))));

    Page<PersonalInventoryItemResponse> result = service.listOwn(OWNER, null, pageable);

    assertEquals(1, result.getTotalElements());
    assertEquals(lorville.getId(), result.getContent().get(0).locationId());
    assertEquals(PersonalInventoryLocationType.CITY, result.getContent().get(0).locationType());
    verify(repository).findAllByOwnerUserId(OWNER, pageable);
    verify(repository, never()).findAll(any(Pageable.class));
  }

  @Test
  void listOwnShouldDelegateToFilteredQueryWhenSearchTermIsProvided() {
    Pageable pageable = PageRequest.of(0, 10);
    when(repository.findAllByOwnerUserIdAndNameContainingIgnoreCase(
            eq(OWNER), eq("med"), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of()));

    service.listOwn(OWNER, "  med  ", pageable);

    verify(repository).findAllByOwnerUserIdAndNameContainingIgnoreCase(OWNER, "med", pageable);
    verify(repository, never()).findAllByOwnerUserId(any(), any());
  }

  @Test
  void createOwnShouldStampOwnerUserIdFromAuthAndTakeTheSnapshotFromTheLocation() {
    when(repository.findSelectableLocation(OWNER, lorville.getId()))
        .thenReturn(Optional.of(lorville));
    when(repository.save(any(PersonalInventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest("Medkit", "first aid", lorville.getId(), 3);

    PersonalInventoryItemResponse result = service.createOwn(OWNER, req);

    ArgumentCaptor<PersonalInventoryItem> captor =
        ArgumentCaptor.forClass(PersonalInventoryItem.class);
    verify(repository).save(captor.capture());
    PersonalInventoryItem persisted = captor.getValue();
    assertEquals(
        OWNER,
        persisted.getOwnerUserId(),
        "ownerUserId must be taken from the JWT, not from the request");
    assertSame(lorville, persisted.getLocation());
    assertEquals("Lorville", persisted.getLocationNameSnapshot());
    assertEquals(3, result.quantity());
    assertEquals(lorville.getId(), result.locationId());
    verify(auditService)
        .record(
            eq(
                de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType
                    .PERSONAL_INVENTORY_CREATED),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void createOwnShouldRejectALocationThatIsNotSelectable() {
    UUID unknown = UUID.randomUUID();
    when(repository.findSelectableLocation(OWNER, unknown)).thenReturn(Optional.empty());

    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest("Ghost", null, unknown, 1);

    assertThrows(NotFoundException.class, () -> service.createOwn(OWNER, req));
    verify(repository, never()).save(any());
  }

  @Test
  void updateOwnShouldUseOwnerScopedLookupAndKeepAnUnchangedLocation() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItem managed = sample(OWNER, 7L, lorville);
    managed.setId(id);
    managed.setLocationNameSnapshot("Lorville (old)");

    when(repository.findByIdAndOwnerUserId(id, OWNER)).thenReturn(Optional.of(managed));
    when(repository.save(any(PersonalInventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest("Renamed", "note", lorville.getId(), 5, 7L);

    PersonalInventoryItemResponse result = service.updateOwn(OWNER, id, req);

    verify(repository, never()).findSelectableLocation(any(), any());
    assertEquals("Renamed", result.name());
    assertEquals(5, result.quantity());
    assertEquals("Lorville (old)", result.locationName());
  }

  @Test
  void updateOwnShouldKeepAnItemWhosePlaceNoLongerResolvesEditable() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItem managed = sample(OWNER, 2L, null);
    managed.setId(id);
    managed.setLocationNameSnapshot("Vanished Outpost");
    when(repository.findByIdAndOwnerUserId(id, OWNER)).thenReturn(Optional.of(managed));
    when(repository.save(any(PersonalInventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

    PersonalInventoryItemResponse result =
        service.updateOwn(
            OWNER, id, new PersonalInventoryItemUpdateRequest("Kept", null, null, 2, 2L));

    assertNull(result.locationId());
    assertNull(result.locationType());
    assertEquals("Vanished Outpost", result.locationName());
  }

  @Test
  void updateOwnShouldRejectDroppingTheLocationOfAnItemThatHasOne() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItem managed = sample(OWNER, 2L, lorville);
    managed.setId(id);
    when(repository.findByIdAndOwnerUserId(id, OWNER)).thenReturn(Optional.of(managed));

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest("x", null, null, 1, 2L);

    assertThrows(BadRequestException.class, () -> service.updateOwn(OWNER, id, req));
    verify(repository, never()).save(any());
  }

  @Test
  void updateOwnShouldThrow409OnVersionMismatch() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItem managed = sample(OWNER, 5L, lorville);
    managed.setId(id);
    when(repository.findByIdAndOwnerUserId(id, OWNER)).thenReturn(Optional.of(managed));

    PersonalInventoryItemUpdateRequest staleReq =
        new PersonalInventoryItemUpdateRequest("x", null, lorville.getId(), 1, 4L);

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> service.updateOwn(OWNER, id, staleReq));
    verify(repository, never()).save(any());
  }

  @Test
  void updateOwnShouldRefreshSnapshotWhenLocationChanges() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItem managed = sample(OWNER, 3L, lorville);
    managed.setId(id);

    when(repository.findByIdAndOwnerUserId(id, OWNER)).thenReturn(Optional.of(managed));
    when(repository.findSelectableLocation(OWNER, portOlisar.getId()))
        .thenReturn(Optional.of(portOlisar));
    when(repository.save(any(PersonalInventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest("Same", null, portOlisar.getId(), 1, 3L);

    PersonalInventoryItemResponse result = service.updateOwn(OWNER, id, req);

    assertEquals(
        "Port Olisar",
        result.locationName(),
        "Changing location must refresh the denormalized snapshot");
    assertEquals(PersonalInventoryLocationType.SPACE_STATION, result.locationType());
  }

  @Test
  void anotherUserMustNotSeeForeignItem() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndOwnerUserId(id, OTHER)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.getOwn(OTHER, id));
  }

  @Test
  void anotherUserMustNotUpdateForeignItem() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndOwnerUserId(id, OTHER)).thenReturn(Optional.empty());

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest("x", null, lorville.getId(), 1, 0L);

    assertThrows(NotFoundException.class, () -> service.updateOwn(OTHER, id, req));
    verify(repository, never()).save(any());
  }

  @Test
  void anotherUserMustNotDeleteForeignItem() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndOwnerUserId(id, OTHER)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.deleteOwn(OTHER, id));
    verify(repository, never()).delete(any());
  }

  @Test
  void adminUpdateForUserChecksTheLocationAgainstTheItemOwner() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItem foreign = sample(OWNER, 1L, lorville);
    foreign.setId(id);

    when(repository.findById(id)).thenReturn(Optional.of(foreign));
    when(repository.findSelectableLocation(OWNER, portOlisar.getId()))
        .thenReturn(Optional.of(portOlisar));
    when(repository.save(any(PersonalInventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest("Admin Touched", null, portOlisar.getId(), 9, 1L);

    PersonalInventoryItemResponse result = service.updateForUser(id, req);

    assertEquals("Admin Touched", result.name());
    assertEquals(OWNER, foreign.getOwnerUserId());
    assertEquals("Port Olisar", result.locationName());
  }

  @Test
  void searchLocationsShouldMapTheSelectableLocationsAndClampTheLimit() {
    Location admin = location("Hangar Seven");
    when(repository.findSelectableLocations(eq(OWNER), eq("port"), eq(PageRequest.of(0, 2000))))
        .thenReturn(List.of(portOlisar));
    when(repository.findSelectableLocations(eq(OWNER), eq(null), eq(PageRequest.of(0, 25))))
        .thenReturn(List.of(admin, lorville, portOlisar));

    List<PersonalInventoryLocationOption> filtered = service.searchLocations(OWNER, " port ", 9999);
    List<PersonalInventoryLocationOption> all = service.searchLocations(OWNER, "  ", null);

    assertEquals(
        List.of(
            new PersonalInventoryLocationOption(
                portOlisar.getId(),
                PersonalInventoryLocationType.SPACE_STATION,
                "Port Olisar",
                "Stanton",
                null)),
        filtered);
    assertEquals(
        List.of(
            new PersonalInventoryLocationOption(
                admin.getId(), PersonalInventoryLocationType.OTHER, "Hangar Seven", null, null),
            new PersonalInventoryLocationOption(
                lorville.getId(),
                PersonalInventoryLocationType.CITY,
                "Lorville",
                "Stanton",
                "Hurston"),
            new PersonalInventoryLocationOption(
                portOlisar.getId(),
                PersonalInventoryLocationType.SPACE_STATION,
                "Port Olisar",
                "Stanton",
                null)),
        all);
  }

  @Test
  void searchLocationsShouldMatchLikeWildcardsLiterally() {
    when(repository.findSelectableLocations(eq(OWNER), eq("100\\%"), eq(PageRequest.of(0, 1))))
        .thenReturn(List.of());

    assertTrue(service.searchLocations(OWNER, "100%", 0).isEmpty());
  }

  private static Location location(String name) {
    Location location = new Location();
    location.setId(UUID.randomUUID());
    location.setName(name);
    return location;
  }

  private static PersonalInventoryItem sample(UUID ownerUserId, long version, Location location) {
    PersonalInventoryItem e =
        PersonalInventoryItem.builder()
            .ownerUserId(ownerUserId)
            .name("Item")
            .note(null)
            .location(location)
            .locationNameSnapshot(location == null ? "Gone" : location.getName())
            .quantity(1)
            .build();
    e.setVersion(version);
    return e;
  }
}
