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

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

class InventoryItemMapperTest {

  private InventoryItemMapper mapper;

  @BeforeEach
  void setUp() {
    // The MapStruct-generated InventoryItemMapperImpl is annotated @Component
    // and pulls UserMapper via @Autowired. Outside of a Spring context we
    // build it via the Mappers.getMapper(...) factory and wire the
    // dependency manually so the nested toReferenceDto(...) call succeeds.
    mapper = Mappers.getMapper(InventoryItemMapper.class);
    ReflectionTestUtils.setField(mapper, "userMapper", Mappers.getMapper(UserMapper.class));
    ReflectionTestUtils.setField(mapper, "squadronMapper", Mappers.getMapper(SquadronMapper.class));
  }

  @Test
  void toDto_shouldMapJobOrderAndMissionAsFlattenedIds() {
    // Given
    UUID itemId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();

    User user = new User();
    user.setId(userId);
    user.setUsername("logist");
    user.setRank(3);

    Material material = new Material();
    material.setId(materialId);
    material.setName("Gold");
    material.setQuantityType(QuantityType.SCU);

    Location location = new Location();
    location.setId(locationId);
    location.setName("Lorville");

    JobOrder jobOrder = new JobOrder();
    jobOrder.setId(jobOrderId);
    jobOrder.setDisplayId(42);

    Mission mission = new Mission();
    mission.setId(missionId);
    mission.setName("Op Sunfire");

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setUser(user);
    item.setMaterial(material);
    item.setLocation(location);
    item.setQuality(800);
    item.setAmount(12.5);
    item.setPersonal(false);
    item.setNote("Strict QC");
    item.setVersion(7L);
    // Variante C (REQ-INV-027): earmarks live on the allocation collections, not the
    // dropped scalar jobOrder/mission/delivered columns. A single job-order slice carries
    // the delivered flag; the mapper flattens the first slice of each dimension back into
    // the soak-compat jobOrderId/missionId DTO fields asserted below.
    InventoryAllocations.addJobOrder(item, jobOrder, item.getAmount(), true);
    InventoryAllocations.addMission(item, mission, item.getAmount());

    // When
    InventoryItemDto dto = mapper.toDto(item);

    // Then
    assertNotNull(dto);
    assertEquals(itemId, dto.id());
    assertEquals(800, dto.quality());
    assertEquals(12.5, dto.amount());
    assertFalse(dto.personal());
    assertEquals("Strict QC", dto.note());
    assertEquals(7L, dto.version());

    // Flattened FK references
    assertEquals(jobOrderId, dto.jobOrderId());
    assertEquals(42, dto.jobOrderDisplayId());
    assertEquals(missionId, dto.missionId());
    assertEquals("Op Sunfire", dto.missionName());

    // Nested reference DTOs
    assertNotNull(dto.user());
    assertEquals(userId, dto.user().id());
    assertEquals("logist", dto.user().username());

    assertNotNull(dto.material());
    assertEquals(materialId, dto.material().id());
    assertEquals("Gold", dto.material().name());
    assertEquals(QuantityType.SCU, dto.material().quantityType());

    assertNotNull(dto.location());
    assertEquals(locationId, dto.location().id());
    assertEquals("Lorville", dto.location().name());
  }

  @Test
  void toDto_withoutJobOrderOrMission_shouldKeepNulls() {
    // Given an item that is unattached to a job order or mission
    User user = new User();
    user.setId(UUID.randomUUID());
    Material material = new Material();
    material.setId(UUID.randomUUID());
    Location location = new Location();
    location.setId(UUID.randomUUID());

    InventoryItem item = new InventoryItem();
    item.setId(UUID.randomUUID());
    item.setUser(user);
    item.setMaterial(material);
    item.setLocation(location);
    item.setQuality(500);
    item.setAmount(1.0);
    item.setPersonal(true);
    // No allocations added: a fresh entry's job-order / mission slice collections are
    // empty, so the mapper leaves the flattened jobOrderId/missionId DTO fields null.

    // When
    InventoryItemDto dto = mapper.toDto(item);

    // Then
    assertNull(dto.jobOrderId());
    assertNull(dto.jobOrderDisplayId());
    assertNull(dto.missionId());
    assertNull(dto.missionName());
    assertTrue(dto.personal());
  }

  @Test
  void locationToDto_shouldExposeFullSurface() {
    // Given
    Location loc = new Location();
    loc.setId(UUID.randomUUID());
    loc.setName("New Babbage");
    loc.setHidden(false);
    loc.setVersion(2L);

    // When
    LocationDto dto = mapper.locationToDto(loc);

    // Then
    assertNotNull(dto);
    assertEquals(loc.getId(), dto.id());
    assertEquals("New Babbage", dto.name());
    assertFalse(dto.hidden());
    assertEquals(2L, dto.version());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.locationToDto(null));
  }
}
