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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One game item as the admin items page lists it (REQ-P4K-048). The names are game texts and reach
 * admins only.
 *
 * @param id the item
 * @param name the display name
 * @param nameDe the German name, or {@code null}
 * @param type the item type the P4K leads, or {@code null}
 * @param subType the item sub-type the P4K leads, or {@code null}
 * @param manufacturerName the manufacturer's name, or {@code null}
 * @param retired whether the P4K swept the item or an admin hid it through a review case
 * @param unobtainableSince when an admin hid the item as not obtainable, or {@code null} while the
 *     pickers offer it
 */
public record GameItemAdminDto(
    @NotNull UUID id,
    @NotNull String name,
    @Nullable String nameDe,
    @Nullable String type,
    @Nullable String subType,
    @Nullable String manufacturerName,
    boolean retired,
    @Nullable Instant unobtainableSince) {

  /**
   * Projects a game item; reads its manufacturer, so it runs inside the loading transaction.
   *
   * @param item the item
   * @return the row
   */
  @NotNull
  static GameItemAdminDto of(@NotNull GameItem item) {
    Manufacturer manufacturer = item.getManufacturer();
    return new GameItemAdminDto(
        item.getId(),
        item.getName(),
        item.getNameDe(),
        item.getWikiType(),
        item.getWikiSubType(),
        manufacturer == null ? null : manufacturer.getName(),
        item.isP4kRetired(),
        item.getUnobtainableSince());
  }
}
