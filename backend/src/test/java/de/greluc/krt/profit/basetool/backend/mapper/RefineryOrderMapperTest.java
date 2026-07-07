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
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.RefineryGood;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderDto;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

class RefineryOrderMapperTest {

  private RefineryOrderMapper mapper;

  @BeforeEach
  void setUp() {
    // RefineryOrderMapperImpl @Autowires both UserMapper and MaterialMapper —
    // wire them manually outside of a Spring context.
    mapper = Mappers.getMapper(RefineryOrderMapper.class);
    ReflectionTestUtils.setField(mapper, "userMapper", Mappers.getMapper(UserMapper.class));
    ReflectionTestUtils.setField(mapper, "materialMapper", Mappers.getMapper(MaterialMapper.class));
    ReflectionTestUtils.setField(mapper, "squadronMapper", Mappers.getMapper(SquadronMapper.class));
  }

  @Test
  void computeProfit_shouldBeSalesMinusExpensesMinusOtherExpenses() {
    // Given
    RefineryOrder order = new RefineryOrder();
    order.setOreSales(1000.0);
    order.setExpenses(200.0);
    order.setOtherExpenses(50.0);

    // When
    double profit = mapper.computeProfit(order);

    // Then
    assertEquals(750.0, profit, 0.0001);
  }

  @Test
  void computeProfit_withNullFields_shouldTreatAsZero() {
    // Given — legacy data with null finance fields
    RefineryOrder order = new RefineryOrder();
    order.setOreSales(null);
    order.setExpenses(null);
    order.setOtherExpenses(null);

    // When
    double profit = mapper.computeProfit(order);

    // Then
    assertEquals(0.0, profit, 0.0001);
  }

  @Test
  void computeProfit_withOnlySales_shouldEqualSales() {
    // Given
    RefineryOrder order = new RefineryOrder();
    order.setOreSales(500.0);
    order.setExpenses(null);
    order.setOtherExpenses(null);

    // When
    double profit = mapper.computeProfit(order);

    // Then
    assertEquals(500.0, profit, 0.0001);
  }

  @Test
  void computeProfit_withLossScenario_shouldBeNegative() {
    // Given — expenses outweigh sales
    RefineryOrder order = new RefineryOrder();
    order.setOreSales(100.0);
    order.setExpenses(150.0);
    order.setOtherExpenses(25.0);

    // When
    double profit = mapper.computeProfit(order);

    // Then
    assertEquals(-75.0, profit, 0.0001);
  }

  @Test
  void computeProfit_nullEntity_shouldReturnZero() {
    assertEquals(0.0, mapper.computeProfit(null), 0.0001);
  }

  @Test
  void toDto_shouldIncludeComputedProfit() {
    // Given
    UUID id = UUID.randomUUID();
    RefineryOrder order = new RefineryOrder();
    order.setId(id);
    order.setOreSales(500.0);
    order.setExpenses(100.0);
    order.setOtherExpenses(25.0);
    order.setDurationMinutes(120L);

    Location loc = new Location();
    loc.setId(UUID.randomUUID());
    loc.setName("ARC-L1");
    order.setLocation(loc);

    // When
    var dto = mapper.toDto(order);

    // Then
    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals(375.0, dto.profit(), 0.0001);
    assertEquals(120L, dto.durationMinutes());
    assertNotNull(dto.location());
    assertEquals("ARC-L1", dto.location().name());
  }

  @Test
  void toListDto_shouldIncludeComputedProfit() {
    // Given
    UUID id = UUID.randomUUID();
    RefineryOrder order = new RefineryOrder();
    order.setId(id);
    order.setOreSales(1200.0);
    order.setExpenses(300.0);
    order.setOtherExpenses(null);

    // When
    var dto = mapper.toListDto(order);

    // Then
    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals(900.0, dto.profit(), 0.0001);
  }

  @Test
  void toListDto_shouldProjectStaffelOwnerIntoOwningSquadron() {
    // Given a Staffel-owned order — the list row must carry the owner so the overview's
    // Staffel column is not blank (regression guard: toListDto previously dropped the owner
    // because the renamed owningOrgUnit source no longer matched the owningSquadron target).
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    squadron.setName("IRIDIUM");
    squadron.setShorthand("IRI");
    RefineryOrder order = new RefineryOrder();
    order.setId(UUID.randomUUID());
    order.setOwningOrgUnit(squadron);

    // When
    var dto = mapper.toListDto(order);

    // Then
    assertNotNull(dto);
    assertNotNull(dto.owningSquadron(), "Staffel owner must surface on the list row");
    assertEquals(squadron.getId(), dto.owningSquadron().id());
    assertEquals("IRI", dto.owningSquadron().shorthand());
  }

  @Test
  void toListDto_shouldProjectSpecialCommandOwnerIntoOwningSquadron() {
    // Given an SK-owned order (SK leader without a Staffel) — it must surface its SK badge
    // on the list row instead of a blank cell.
    SpecialCommand sk = new SpecialCommand();
    sk.setId(UUID.randomUUID());
    sk.setName("Special Command Alpha");
    sk.setShorthand("SKA");
    RefineryOrder order = new RefineryOrder();
    order.setId(UUID.randomUUID());
    order.setOwningOrgUnit(sk);

    // When
    var dto = mapper.toListDto(order);

    // Then
    assertNotNull(dto);
    assertNotNull(dto.owningSquadron(), "SK owner must surface on the list row");
    assertEquals(sk.getId(), dto.owningSquadron().id());
    assertEquals("SKA", dto.owningSquadron().shorthand());
  }

