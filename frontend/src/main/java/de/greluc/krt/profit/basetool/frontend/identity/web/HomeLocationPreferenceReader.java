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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyHomeLocationResponse;
import de.greluc.krt.profit.basetool.frontend.identity.model.ProfileHomeLocationSection;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads the caller's profile setting "my home location" for the pages that show it: the profile
 * section (REQ-PATCH-030) and the location default of the stock booking, the refinery store and a
 * new ship (REQ-PATCH-033). A failed read degrades to "no choice" and never fails the page.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HomeLocationPreferenceReader {

  /** The order of the profile's options: by name, ignoring case. */
  private static final Comparator<LocationDto> BY_NAME =
      Comparator.comparing(LocationDto::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

  /** Reads the setting and the curated home locations on the backend. */
  private final IdentityBackendClient identityClient;

  /**
   * Returns the caller's home location as the default of a form's location field.
   *
   * @return the chosen home location, empty when the caller made no choice or it cannot be read
   */
  public @NotNull Optional<UUID> formDefault() {
    return Optional.ofNullable(stored("home-location-default"))
        .map(MyHomeLocationResponse::homeLocationId);
  }

  /**
   * Returns what the profile's section shows; a stored choice the profile no longer offers shows as
   * none, so the select never pretends another location is chosen.
   *
   * @return the stored choice with its version and the curated home locations by name
   */
  public @NotNull ProfileHomeLocationSection profileSection() {
    MyHomeLocationResponse stored = stored("profile-home-location");
    List<LocationDto> offered =
        SectionFallback.load(
            log, "profile-home-location-options", identityClient::homeLocations, null);
    List<LocationDto> options =
        offered == null ? List.of() : offered.stream().sorted(BY_NAME).toList();
    if (stored == null) {
      return new ProfileHomeLocationSection(null, 0L, options);
    }
    UUID chosen = stored.homeLocationId();
    boolean offeredStill = options.stream().anyMatch(option -> option.id().equals(chosen));
    return new ProfileHomeLocationSection(
        offeredStill ? chosen : null, stored.version() == null ? 0L : stored.version(), options);
  }

  /**
   * Reads the stored setting.
   *
   * @param section the section name of the log line when the read fails
   * @return the setting, or {@code null} when the backend sent none or failed
   */
  private @Nullable MyHomeLocationResponse stored(@NotNull String section) {
    return SectionFallback.load(log, section, identityClient::myHomeLocation, null);
  }
}
