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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyDonationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

/** Proves real-schema item reconciliation preserves stock and combines only duplicate floors. */
@SpringBootTest
@Transactional
class InventoryItemReassignmentIntegrationTest {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @Autowired private InventoryItemReassignment participant;
  @Autowired private InventoryAggregationService aggregation;
  @Autowired private AuthHelperService authentication;
  @Autowired private SystemSettings settings;
  @Autowired private SupplyDonationService donations;

  @Test
  void stockRowsKeepTheirIdsAndQuantitiesWhileDuplicateFloorsKeepTheStrongestSettings() {
    StockLevelTestData data = new StockLevelTestData(jdbc, users);
    UUID unit = data.orgUnit("SPECIAL_COMMAND", null);
    UUID member = data.user("catalogue-merge");
    UUID location = data.location();
    UUID oldItem = data.gameItem();
    UUID canonical = data.gameItem();
    UUID oldFloor = data.level(unit, null, oldItem, null, 8, 10);
    UUID newFloor = data.level(unit, null, canonical, null, 5, 20);
    UUID stock = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, game_item_id, location_id, amount,
          personal, stolen, owning_org_unit_id, version)
        VALUES (?, ?, ?, ?, 7, false, true, ?, 0)
        """,
        stock,
        member,
        oldItem,
        location,
        unit);

    participant.reassignGameItem(oldItem, canonical);

    assertThat(
            jdbc.queryForObject(
                "SELECT game_item_id FROM inventory_item WHERE id = ?", UUID.class, stock))
        .isEqualTo(canonical);
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM inventory_item WHERE id = ?", Double.class, stock))
        .isEqualTo(7d);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM inventory_item WHERE id = ?", Long.class, stock))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_stock_level WHERE id = ?", Integer.class, oldFloor))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT minimum_amount FROM inventory_stock_level WHERE id = ?",
                Double.class,
                newFloor))
        .isEqualTo(8d);
    assertThat(
            jdbc.queryForObject(
                "SELECT target_amount FROM inventory_stock_level WHERE id = ?",
                Double.class,
                newFloor))
        .isEqualTo(20d);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE subject_id = ?", Integer.class, stock))
        .isOne();
  }

  @Test
  void retiredBookmarkReadsTheSameCanonicalStockAndCountAfterReassignment() {
    StockLevelTestData data = new StockLevelTestData(jdbc, users);
    UUID member = data.user("catalogue-stack-redirect");
    UUID location = data.location();
    UUID retired = data.gameItem();
    UUID canonical = data.gameItem();
    UUID stock = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, game_item_id, location_id, amount,
          personal, stolen, version)
        VALUES (?, ?, ?, ?, 7, true, true, 0)
        """,
        stock,
        member,
        retired,
        location);
    participant.reassignGameItem(retired, canonical);
    jdbc.update("DELETE FROM game_item WHERE id = ?", retired);
    jdbc.update(
        "INSERT INTO game_item_redirect (id, canonical_id, retired_snapshot)"
            + " VALUES (?, ?, '{}'::jsonb)",
        retired,
        canonical);

    var oldPage =
        aggregation.getMyItemStackEntries(
            member, retired, location, true, true, null, PageRequest.of(0, 1));
    var currentPage =
        aggregation.getMyItemStackEntries(
            member, canonical, location, true, true, null, PageRequest.of(0, 1));

    assertThat(oldPage.getTotalElements()).isOne().isEqualTo(currentPage.getTotalElements());
    assertThat(oldPage.getContent()).hasSize(1);
    assertThat(oldPage.getContent().getFirst().id()).isEqualTo(stock);
    assertThat(oldPage.getContent().getFirst().amount()).isEqualTo(7d);
    assertThat(oldPage.getContent().getFirst().gameItem().id()).isEqualTo(canonical);

    Jwt token =
        Jwt.withTokenValue("catalogue-read-fixture")
            .header("alg", "none")
            .subject(member.toString())
            .build();
    authentication.runAs(
        new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))),
        () -> {
          LagerFilter filter =
              LagerFilter.builder().gameItemIds(List.of(retired, canonical)).build();
          var grouped = aggregation.getMyAggregatedItemInventory(member, filter);
          assertThat(grouped).hasSize(1);
          assertThat(grouped.getFirst().totalAmount()).isEqualTo(7d);
          assertThat(aggregation.getMyItemEntryIds(member, filter)).containsExactly(stock);
          jdbc.update(
              "INSERT INTO inventory_item (id, user_id, game_item_id, location_id,"
                  + " amount, personal, stolen, version) VALUES (?, ?, ?, ?, 2, false, true, 0)",
              UUID.randomUUID(),
              member,
              canonical,
              location);
          assertThat(aggregation.getAllAggregatedItemInventory(filter)).hasSize(1);
          assertThat(
                  aggregation.getAllItemInventory(filter, PageRequest.of(0, 5)).getTotalElements())
              .isOne();
          UUID donatable = UUID.randomUUID();
          jdbc.update(
              "INSERT INTO inventory_item (id, user_id, game_item_id, location_id,"
                  + " amount, personal, stolen, version) VALUES (?, ?, ?, ?, 3, true, false, 0)",
              donatable,
              member,
              canonical,
              location);
          settings.putSettingValue("supply.enabled", "true");
          assertThat(donations.sources(null, null, retired)).hasSize(1);
          assertThat(donations.sources(null, null, retired).getFirst().id()).isEqualTo(donatable);
        });
  }
}
