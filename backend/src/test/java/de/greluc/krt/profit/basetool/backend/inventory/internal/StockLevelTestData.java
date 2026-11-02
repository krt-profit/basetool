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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts the rows the minimum-stock-level integration tests stand on. */
final class StockLevelTestData {

  private final JdbcTemplate jdbc;
  private final UserRepository userRepository;

  StockLevelTestData(@NotNull JdbcTemplate jdbc, @NotNull UserRepository userRepository) {
    this.jdbc = jdbc;
    this.userRepository = userRepository;
  }

  /** Inserts an active org unit of the given kind under an optional parent. */
  @NotNull
  UUID orgUnit(@NotNull String kind, @Nullable UUID parent) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible, parent_org_unit_id) VALUES (?, ?, ?, ?, TRUE, FALSE, FALSE, ?)",
        id,
        kind,
        "Unit " + id,
        "U" + id.toString().substring(0, 8),
        parent);
    return id;
  }

  /** Makes a user a member of a unit with the given rank, optionally flagged Logistician. */
  void joins(@NotNull UUID user, @NotNull UUID orgUnit, @NotNull String role, boolean logistician) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, ?, ?)",
        user,
        orgUnit,
        role,
        logistician);
  }

  /** Persists a user. */
  @NotNull
  UUID user(@NotNull String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + "-" + UUID.randomUUID());
    return userRepository.saveAndFlush(user).getId();
  }

  /** Inserts an SCU raw material. */
  @NotNull
  UUID material() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        id,
        "Stock " + id);
    return id;
  }

  /** Inserts a game item. */
  @NotNull
  UUID gameItem() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item (id, name, kind, source_systems) VALUES (?, ?, 'GENERIC',"
            + " 'UEX_ONLY')",
        id,
        "Item " + id);
    return id;
  }

  /** Inserts a location. */
  @NotNull
  UUID location() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, "Loc " + id);
    return id;
  }

  /** Inserts a minimum stock level for a material or a game item, armed. */
  @NotNull
  UUID level(
      @NotNull UUID unit,
      @Nullable UUID material,
      @Nullable UUID gameItem,
      @Nullable UUID tier,
      double minimum,
      double target) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inventory_stock_level (id, version, org_unit_id, material_id, game_item_id,"
            + " quality_tier_id, minimum_amount, target_amount) VALUES (?, 0, ?, ?, ?, ?, ?, ?)",
        id,
        unit,
        material,
        gameItem,
        tier,
        minimum,
        target);
    return id;
  }

  /** Sets the free shared stock of a unit's material to one row of the given quality and amount. */
  void setMaterialStock(
      @NotNull UUID holder,
      @NotNull UUID unit,
      @NotNull UUID location,
      @NotNull UUID material,
      int quality,
      double amount) {
    jdbc.update(
        "DELETE FROM inventory_item WHERE owning_org_unit_id = ? AND material_id = ?",
        unit,
        material);
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, version)
        VALUES (?, ?, ?, ?, ?, ?, false, false, ?, 0)
        """,
        UUID.randomUUID(),
        holder,
        material,
        location,
        quality,
        amount,
        unit);
  }
}
