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

package de.greluc.krt.profit.basetool.backend.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryItem;
import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemUpdateRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class PersonalInventoryItemMapperTest {

  private final PersonalInventoryItemMapper mapper =
      Mappers.getMapper(PersonalInventoryItemMapper.class);

  @Test
  void shouldMapEntityToResponseAndExposeSnapshotAsLocationName() {
    // Given
    UUID id = UUID.randomUUID();
    PersonalInventoryItem entity =
        PersonalInventoryItem.builder()
            .id(id)
            .ownerSub(UUID.fromString("12312312-3123-4123-8123-123123123123"))
            .name("Medkit")
            .note("First aid")
            .locationUexId(42)
            .locationType(PersonalInventoryLocationType.CITY)
            .locationNameSnapshot("Lorville")
            .quantity(3)
            .build();
    entity.setVersion(7L);
    Instant now = Instant.parse("2024-01-01T00:00:00Z");
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);

    // When
    PersonalInventoryItemResponse response = mapper.toResponse(entity);

    // Then
    assertNotNull(response);
    assertEquals(id, response.id());
    assertEquals("Medkit", response.name());
    assertEquals("First aid", response.note());
    assertEquals(42, response.locationUexId());
    assertEquals(PersonalInventoryLocationType.CITY, response.locationType());
    assertEquals(
        "Lorville",
        response.locationName(),
        "locationNameSnapshot must be exposed under the simpler 'locationName' DTO field");
    assertEquals(3, response.quantity());
    assertEquals(7L, response.version());
    assertEquals(now, response.createdAt());
    assertEquals(now, response.updatedAt());
  }

  @Test
  void toEntityShouldNotPopulateOwnerOrSnapshot() {
    // Given
    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest(
            "Ammo", null, 10, PersonalInventoryLocationType.SPACE_STATION, 200);

    // When
    PersonalInventoryItem entity = mapper.toEntity(req);

    // Then – owner sub and snapshot must be set explicitly by the service, not by the mapper
    assertNotNull(entity);
    assertEquals("Ammo", entity.getName());
    assertEquals(PersonalInventoryLocationType.SPACE_STATION, entity.getLocationType());
    assertEquals(10, entity.getLocationUexId());
    assertEquals(200, entity.getQuantity());
    assertNull(entity.getOwnerSub(), "ownerSub must not be derived from the request DTO");
    assertNull(
        entity.getLocationNameSnapshot(), "snapshot must be set by the service after UEX lookup");
    assertNull(entity.getId());
  }

  @Test
  void updateEntityShouldPreserveOwnerVersionAndSnapshot() {
    // Given
    PersonalInventoryItem managed =
        PersonalInventoryItem.builder()
            .id(UUID.randomUUID())
            .ownerSub(UUID.fromString("9e5e5e5e-0000-4000-8000-000000000001"))
            .name("Old")
            .note("Old note")
            .locationUexId(1)
            .locationType(PersonalInventoryLocationType.CITY)
            .locationNameSnapshot("OldName")
            .quantity(1)
            .build();
    managed.setVersion(5L);

    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest(
            "New", "New note", 2, PersonalInventoryLocationType.SPACE_STATION, 9, 5L);

    // When
    mapper.updateEntity(managed, req);

    // Then
    assertEquals("New", managed.getName());
    assertEquals("New note", managed.getNote());
    assertEquals(2, managed.getLocationUexId());
    assertEquals(PersonalInventoryLocationType.SPACE_STATION, managed.getLocationType());
    assertEquals(9, managed.getQuantity());
    assertEquals(
        UUID.fromString("9e5e5e5e-0000-4000-8000-000000000001"),
        managed.getOwnerSub(),
        "ownerSub must NEVER be overwritten by an update request");
    assertEquals(
        5L, managed.getVersion(), "version must be left to JPA; the mapper must not alter it");
    assertEquals(
        "OldName",
        managed.getLocationNameSnapshot(),
        "the snapshot is owned by the service (set after UEX lookup), not by the mapper");
  }
}
