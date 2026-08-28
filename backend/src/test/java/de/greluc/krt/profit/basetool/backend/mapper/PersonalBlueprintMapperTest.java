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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Unit tests for {@link PersonalBlueprintMapper}. */
class PersonalBlueprintMapperTest {

  private final PersonalBlueprintMapper mapper = Mappers.getMapper(PersonalBlueprintMapper.class);

  @Test
  void toResponse_mapsFieldsAndFlattensOutputItemId() {
    UUID id = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    GameItem item = new GameItem();
    item.setId(itemId);

    PersonalBlueprint entity =
        PersonalBlueprint.builder()
            .id(id)
            .ownerSub(UUID.fromString("11111111-1111-1111-1111-111111111111"))
            .productKey("arclight pistol")
            .productName("Arclight Pistol")
            .outputItem(item)
            .acquiredAt(Instant.parse("2026-01-02T03:04:05Z"))
            .note("looted in Pyro")
            .build();
    entity.setVersion(4L);

    PersonalBlueprintResponse response = mapper.toResponse(entity, false);

    assertEquals(id, response.id());
    assertEquals("arclight pistol", response.productKey());
    assertEquals("Arclight Pistol", response.productName());
    assertEquals(itemId, response.outputItemId());
    assertEquals(Instant.parse("2026-01-02T03:04:05Z"), response.acquiredAt());
    assertEquals("looted in Pyro", response.note());
    assertFalse(response.removable());
    assertEquals(4L, response.version());
  }

  @Test
  void toResponse_leavesOutputItemIdNullWhenUnresolved() {
    PersonalBlueprint entity =
        PersonalBlueprint.builder()
            .id(UUID.randomUUID())
            .ownerSub(UUID.fromString("11111111-1111-1111-1111-111111111111"))
            .productKey("aril core")
            .productName("Aril Core")
            .build();

    PersonalBlueprintResponse response = mapper.toResponse(entity, true);

    assertNull(response.outputItemId());
    assertTrue(response.removable());
  }
}
