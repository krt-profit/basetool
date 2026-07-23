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

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipTypeDto;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

class ShipMapperTest {

  private ShipMapper mapper;

  @BeforeEach
  void setUp() {
    // ShipMapperImpl @Autowires UserMapper for owner mapping — wire it
    // manually since we are running without a Spring context.
    mapper = Mappers.getMapper(ShipMapper.class);
    UserMapper userMapper = Mappers.getMapper(UserMapper.class);
    // Post-R9 D3 (V101): UserMapper derives squadron + flags from org_unit_membership — wire the
    // membership repository plus the StaffelMembershipResolver collaborator (both mocked / empty
    // for
    // this fixture, so the squadron table is never read).
    ReflectionTestUtils.setField(
        userMapper,
        "membershipRepository",
        org.mockito.Mockito.mock(
            de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository.class));
    ReflectionTestUtils.setField(
        userMapper,
        "staffelMembershipResolver",
        new de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver(
            org.mockito.Mockito.mock(
                de.greluc.krt.profit.basetool.backend.repository.SquadronRepository.class),
            org.mockito.Mockito.mock(
                de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository.class)));
    ReflectionTestUtils.setField(mapper, "userMapper", userMapper);
    ReflectionTestUtils.setField(mapper, "squadronMapper", Mappers.getMapper(SquadronMapper.class));
  }

  @Test
  void toDto_shouldMapFlatAndNestedFields() {
    // Given
    UUID shipId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID shipTypeId = UUID.randomUUID();
    UUID manufacturerId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();

    Manufacturer mfr = new Manufacturer();
    mfr.setId(manufacturerId);
    mfr.setName("Drake Interplanetary");
    mfr.setAbbreviation("DRAK");

    ShipType type = new ShipType();
    type.setId(shipTypeId);
    type.setName("Cutlass Black");
    type.setManufacturer(mfr);
    type.setScu(46);

    Location loc = new Location();
    loc.setId(locationId);
    loc.setName("Port Olisar");

    User owner = new User();
    owner.setId(ownerId);
    owner.setUsername("pilot");

    Ship ship = new Ship();
    ship.setId(shipId);
    ship.setName("Black Beauty");
    ship.setShipType(type);
    ship.setInsurance("120");
    ship.setLocation(loc);
    ship.setFitted(true);
    ship.setOwner(owner);
    ship.setVersion(2L);

    // When
    ShipDto dto = mapper.toDto(ship);

    // Then
    assertNotNull(dto);
    assertEquals(shipId, dto.id());
    assertEquals("Black Beauty", dto.name());
    assertEquals("120", dto.insurance());
    assertTrue(dto.fitted());
    assertEquals(2L, dto.version());

    // ShipType nested
    assertNotNull(dto.shipType());
    assertEquals(shipTypeId, dto.shipType().id());
    assertEquals("Cutlass Black", dto.shipType().name());
    assertEquals(46, dto.shipType().scu());

    // Manufacturer (nested in ShipType)
    assertNotNull(dto.shipType().manufacturer());
    assertEquals(manufacturerId, dto.shipType().manufacturer().id());
    assertEquals("Drake Interplanetary", dto.shipType().manufacturer().name());

    // Location nested
    assertNotNull(dto.location());
    assertEquals(locationId, dto.location().id());
    assertEquals("Port Olisar", dto.location().name());

    // Owner nested (via UserMapper)
    assertNotNull(dto.owner());
    assertEquals(ownerId, dto.owner().id());
  }

  @Test
  void toDto_withoutOptionalRelations_shouldStillMap() {
    // Given a ship with no location and no fitted flag
    Ship ship = new Ship();
    ship.setId(UUID.randomUUID());
    ship.setName("Solo");
    ship.setInsurance("0");
    ship.setFitted(false);

    // When
    ShipDto dto = mapper.toDto(ship);

    // Then
    assertNotNull(dto);
    assertEquals("Solo", dto.name());
    assertEquals("0", dto.insurance());
    assertFalse(dto.fitted());
    assertNull(dto.location());
    assertNull(dto.shipType());
    assertNull(dto.owner());
  }

  @Test
  void locationToDto_shouldExposePublicSurface() {
    // Given
    Location loc = new Location();
    loc.setId(UUID.randomUUID());
    loc.setName("Lorville");
    loc.setHidden(false);
    loc.setVersion(3L);

    // When
    LocationDto dto = mapper.locationToDto(loc);

    // Then
    assertNotNull(dto);
    assertEquals(loc.getId(), dto.id());
    assertEquals("Lorville", dto.name());
    assertFalse(dto.hidden());
    assertEquals(3L, dto.version());
  }

  @Test
  void manufacturerToDto_shouldMapAllFields() {
    // Given
    Manufacturer mfr = new Manufacturer();
    mfr.setId(UUID.randomUUID());
    mfr.setName("Aegis Dynamics");
    mfr.setAbbreviation("AEGS");
    mfr.setNickname("Aegis");

    // When
    ManufacturerDto dto = mapper.manufacturerToDto(mfr);

    // Then
    assertNotNull(dto);
    assertEquals(mfr.getId(), dto.id());
    assertEquals("Aegis Dynamics", dto.name());
    assertEquals("AEGS", dto.abbreviation());
    assertEquals("Aegis", dto.nickname());
  }

  @Test
  void shipTypeToDto_shouldMapManufacturerNested() {
    // Given
    Manufacturer mfr = new Manufacturer();
    mfr.setId(UUID.randomUUID());
    mfr.setName("Anvil");

    ShipType type = new ShipType();
    type.setId(UUID.randomUUID());
    type.setName("Carrack");
    type.setManufacturer(mfr);
    type.setScu(456);

    // When
    ShipTypeDto dto = mapper.shipTypeToDto(type);

    // Then
    assertNotNull(dto);
    assertEquals(type.getId(), dto.id());
    assertEquals("Carrack", dto.name());
    assertEquals(456, dto.scu());
    assertNotNull(dto.manufacturer());
    assertEquals("Anvil", dto.manufacturer().name());
  }

  @Test
  void shipTypeToDto_sourcesDescriptionFromRichColumns_germanPreferred() {
    // R9 Step 2: the description wire field comes from the rich descriptionDe/descriptionEn columns
    // (German preferred), not the legacy synthesised ship_type.description column.
    ShipType german = new ShipType();
    german.setName("Carrack");
    german.setDescriptionDe("Deutsche Beschreibung");
    german.setDescriptionEn("English description");
    assertEquals("Deutsche Beschreibung", mapper.shipTypeToDto(german).description());
  }

  @Test
  void shipTypeToDto_fallsBackToEnglishDescription_whenGermanNull() {
    ShipType englishOnly = new ShipType();
    englishOnly.setName("Gladius");
    englishOnly.setDescriptionEn("English only");
    assertEquals("English only", mapper.shipTypeToDto(englishOnly).description());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.locationToDto(null));
    assertNull(mapper.manufacturerToDto(null));
    assertNull(mapper.shipTypeToDto(null));
  }
}
