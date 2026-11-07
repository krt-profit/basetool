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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocalisedNames;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code ItemShopAvailabilityDto}: a shop whose known item list
 * names an item, shown to members as "available at" (REQ-P4K-044).
 *
 * @param shopId the shop
 * @param terminalName the linked UEX terminal's name, or {@code null} while unlinked
 * @param registryName the registry's shop name, or {@code null}
 * @param entityName the shop entity's name, or {@code null}
 * @param starSystem the star system, or {@code null}
 * @param placeName the English name of the shop's place, or {@code null}
 * @param placeNameDe the German name of the shop's place, or {@code null}
 */
@DtoMirror
public record ItemShopAvailabilityDto(
    UUID shopId,
    @Nullable String terminalName,
    @Nullable String registryName,
    @Nullable String entityName,
    @Nullable String starSystem,
    @Nullable String placeName,
    @Nullable String placeNameDe) {

  /**
   * Returns the name a member reads: the UEX terminal's, else the registry's, else the entity's.
   *
   * @return the shop's name, or {@code null} when it has none
   */
  @Nullable
  public String shopName() {
    if (terminalName != null) {
      return terminalName;
    }
    return registryName != null ? registryName : entityName;
  }

  /**
   * Returns the place name in a locale: the German one for German when it is not blank, else the
   * English one.
   *
   * @param locale the page locale
   * @return the place name, or {@code null} when the shop has no place
   */
  @Nullable
  public String placeName(@NotNull Locale locale) {
    return LocalisedNames.display(placeName, placeNameDe, locale);
  }
}
