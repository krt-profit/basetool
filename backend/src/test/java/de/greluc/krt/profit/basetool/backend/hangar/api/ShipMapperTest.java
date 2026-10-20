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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ManufacturerDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ManufacturerMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeMapperImpl;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
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
            de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository.class));
    ReflectionTestUtils.setField(
        userMapper,
        "staffelMembershipResolver",
        new de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver(
            org.mockito.Mockito.mock(
                de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository.class),
            org.mockito.Mockito.mock(
                de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository.class)));
    mapper =
        new ShipMapperImpl(
            userMapper,
            Mappers.getMapper(SquadronMapper.class),
            new ShipTypeMapperImpl(Mappers.getMapper(ManufacturerMapper.class)));
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
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.locationToDto(null));
    assertNull(mapper.manufacturerToDto(null));
  }

  @Test
  void toDto_leavesOutPledgeButKeepsStatus_whileToOwnerDtoCarriesIt() {
    Ship ship = new Ship();
    ship.setId(UUID.randomUUID());
    ship.setInsurance("LTI");
    ship.setProductionStatus(ShipProductionStatus.IN_CONCEPT);
    ship.setPledgeId("113735507");
    ship.setPledgeDate(LocalDate.of(2026, 10, 14));
    ship.setPledgeCost(new BigDecimal("275.00"));
    ship.setPledgeCostCurrency("USD");
    ship.setMeltable(true);
    ship.setGiftable(false);

    ShipDto plain = mapper.toDto(ship);
    ShipDto owner = mapper.toOwnerDto(ship);

    assertNull(plain.pledge());
    assertEquals(ShipProductionStatus.IN_CONCEPT, plain.productionStatus());
    assertEquals(
        new ShipPledgeDto(
            "113735507", LocalDate.of(2026, 10, 14), new BigDecimal("275.00"), "USD", true, false),
        owner.pledge());
  }

  @Test
  void toOwnerDto_withoutPledgeId_carriesNoPledge() {
    Ship ship = new Ship();
    ship.setId(UUID.randomUUID());
    ship.setInsurance("0");

    assertNull(mapper.toOwnerDto(ship).pledge());
  }
}
