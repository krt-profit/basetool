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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class PersonalInventoryItemMapperTest {

  private final PersonalInventoryItemMapper mapper =
      Mappers.getMapper(PersonalInventoryItemMapper.class);

  @Test
  void shouldMapEntityToResponseAndExposeSnapshotAsLocationName() {
    UUID id = UUID.randomUUID();
    Location lorville = new Location();
    lorville.setId(UUID.randomUUID());
    lorville.setName("Lorville");
    lorville.setCity(new City());
    PersonalInventoryItem entity =
        PersonalInventoryItem.builder()
            .id(id)
            .ownerUserId(UUID.fromString("12312312-3123-4123-8123-123123123123"))
            .name("Medkit")
            .note("First aid")
            .location(lorville)
            .locationNameSnapshot("Lorville (snapshot)")
            .quantity(3)
            .build();
    entity.setVersion(7L);
    Instant now = Instant.parse("2024-01-01T00:00:00Z");
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);

    PersonalInventoryItemResponse response = mapper.toResponse(entity);

    assertNotNull(response);
    assertEquals(id, response.id());
    assertEquals("Medkit", response.name());
    assertEquals("First aid", response.note());
    assertEquals(lorville.getId(), response.locationId());
    assertEquals(PersonalInventoryLocationType.CITY, response.locationType());
    assertEquals(
        "Lorville (snapshot)",
        response.locationName(),
        "locationNameSnapshot must be exposed under the simpler 'locationName' DTO field");
    assertEquals(3, response.quantity());
    assertEquals(7L, response.version());
    assertEquals(now, response.createdAt());
    assertEquals(now, response.updatedAt());
  }

  @Test
  void anItemWhosePlaceNoLongerResolvesMapsWithoutLocationIdOrType() {
    PersonalInventoryItem entity =
        PersonalInventoryItem.builder()
            .name("Relic")
            .locationNameSnapshot("Vanished Outpost")
            .quantity(1)
            .build();

    PersonalInventoryItemResponse response = mapper.toResponse(entity);

    assertNull(response.locationId());
    assertNull(response.locationType());
    assertEquals("Vanished Outpost", response.locationName());
  }

  @Test
  void toEntityShouldNotPopulateOwnerLocationOrSnapshot() {
    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest("Ammo", null, UUID.randomUUID(), 200);

    PersonalInventoryItem entity = mapper.toEntity(req);

    assertNotNull(entity);
    assertEquals("Ammo", entity.getName());
    assertEquals(200, entity.getQuantity());
    assertNull(entity.getLocation(), "the location must be set by the service after its check");
    assertNull(entity.getOwnerUserId(), "ownerUserId must not be derived from the request DTO");
    assertNull(entity.getLocationNameSnapshot(), "snapshot must be set by the service");
    assertNull(entity.getId());
  }

  @Test
  void updateEntityShouldPreserveOwnerVersionLocationAndSnapshot() {
    Location old = new Location();
    old.setId(UUID.randomUUID());
    PersonalInventoryItem managed =
        PersonalInventoryItem.builder()
            .id(UUID.randomUUID())
            .ownerUserId(UUID.fromString("9e5e5e5e-0000-4000-8000-000000000001"))
            .name("Old")
            .note("Old note")
            .location(old)
            .locationNameSnapshot("OldName")
            .quantity(1)
            .build();
    managed.setVersion(5L);

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest("New", "New note", UUID.randomUUID(), 9, 5L);

    mapper.updateEntity(managed, req);

    assertEquals("New", managed.getName());
    assertEquals("New note", managed.getNote());
    assertEquals(9, managed.getQuantity());
    assertSame(old, managed.getLocation(), "the location is owned by the service");
    assertEquals(
        UUID.fromString("9e5e5e5e-0000-4000-8000-000000000001"),
        managed.getOwnerUserId(),
        "ownerUserId must NEVER be overwritten by an update request");
    assertEquals(
        5L, managed.getVersion(), "version must be left to JPA; the mapper must not alter it");
    assertEquals(
        "OldName",
        managed.getLocationNameSnapshot(),
        "the snapshot is owned by the service, not by the mapper");
  }
}
