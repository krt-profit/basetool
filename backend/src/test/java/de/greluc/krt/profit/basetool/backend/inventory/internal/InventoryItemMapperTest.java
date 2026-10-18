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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.DonationEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.MissionEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockViewerAccess;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

class InventoryItemMapperTest {

  /** Says yes to every row, so these tests assert the mapping rather than the gate. */
  private static final StockViewerAccess ALWAYS_ALLOWED =
      new StockViewerAccess() {
        @Override
        public boolean mayEditInventoryItem(java.util.UUID inventoryItemId, UUID ownerId) {
          return true;
        }

        @Override
        public boolean mayEditJobOrder(java.util.UUID jobOrderId) {
          return true;
        }
      };

  private InventoryItemMapper mapper;

  @BeforeEach
  void setUp() {
    mapper =
        new InventoryItemMapperImpl(
            Mappers.getMapper(UserMapper.class), Mappers.getMapper(SquadronMapper.class));
    ReflectionTestUtils.setField(mapper, "stockAccess", ALWAYS_ALLOWED);
  }

  @Test
  void toDto_shouldMapJobOrderAndMissionAsFlattenedIds() {
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
    InventoryAllocations.addJobOrder(item, jobOrder.getId(), item.getAmount(), true);
    InventoryAllocations.addMission(item, mission.getId(), item.getAmount());
    UUID donationId = UUID.randomUUID();
    item.addDonationAllocation(
        InventoryDonationAllocation.builder()
            .inventoryItem(item)
            .donationId(donationId)
            .amount(4.0)
            .build());
    InventoryEarmarkLookup targets =
        new InventoryEarmarkLookup() {
          @Override
          public MissionEarmark mission(UUID id) {
            return id.equals(missionId)
                ? new MissionEarmark(missionId, mission.getName(), mission.getPlannedStartTime())
                : null;
          }

          @Override
          public DonationEarmark donation(UUID id) {
            if (id.equals(donationId)) {
              return new DonationEarmark(donationId, "Bereich Profit");
            }
            return null;
          }

          @Override
          public JobOrderEarmark jobOrder(UUID id) {
            return id.equals(jobOrderId) ? new JobOrderEarmark(jobOrderId, 42) : null;
          }
        };
    ReflectionTestUtils.setField(mapper, "earmarkTargets", targets);

    InventoryItemDto dto = mapper.toDto(item);

    assertNotNull(dto);
    assertEquals(itemId, dto.id());
    assertEquals(800, dto.quality());
    assertEquals(12.5, dto.amount());
    assertFalse(dto.personal());
    assertEquals("Strict QC", dto.note());
    assertEquals(7L, dto.version());

    assertEquals(1, dto.jobOrderAllocations().size());
    assertEquals(jobOrderId, dto.jobOrderAllocations().get(0).jobOrderId());
    assertEquals(42, dto.jobOrderAllocations().get(0).jobOrderDisplayId());
    assertEquals(1, dto.missionAllocations().size());
    assertEquals(missionId, dto.missionAllocations().get(0).missionId());
    assertEquals("Op Sunfire", dto.missionAllocations().get(0).missionName());
    assertEquals(1, dto.donationAllocations().size());
    assertEquals(donationId, dto.donationAllocations().get(0).donationId());
    assertEquals("Bereich Profit", dto.donationAllocations().get(0).receivingUnitName());
    assertEquals(4.0, dto.donationAllocations().get(0).amount());

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
  void toDto_withoutJobOrderOrMission_shouldKeepEmptyAllocations() {
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

    InventoryItemDto dto = mapper.toDto(item);

    assertTrue(dto.jobOrderAllocations().isEmpty());
    assertTrue(dto.missionAllocations().isEmpty());
    assertTrue(dto.personal());
  }

  @Test
  void toDto_canEdit_shouldCarryTheGateAnswerForTheRowAndItsOwner() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID[] asked = new UUID[2];
    ReflectionTestUtils.setField(
        mapper,
        "stockAccess",
        new StockViewerAccess() {
          @Override
          public boolean mayEditInventoryItem(UUID inventoryItemId, UUID rowOwnerId) {
            asked[0] = inventoryItemId;
            asked[1] = rowOwnerId;
            return false;
          }

          @Override
          public boolean mayEditJobOrder(UUID jobOrderId) {
            return true;
          }
        });
    User owner = new User();
    owner.setId(ownerId);
    Material material = new Material();
    material.setId(UUID.randomUUID());
    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setUser(owner);
    item.setMaterial(material);
    item.setAmount(1.0);

    InventoryItemDto dto = mapper.toDto(item);

    assertFalse(dto.canEdit());
    assertEquals(itemId, asked[0]);
    assertEquals(ownerId, asked[1]);
  }

