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
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code LocationDto}, a storage place.
 *
 * @param id the location
 * @param name the curated name, the location's main name
 * @param description the admin's description
 * @param hidden whether normal dropdowns skip the location
 * @param homeLocation whether the hangar's home-location picker offers it
 * @param version the optimistic-lock version
 * @param starmapObjectId the starmap object the place was created on (REQ-P4K-036), or {@code
 *     null}; required on create, ignored on update
 * @param p4kName the English P4K name of the linked starmap object, or {@code null}; read-only
 * @param p4kNameDe the German P4K name of the linked starmap object, or {@code null}; read-only
 */
@DtoMirror
public record LocationDto(
    UUID id,
    String name,
    String description,
    boolean hidden,
    boolean homeLocation,
    Long version,
    @Nullable UUID starmapObjectId,
    @Nullable String p4kName,
    @Nullable String p4kNameDe) {

  /**
   * Builds a location without a starmap object and without P4K names, the shape of an update.
   *
   * @param id the location
   * @param name the curated name
   * @param description the admin's description
   * @param hidden whether normal dropdowns skip the location
   * @param homeLocation whether the hangar's home-location picker offers it
   * @param version the optimistic-lock version
   */
  public LocationDto(
      UUID id,
      String name,
      String description,
      boolean hidden,
      boolean homeLocation,
      Long version) {
    this(id, name, description, hidden, homeLocation, version, null, null, null);
  }

  /**
   * Returns the P4K name the page shows beside the curated name (REQ-P4K-023, REQ-P4K-037).
   *
   * @param locale the page locale
   * @return the German P4K name on a German page when there is one, else the English one, or {@code
   *     null} when the location is linked to no starmap object
   */
  @Nullable
  public String p4kDisplayName(@Nullable Locale locale) {
    return LocalisedNames.display(p4kName, p4kNameDe, locale);
  }
}
