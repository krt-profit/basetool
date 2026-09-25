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
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderDto;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class RefineryOrderMapperTest {

  private RefineryOrderMapper mapper;

  @BeforeEach
  void setUp() {
    mapper =
        new RefineryOrderMapperImpl(
            Mappers.getMapper(UserMapper.class),
            new MaterialMapperImpl(new MaterialCategoryMapperImpl()),
            Mappers.getMapper(SquadronMapper.class),
            new LocationMapperImpl(),
            new RefiningMethodMapperImpl());
  }

  @Test
  void computeProfit_shouldBeSalesMinusExpensesMinusOtherExpenses() {
    RefineryOrder order = new RefineryOrder();
    order.setOreSales(1000.0);
    order.setExpenses(200.0);
    order.setOtherExpenses(50.0);

    double profit = mapper.computeProfit(order);

    assertEquals(750.0, profit, 0.0001);
  }

  @Test
  void computeProfit_withNullFields_shouldTreatAsZero() {
    RefineryOrder order = new RefineryOrder();
    order.setOreSales(null);
    order.setExpenses(null);
    order.setOtherExpenses(null);

    double profit = mapper.computeProfit(order);

    assertEquals(0.0, profit, 0.0001);
  }

  @Test
  void computeProfit_withOnlySales_shouldEqualSales() {
    RefineryOrder order = new RefineryOrder();
    order.setOreSales(500.0);
    order.setExpenses(null);
    order.setOtherExpenses(null);

    double profit = mapper.computeProfit(order);

    assertEquals(500.0, profit, 0.0001);
  }

  @Test
  void computeProfit_withLossScenario_shouldBeNegative() {
    RefineryOrder order = new RefineryOrder();
    order.setOreSales(100.0);
    order.setExpenses(150.0);
    order.setOtherExpenses(25.0);

    double profit = mapper.computeProfit(order);

    assertEquals(-75.0, profit, 0.0001);
  }

  @Test
  void computeProfit_nullEntity_shouldReturnZero() {
    assertEquals(0.0, mapper.computeProfit(null), 0.0001);
  }

  @Test
  void toDto_shouldIncludeComputedProfit() {
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

    var dto = mapper.toDto(order);

    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals(375.0, dto.profit(), 0.0001);
    assertEquals(120L, dto.durationMinutes());
    assertNotNull(dto.location());
    assertEquals("ARC-L1", dto.location().name());
  }

  @Test
  void toListDto_shouldIncludeComputedProfit() {
    UUID id = UUID.randomUUID();
    RefineryOrder order = new RefineryOrder();
    order.setId(id);
    order.setOreSales(1200.0);
    order.setExpenses(300.0);
    order.setOtherExpenses(null);

    var dto = mapper.toListDto(order);

    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals(900.0, dto.profit(), 0.0001);
  }

  @Test
  void toListDto_shouldProjectStaffelOwnerIntoOwningSquadron() {
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    squadron.setName("IRIDIUM");
    squadron.setShorthand("IRI");
    RefineryOrder order = new RefineryOrder();
    order.setId(UUID.randomUUID());
    order.setOwningOrgUnit(squadron);

    var dto = mapper.toListDto(order);

    assertNotNull(dto);
    assertNotNull(dto.owningSquadron(), "Staffel owner must surface on the list row");
    assertEquals(squadron.getId(), dto.owningSquadron().id());
    assertEquals("IRI", dto.owningSquadron().shorthand());
  }

  @Test
  void toListDto_shouldProjectSpecialCommandOwnerIntoOwningSquadron() {
    SpecialCommand sk = new SpecialCommand();
    sk.setId(UUID.randomUUID());
    sk.setName("Special Command Alpha");
    sk.setShorthand("SKA");
    RefineryOrder order = new RefineryOrder();
    order.setId(UUID.randomUUID());
    order.setOwningOrgUnit(sk);

    var dto = mapper.toListDto(order);

    assertNotNull(dto);
    assertNotNull(dto.owningSquadron(), "SK owner must surface on the list row");
    assertEquals(sk.getId(), dto.owningSquadron().id());
    assertEquals("SKA", dto.owningSquadron().shorthand());
  }

  @Test
  void missionReferenceToMission_shouldOnlyCopyId() {
    UUID missionId = UUID.randomUUID();
    MissionReferenceDto dto =
        new MissionReferenceDto(missionId, "Op Sunfire", "PLANNED", Instant.now());

    Mission mission = mapper.missionReferenceToMission(dto);

    assertNotNull(mission);
    assertEquals(missionId, mission.getId());
    assertNull(mission.getName());
  }

  @Test
  void toEntity_linksTheMissionAsAnIdOnlyStub() {
    UUID missionId = UUID.randomUUID();
    RefineryOrderDto dto =
        new RefineryOrderDto(
            null,
            null,
            null,
            new MissionReferenceDto(missionId, "Op Sunfire", "PLANNED", Instant.now()),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.List.of(),
            null,
            null,
            null);

    RefineryOrder entity = mapper.toEntity(dto);

    assertEquals(missionId, entity.getMission().getId());
    assertNull(entity.getMission().getName());
  }

  @Test
  void missionReferenceToMission_nullDto_shouldReturnNull() {
    assertNull(mapper.missionReferenceToMission(null));
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto((RefineryOrder) null));
    assertNull(mapper.toListDto(null));
    assertNull(mapper.toEntity((RefineryOrderDto) null));
  }

  @Test
  void toDtoWithYieldMap_populatesYieldBonusPercent_onMatchingGoods() {
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

    RefineryOrderDto dto = mapper.toDto(order, Map.of(matA, 5, matB, -3));

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