  @Test
  void toDto_canEdit_shouldBeFalseForAnUnsavedRowWithoutAskingTheGate() {
    ReflectionTestUtils.setField(
        mapper,
        "stockAccess",
        new StockViewerAccess() {
          @Override
          public boolean mayEditInventoryItem(UUID inventoryItemId, UUID ownerId) {
            throw new AssertionError("an id-less row must not reach the gate");
          }

          @Override
          public boolean mayEditJobOrder(UUID jobOrderId) {
            return true;
          }
        });
    InventoryItem item = new InventoryItem();
    item.setAmount(1.0);

    assertFalse(mapper.toDto(item).canEdit());
  }

  @Test
  void locationToDto_shouldExposeFullSurface() {
    Location loc = new Location();
    loc.setId(UUID.randomUUID());
    loc.setName("New Babbage");
    loc.setHidden(false);
    loc.setVersion(2L);

    LocationDto dto = mapper.locationToDto(loc);

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
    assertNull(mapper.gameItemToReferenceDto(null));
  }

  @Test
  void gameItemToReferenceDto_component_carriesTypeSizeAndGrade() {
    GameItem shield = gameItem("FR-66", GameItemKind.VEHICLE_ITEM, "Shield", 1, "A");

    InventoryGameItemReferenceDto dto = mapper.gameItemToReferenceDto(shield);

    assertNotNull(dto);
    assertEquals("VEHICLE_ITEM", dto.kind());
    assertEquals("SHIELD", dto.componentType());
    assertEquals(1, dto.size());
    assertEquals("A", dto.grade());
  }

  @Test
  void gameItemToReferenceDto_component_carriesItsClass() {
    GameItem shield = gameItem("FR-66", GameItemKind.VEHICLE_ITEM, "Shield", 1, "A");
    shield.setItemClass(ShipComponentClass.MILITARY);

    InventoryGameItemReferenceDto dto = mapper.gameItemToReferenceDto(shield);

    assertNotNull(dto);
    assertEquals("MILITARY", dto.itemClass());
  }

  @Test
  void gameItemToReferenceDto_nonComponent_dropsItsClass() {
    GameItem paint = gameItem("Aurora Paint", GameItemKind.VEHICLE_ITEM, "Paints", 1, "A");
    paint.setItemClass(ShipComponentClass.CIVILIAN);

    InventoryGameItemReferenceDto dto = mapper.gameItemToReferenceDto(paint);

    assertNotNull(dto);
    assertNull(dto.itemClass());
  }

  @Test
  void gameItemToReferenceDto_nonComponent_dropsSizeAndGrade() {
    GameItem paint = gameItem("Aurora Paint", GameItemKind.VEHICLE_ITEM, "Paints", 1, "A");

    InventoryGameItemReferenceDto dto = mapper.gameItemToReferenceDto(paint);

    assertNotNull(dto);
    assertEquals("VEHICLE_ITEM", dto.kind());
    assertNull(dto.componentType());
    assertNull(dto.size());
    assertNull(dto.grade());
  }

  @Test
  void gameItemToReferenceDto_componentWithoutSizeAndGrade_keepsTypeOnly() {
    GameItem cooler = gameItem("Bracer", GameItemKind.VEHICLE_ITEM, "Cooler", null, " ");

    InventoryGameItemReferenceDto dto = mapper.gameItemToReferenceDto(cooler);

    assertNotNull(dto);
    assertEquals("COOLER", dto.componentType());
    assertNull(dto.size());
    assertNull(dto.grade());
  }

  private static GameItem gameItem(
      String name, GameItemKind kind, String wikiType, Integer size, String grade) {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setName(name);
    item.setKind(kind);
    item.setWikiType(wikiType);
    item.setSizeClass(size);
    item.setGrade(grade);
    return item;
  }
}
