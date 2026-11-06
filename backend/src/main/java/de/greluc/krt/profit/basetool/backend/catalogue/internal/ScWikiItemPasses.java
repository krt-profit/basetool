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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Defines Wiki item scope priority and the completeness evidence produced by each pass. */
public final class ScWikiItemPasses {
  private ScWikiItemPasses() {}

  static List<KindPass> ordered(ScWikiProperties properties) {
    return List.of(
        new KindPass(
            properties.weaponAttachmentsEndpoint(),
            GameItemKind.WEAPON_ATTACHMENT,
            properties.weaponAttachmentsFilter(),
            true),
        new KindPass(
            properties.weaponsEndpoint(), GameItemKind.WEAPON, properties.weaponsFilter(), true),
        new KindPass(
            properties.vehicleWeaponsEndpoint(),
            GameItemKind.VEHICLE_WEAPON,
            properties.vehicleWeaponsFilter(),
            true),
        new KindPass(
            properties.vehicleItemsEndpoint(),
            GameItemKind.VEHICLE_ITEM,
            properties.vehicleItemsFilter(),
            true),
        new KindPass(
            properties.armorEndpoint(), GameItemKind.ARMOR, properties.armorFilter(), true),
        new KindPass(
            properties.clothesEndpoint(), GameItemKind.CLOTHING, properties.clothesFilter(), true),
        new KindPass(properties.foodEndpoint(), GameItemKind.FOOD, properties.foodFilter(), true));
  }

  /**
   * Describes one source scope in most-specific-first identity claim order.
   *
   * @param endpoint Wiki list endpoint
   * @param kind kind assigned by this endpoint
   * @param classificationFilter optional source classification filter
   * @param applySanityCap whether an oversized pool invalidates this scope
   */
  record KindPass(
      String endpoint,
      GameItemKind kind,
      @Nullable String classificationFilter,
      boolean applySanityCap) {}

  /**
   * Separates complete scope evidence from the identities it enumerated.
   *
   * @param succeeded whether this scope completely enumerated its pool
   * @param enumerated all served UUIDs, including overlap with earlier scopes
   */
  record KindPassResult(boolean succeeded, @NotNull Set<UUID> enumerated) {
    static @NotNull KindPassResult notEnumerated() {
      return new KindPassResult(false, Set.of());
    }
  }
}
