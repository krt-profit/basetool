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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks versioned allocation release and retained stock through Inventory's public command seam.
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class InventoryAllocationSoakDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private StockCommands stockCommands;
  @Autowired private StockReads stockReads;
  @Autowired private CityRepository cityRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private JdbcTemplate jdbc;

  @ParameterizedTest
  @EnumSource(ReleaseScope.class)
  void releasesOnlyMatchingSlicesAndAdvancesTheirExposedTokenExactlyWhenChanged(
      ReleaseScope scope) {
    Fixture selected = seedLinkedItem(75.0, scope == ReleaseScope.GAME_ITEM);
    Fixture unrelated = seedLinkedItem(25.0, false);
    inventoryItemRepository.flush();
    Snapshot before = snapshot(selected);
    Snapshot untouched = snapshot(unrelated);

    release(scope, selected);

    Snapshot after = snapshot(selected);
    assertThat(after.amount()).isEqualTo(75.0);
    assertThat(after.allocated()).isZero();
    assertThat(after.version()).isGreaterThan(before.version());
    assertThat(snapshot(unrelated)).isEqualTo(untouched);
    assertThat(removalAudits(selected.itemId())).isEqualTo(1);

    release(scope, selected);

    assertThat(snapshot(selected)).isEqualTo(after);
    assertThat(snapshot(unrelated)).isEqualTo(untouched);
    assertThat(removalAudits(selected.itemId())).isEqualTo(1);
  }

  private void release(ReleaseScope scope, Fixture fixture) {
    switch (scope) {
      case ALL -> stockCommands.releaseJobOrderEarmarks(fixture.orderId());
      case MATERIAL ->
          stockCommands.releaseJobOrderMaterialEarmarks(fixture.orderId(), fixture.catalogId());
      case GAME_ITEM ->
          stockCommands.releaseJobOrderGameItemEarmarks(fixture.orderId(), fixture.catalogId());
      case SINGLE -> stockCommands.releaseJobOrderEarmark(fixture.itemId(), fixture.orderId());
      case null -> throw new NullPointerException("release scope");
    }
  }

  private Snapshot snapshot(Fixture fixture) {
    var row = stockReads.findRow(fixture.itemId()).orElseThrow();
    return new Snapshot(
        row.getAmount(), row.getVersion(), row.jobOrderSliceAmount(fixture.orderId()));
  }

  private long removalAudits(UUID rowId) {
    return java.util.Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE subject_id = ? AND event_type ="
                + " 'INVENTORY_ALLOCATION_REMOVED'",
            Long.class,
            rowId));
  }

  private Fixture seedLinkedItem(double amount, boolean gameItemStock) {
    Squadron unit = new Squadron();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    unit.setName("Allocation unit " + suffix);
    unit.setShorthand("AL" + suffix);
    withinProfit(unit, persistedProfitArea(orgUnitRepository));
    squadronRepository.saveAndFlush(unit);
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(unit)
            .requestingOrgUnit(unit)
            .handle("alloc-soak-" + UUID.randomUUID())
            .status(JobOrderStatus.OPEN)
            .type(gameItemStock ? JobOrderType.ITEM : JobOrderType.MATERIAL)
            .build();
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("allocation-" + UUID.randomUUID());
    userRepository.save(user);
    Location location = new Location();
    location.setName("Allocation-" + UUID.randomUUID());
    locationRepository.save(TestPlaces.onOwnCity(location, cityRepository));
    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setLocation(location);
    item.setAmount(amount);
    item.setPersonal(false);
    item.setOwningOrgUnit(unit);
    UUID catalogId;
    if (gameItemStock) {
      GameItem gameItem = new GameItem();
      gameItem.setName("Allocation output " + UUID.randomUUID());
      gameItemRepository.save(gameItem);
      Blueprint blueprint = new Blueprint();
      blueprint.setOutputItem(gameItem);
      blueprint.setOutputName(gameItem.getName());
      blueprintRepository.save(blueprint);
      order
          .getItems()
          .add(
              JobOrderItem.builder()
                  .jobOrder(order)
                  .gameItem(gameItem)
                  .blueprint(blueprint)
                  .amount((int) amount)
                  .manufacturedAmount((int) amount)
                  .build());
      item.setGameItem(gameItem);
      catalogId = gameItem.getId();
    } else {
      Material material = new Material();
      material.setName("Allocation material " + UUID.randomUUID());
      material.setType(MaterialType.RAW);
      materialRepository.save(material);
      order.addMaterial(
          JobOrderMaterial.builder()
              .material(material)
              .qualityTier(qualityTierRepository.findByCode("GOOD").orElseThrow())
              .amount(amount)
              .build());
      item.setMaterial(material);
      item.setQuality(650);
      catalogId = material.getId();
    }
    jobOrderRepository.saveAndFlush(order);
    InventoryAllocations.addJobOrder(item, order.getId(), amount, false);
    inventoryItemRepository.saveAndFlush(item);
    return new Fixture(item.getId(), order.getId(), catalogId);
  }

  /** The owning release scopes exercised against actual PostgreSQL state. */
  private enum ReleaseScope {
    ALL,
    MATERIAL,
    GAME_ITEM,
    SINGLE
  }

  /** The identities of one existing, fully earmarked physical row. */
  private record Fixture(UUID itemId, UUID orderId, UUID catalogId) {}

  /** The public row facts whose token and quantity must remain coherent. */
  private record Snapshot(double amount, long version, double allocated) {}
}
