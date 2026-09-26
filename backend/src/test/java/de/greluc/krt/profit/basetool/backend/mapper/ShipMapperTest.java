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
    UserMapper userMapper = Mappers.getMapper(UserMapper.class);
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
    mapper = new ShipMapperImpl(userMapper, Mappers.getMapper(SquadronMapper.class));
  }

  @Test
  void toDto_shouldMapFlatAndNestedFields() {
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

    ShipDto dto = mapper.toDto(ship);

    assertNotNull(dto);
    assertEquals(shipId, dto.id());
    assertEquals("Black Beauty", dto.name());
    assertEquals("120", dto.insurance());
    assertTrue(dto.fitted());
    assertEquals(2L, dto.version());

    assertNotNull(dto.shipType());
    assertEquals(shipTypeId, dto.shipType().id());
    assertEquals("Cutlass Black", dto.shipType().name());
    assertEquals(46, dto.shipType().scu());

    assertNotNull(dto.shipType().manufacturer());
    assertEquals(manufacturerId, dto.shipType().manufacturer().id());
    assertEquals("Drake Interplanetary", dto.shipType().manufacturer().name());

    assertNotNull(dto.location());
    assertEquals(locationId, dto.location().id());
    assertEquals("Port Olisar", dto.location().name());

    assertNotNull(dto.owner());
    assertEquals(ownerId, dto.owner().id());
  }

  @Test
  void toDto_withoutOptionalRelations_shouldStillMap() {
    Ship ship = new Ship();
    ship.setId(UUID.randomUUID());
    ship.setName("Solo");
    ship.setInsurance("0");
    ship.setFitted(false);

    ShipDto dto = mapper.toDto(ship);

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
    Location loc = new Location();
    loc.setId(UUID.randomUUID());
    loc.setName("Lorville");
    loc.setHidden(false);
    loc.setVersion(3L);

    LocationDto dto = mapper.locationToDto(loc);

    assertNotNull(dto);
    assertEquals(loc.getId(), dto.id());
    assertEquals("Lorville", dto.name());
    assertFalse(dto.hidden());
    assertEquals(3L, dto.version());
  }

  @Test
  void manufacturerToDto_shouldMapAllFields() {
    Manufacturer mfr = new Manufacturer();
    mfr.setId(UUID.randomUUID());
    mfr.setName("Aegis Dynamics");
    mfr.setAbbreviation("AEGS");
    mfr.setNickname("Aegis");

    ManufacturerDto dto = mapper.manufacturerToDto(mfr);

    assertNotNull(dto);
    assertEquals(mfr.getId(), dto.id());
    assertEquals("Aegis Dynamics", dto.name());
    assertEquals("AEGS", dto.abbreviation());
    assertEquals("Aegis", dto.nickname());
  }

  @Test
  void shipTypeToDto_shouldMapManufacturerNested() {
    Manufacturer mfr = new Manufacturer();
    mfr.setId(UUID.randomUUID());
    mfr.setName("Anvil");

    ShipType type = new ShipType();
    type.setId(UUID.randomUUID());
    type.setName("Carrack");
    type.setManufacturer(mfr);
    type.setScu(456);

    ShipTypeDto dto = mapper.shipTypeToDto(type);

    assertNotNull(dto);
    assertEquals(type.getId(), dto.id());
    assertEquals("Carrack", dto.name());
    assertEquals(456, dto.scu());
    assertNotNull(dto.manufacturer());
    assertEquals("Anvil", dto.manufacturer().name());
  }

  @Test
  void shipTypeToDto_sourcesDescriptionFromRichColumns_germanPreferred() {
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
