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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOfferKind;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the two reads the Lager makes of the Materialbörse beyond the offer ratchet: a split by the
 * stolen marker may not leave a row below what its active offer promises, and a row an offer stands
 * on is never merged into another (REQ-MARKET-013, REQ-INV-047).
 */
@SpringBootTest
@TestPropertySource(properties = "app.inventory.stolen-marking-enabled=true")
@Transactional
@WithMockUser(roles = "ADMIN")
class MaterialExchangeStockLinksDataTest {

  @Autowired private InventoryStolenMarkService stolenMarkService;
  @Autowired private InventoryCheckoutService checkoutService;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialExchangeOfferRepository offerRepository;
  @Autowired private JdbcTemplate jdbc;

  @PersistenceContext private EntityManager entityManager;

  @Test
  void aStolenSplitThatWouldLeaveTheRowBelowItsOfferIsRefused() {
    InventoryItem row = row(user(), material(), location(), 100.0);
    offer(row, 80.0);
    entityManager.flush();
    entityManager.clear();

    assertThatThrownBy(
            () ->
                stolenMarkService.mark(
                    row.getId(),
                    new InventoryItemStolenMarkDto(row.getVersion(), true, 30.0),
                    row.getUser().getId(),
                    true))
        .isInstanceOf(BusinessConflictException.class)
        .hasMessage("error.inventory.stolen.belowOffer");
    assertThat(amountOf(row)).isEqualTo(100.0);
  }

  @Test
  void aStolenSplitThatKeepsTheOfferedAmountGoesThrough() {
    InventoryItem row = row(user(), material(), location(), 100.0);
    offer(row, 80.0);
    entityManager.flush();
    entityManager.clear();

    stolenMarkService.mark(
        row.getId(),
        new InventoryItemStolenMarkDto(row.getVersion(), true, 20.0),
        row.getUser().getId(),
        true);
    entityManager.flush();

    assertThat(amountOf(row)).isEqualTo(80.0);
  }

  @Test
  void aRowAnOfferStandsOnIsNotMergedIntoItsTwin() {
    User user = user();
    Material material = material();
    Location location = location();
    InventoryItem offered = row(user, material, location, 10.0);
    InventoryItem twin = row(user, material, location, 5.0);
    offer(offered, 4.0);
    entityManager.flush();

    InventoryItem result = checkoutService.mergeStockIfRequested(offered, true);
    entityManager.flush();

    assertThat(result.getId()).isEqualTo(offered.getId());
    assertThat(amountOf(offered)).isEqualTo(10.0);
    assertThat(amountOf(twin)).isEqualTo(5.0);
  }

  private Double amountOf(InventoryItem row) {
    return jdbc.queryForObject(
        "SELECT amount FROM inventory_item WHERE id = ?", Double.class, row.getId());
  }

  private User user() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    return userRepository.save(user);
  }

  private Material material() {
    Material material = new Material();
    material.setName("Ore-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    return materialRepository.save(material);
  }

  private Location location() {
    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    return locationRepository.save(location);
  }

  private InventoryItem row(User user, Material material, Location location, double amount) {
    OrgUnit orgUnit = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setMaterial(material);
    item.setLocation(location);
    item.setOwningOrgUnit(orgUnit);
    item.setQuality(500);
    item.setAmount(amount);
    item.setPersonal(false);
    return inventoryItemRepository.save(item);
  }

  private void offer(InventoryItem item, double offered) {
    offerRepository.save(
        MaterialExchangeOffer.builder()
            .kind(MaterialExchangeOfferKind.MATERIAL)
            .inventoryItem(item)
            .owner(item.getUser())
            .owningOrgUnit(item.getOwningOrgUnit())
            .offeredAmount(offered)
            .status(MaterialExchangeOfferStatus.ACTIVE)
            .releasedAt(Instant.parse("2026-09-27T00:00:00Z"))
            .build());
  }
}
