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

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.withinProfit;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Verifies published stock facts, immutable lot snapshots and held live row identity. */
@SpringBootTest
@Transactional
class StockRowViewCharacterisationTest {

  @Autowired private StockCommands stockCommands;

  @Autowired private CityRepository cityRepository;
  @Autowired private StockReads stockReads;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private MissionRepository missionRepository;
  @PersistenceContext private EntityManager entityManager;

  private User owner;
  private Squadron unit;
  private Material material;
  private Location location;
  private InventoryItem row;
  private UUID orderId;

  @BeforeEach
  void setUp() {
    owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("row-view-" + UUID.randomUUID());
    userRepository.saveAndFlush(owner);
    unit = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    withinProfit(unit, persistedProfitArea(orgUnitRepository));
    unit = squadronRepository.saveAndFlush(unit);
    material = new Material();
    material.setName("row-view-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.saveAndFlush(material);
    location = new Location();
    location.setName("row-view-" + UUID.randomUUID());
    locationRepository.saveAndFlush(TestPlaces.onOwnCity(location, cityRepository));
    JobOrder order = new JobOrder();
    order.setResponsibleOrgUnit(unit);
    order.setRequestingOrgUnit(unit);
    order.setHandle("row-view-" + UUID.randomUUID());
    orderId = jobOrderRepository.saveAndFlush(order).getId();

    row = new InventoryItem();
    row.setUser(owner);
    row.setOwningOrgUnit(unit);
    row.setMaterial(material);
    row.setLocation(location);
    row.setQuality(612);
    row.setAmount(7.5);
    row.setPersonal(false);
    row.setStolen(true);
    InventoryAllocations.addJobOrder(row, orderId, 2.5, false);
    Mission mission = new Mission();
    mission.setName("row-view-" + UUID.randomUUID());
    mission.setStatus("PLANNED");
    mission.setOwningOrgUnit(unit);
    InventoryAllocations.addMission(row, missionRepository.saveAndFlush(mission).getId(), 1.25);
    row = inventoryItemRepository.saveAndFlush(row);
    entityManager.clear();
  }

  @Test
  void everyEntryTheForeignModulesUseReturnsTheRowWithTheValuesItWasStoredWith() {
    var locked = stockCommands.lockRow(row.getId()).orElseThrow();
    var found = stockReads.findRow(row.getId()).orElseThrow();
    var withMaterial = stockReads.findRowsWithMaterial(List.of(row.getId())).getFirst();
    StockLot lot = new StockLot(material, null, location, 612, true);
    var lotRows = stockReads.lotRows(owner.getId(), List.of(lot)).get(lot.key());
    var releasable = stockReads.releasableRows(owner.getId(), null, true, false, 10);

    assertThat(lotRows).hasSize(1);
    assertThat(releasable).extracting(r -> r.getId()).contains(row.getId());

    assertThat(locked).isSameAs(found).isSameAs(withMaterial);
    assertStoredFacts(locked);
    assertStoredFacts(lotRows.getFirst());
  }

  @Test
  void aCommandIsSeenOnTheSameRowInstanceTheCallerAlreadyHolds() {
    var held = stockCommands.lockRow(row.getId()).orElseThrow();
    StockLot lot = new StockLot(material, null, location, 612, true);
    StockRow snapshot = stockReads.lotRows(owner.getId(), List.of(lot)).get(lot.key()).getFirst();

    stockCommands.releaseJobOrderEarmark(held.getId(), orderId);

    assertThat(jobOrderReserved(held)).isZero();
    assertThat(jobOrderSliceAmount(held, orderId)).isNull();
    assertThat(missionReserved(held)).isEqualTo(1.25);
    assertThat(stockReads.findRow(row.getId()).orElseThrow()).isSameAs(held);
    assertStoredFacts(snapshot);
  }

  /** Checks every persisted physical and allocation fact used by foreign modules. */
  private void assertStoredFacts(@NotNull StockRow item) {
    assertThat(item.getId()).isEqualTo(row.getId());
    assertThat(item.getVersion()).isEqualTo(row.getVersion());
    assertThat(item.getUser().getId()).isEqualTo(owner.getId());
    assertThat(item.getOwningOrgUnit().getId()).isEqualTo(unit.getId());
    assertThat(item.getMaterial().getId()).isEqualTo(material.getId());
    assertThat(item.getGameItem()).isNull();
    assertThat(item.getLocation().getId()).isEqualTo(location.getId());
    assertThat(item.getQuality()).isEqualTo(612);
    assertThat(item.getAmount()).isEqualTo(7.5);
    assertThat(item.getPersonal()).isFalse();
    assertThat(item.getStolen()).isTrue();
    assertThat(jobOrderReserved(item)).isEqualTo(2.5);
    assertThat(missionReserved(item)).isEqualTo(1.25);
    assertThat(jobOrderSliceAmount(item, orderId)).isEqualTo(2.5);
    assertThat(jobOrderSliceAmount(item, UUID.randomUUID())).isNull();
  }

  private static double jobOrderReserved(StockRow item) {
    return item.jobOrderReserved();
  }

  private static double missionReserved(StockRow item) {
    return item.missionReserved();
  }

  private static Double jobOrderSliceAmount(StockRow item, UUID jobOrderId) {
    return item.isEarmarkedForJobOrder(jobOrderId) ? item.jobOrderSliceAmount(jobOrderId) : null;
  }
}
