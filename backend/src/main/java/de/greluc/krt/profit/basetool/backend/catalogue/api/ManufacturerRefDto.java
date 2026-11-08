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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Slim manufacturer reference for item views: what a page needs to name a manufacturer in its
 * language (REQ-P4K-023) and to show its Fan Kit logo or abbreviation badge (REQ-P4K-022).
 *
 * @param id the manufacturer's id
 * @param name its name
 * @param nameDe its German name, or {@code null}
 * @param abbreviation its short display code
 * @param p4kUuid its P4K GUID, the key of its logo, or {@code null}
 */
public record ManufacturerRefDto(
    UUID id, String name, @Nullable String nameDe, String abbreviation, @Nullable UUID p4kUuid) {

  /**
   * Projects a manufacturer.
   *
   * @param manufacturer the manufacturer, or {@code null}
   * @return the reference, or {@code null} for a {@code null} manufacturer
   */
  @Contract("null -> null; !null -> !null")
  @Nullable
  public static ManufacturerRefDto of(@Nullable Manufacturer manufacturer) {
    if (manufacturer == null) {
      return null;
    }
    return new ManufacturerRefDto(
        manufacturer.getId(),
        manufacturer.getName(),
        manufacturer.getNameDe(),
        manufacturer.getAbbreviation(),
        manufacturer.getP4kUuid());
  }
}