  @Test
  void missionDtoToMission_shouldOnlyCopyId() {
    // Given a MissionDto where only the id is relevant for the conversion
    UUID missionId = UUID.randomUUID();
    MissionDto dto =
        new MissionDto(
            missionId,
            "Op Sunfire",
            "desc",
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null, // 5 Instants + 1 status set above
            null,
            null,
            null,
            null, // isInternal, participants, units, frequencies, subMissions, inventoryEntries,
            // refineryOrders
            null,
            null,
            null, // operation, owner, managers
            null,
            null,
            null, // canEdit, canManageManagers
            null, // version
            null,
            null,
            null, // coreVersion, scheduleVersion, flagsVersion
            null,
            null, // checkedInParticipants, registeredParticipants
            null, // owningSquadron
            null, // partyLeadUser
            null, // partyLeadGuestName
            0L, // partyLeadVersion
            null, // steps
            null, // stepsVersion
            null, // objectives
            null, // objectivesVersion
            null // meetingPoint
            );

    // When
    Mission mission = mapper.missionDtoToMission(dto);

    // Then
    assertNotNull(mission);
    assertEquals(missionId, mission.getId());
    // name and other fields are NOT copied — only id is used for FK linkage
    assertNull(mission.getName());
  }

  @Test
  void missionDtoToMission_nullDto_shouldReturnNull() {
    assertNull(mapper.missionDtoToMission(null));
  }

  @Test
  void locationToDto_shouldExposeFullSurface() {
    // Given
    Location loc = new Location();
    loc.setId(UUID.randomUUID());
    loc.setName("CRU-L4");
    loc.setHidden(false);
    loc.setVersion(1L);

    // When
    LocationDto dto = mapper.locationToDto(loc);

    // Then
    assertNotNull(dto);
    assertEquals(loc.getId(), dto.id());
    assertEquals("CRU-L4", dto.name());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.toListDto(null));
    assertNull(mapper.toEntity(null));
    assertNull(mapper.locationToDto(null));
  }

  @Test
  void toDtoWithYieldMap_populatesYieldBonusPercent_onMatchingGoods() {
    // Given — an order with two goods, two materials
    UUID matA = UUID.randomUUID();
    UUID matB = UUID.randomUUID();

    Material a = newRawMaterial(matA, "Quantanium");
    Material b = newRawMaterial(matB, "Laranite");

    RefineryOrder order = new RefineryOrder();
    order.setId(UUID.randomUUID());
    order.setOreSales(0d);
    order.setExpenses(0d);

    RefineryGood g1 = new RefineryGood();
    g1.setInputMaterial(a);
    g1.setInputQuantity(100);
    RefineryGood g2 = new RefineryGood();
    g2.setInputMaterial(b);
    g2.setInputQuantity(50);

    Set<RefineryGood> goods = new HashSet<>();
    goods.add(g1);
    goods.add(g2);
    order.setGoods(goods);

    // When
    RefineryOrderDto dto = mapper.toDto(order, Map.of(matA, 5, matB, -3));

    // Then — both goods carry their respective bonus; ordering is undefined (Set) but every
    // good in the output finds its bonus in the map.
    assertNotNull(dto);
    assertNotNull(dto.goods());
    assertEquals(2, dto.goods().size());
    for (RefineryGoodDto good : dto.goods()) {
      Integer expected = good.inputMaterial().id().equals(matA) ? 5 : -3;
      assertEquals(expected, good.yieldBonusPercent());
    }
  }

  @Test
  void toDtoWithYieldMap_leavesYieldNull_whenMaterialMissingFromMap() {
    UUID matA = UUID.randomUUID();
    Material a = newRawMaterial(matA, "Quantanium");

    RefineryOrder order = new RefineryOrder();
    order.setId(UUID.randomUUID());

    RefineryGood g = new RefineryGood();
    g.setInputMaterial(a);
    g.setInputQuantity(100);
    Set<RefineryGood> goods = new HashSet<>();
    goods.add(g);
    order.setGoods(goods);

    // Map without the good's material — bonus stays null (caller must distinguish from 0)
    RefineryOrderDto dto = mapper.toDto(order, Map.of(UUID.randomUUID(), 5));

    assertNotNull(dto);
    assertEquals(1, dto.goods().size());
    assertNull(dto.goods().iterator().next().yieldBonusPercent());
  }

  @Test
  void toDtoWithYieldMap_emptyMap_returnsSameAsBaseDto() {
    UUID matA = UUID.randomUUID();
    Material a = newRawMaterial(matA, "Quantanium");

    RefineryOrder order = new RefineryOrder();
    order.setId(UUID.randomUUID());
    RefineryGood g = new RefineryGood();
    g.setInputMaterial(a);
    g.setInputQuantity(100);
    Set<RefineryGood> goods = new HashSet<>();
    goods.add(g);
    order.setGoods(goods);

    RefineryOrderDto dto = mapper.toDto(order, Map.of());

    assertNotNull(dto);
    assertNull(dto.goods().iterator().next().yieldBonusPercent());
  }

  @Test
  void toDtoWithYieldMap_nullEntity_returnsNull() {
    assertNull(mapper.toDto(null, Map.of(UUID.randomUUID(), 5)));
  }

  private static Material newRawMaterial(UUID id, String name) {
    Material m = new Material();
    m.setId(id);
    m.setName(name);
    m.setType(MaterialType.RAW);
    return m;
  }
}
