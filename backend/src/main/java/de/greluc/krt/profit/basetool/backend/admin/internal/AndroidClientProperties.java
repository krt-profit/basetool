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

package de.greluc.krt.profit.basetool.backend.admin.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code app.android.version-policy.*}: which Android app builds the
 * server still serves (REQ-API-010, REQ-API-020).
 *
 * <p>The effective value of each field is the emergency override when one is set, otherwise the
 * release default committed in {@code application.yml}.
 *
 * @param release the reviewed values committed with the release, which deploy and roll back with it
 * @param emergencyOverride the break-glass values from the host environment, unset by default
 */
@Validated
@ConfigurationProperties(prefix = "app.android.version-policy")
public record AndroidClientProperties(
    @DefaultValue @NotNull @Valid Release release,
    @DefaultValue @NotNull @Valid EmergencyOverride emergencyOverride) {

  /** An absolute {@code https} URL without whitespace. */
  static final String HTTPS_URL = "^https://\\S+$";

  /** Empty, or an absolute {@code https} URL without whitespace. */
  static final String OPTIONAL_HTTPS_URL = "^(https://\\S+)?$";

  /**
   * The policy values committed with the release.
   *
   * @param minimumVersionCode the oldest {@code versionCode} still served; {@code 0} means no floor
   * @param latestVersionCode the newest published {@code versionCode}, or {@code 0} when unknown;
   *     informational only and never blocks a build
   * @param releasesUrl the release page the app opens to get a new build
   */
  public record Release(
      @DefaultValue("0") @NotNull @Min(0) Integer minimumVersionCode,
      @DefaultValue("0") @NotNull @Min(0) Integer latestVersionCode,
      @DefaultValue("https://github.com/krt-profit/basetool-android/releases/latest")
          @NotBlank
          @Pattern(regexp = HTTPS_URL)
          String releasesUrl) {}

  /**
   * Host-side values that replace the release default field by field; {@code null} or blank leaves
   * the release default in force.
   *
   * @param minimumVersionCode the floor to serve instead of the release default
   * @param latestVersionCode the newest build to announce instead of the release default
   * @param releasesUrl the release page to announce instead of the release default
   */
  public record EmergencyOverride(
      @Nullable @Min(0) Integer minimumVersionCode,
      @Nullable @Min(0) Integer latestVersionCode,
      @Nullable @Pattern(regexp = OPTIONAL_HTTPS_URL) String releasesUrl) {}

  /**
   * Returns the floor in force.
   *
   * @return the override when set, otherwise the release default
   */
  public int minimumVersionCode() {
    Integer override = emergencyOverride.minimumVersionCode();
    return override != null ? override : release.minimumVersionCode();
  }

  /**
   * Returns the newest published build in force.
   *
   * @return the override when set, otherwise the release default
   */
  public int latestVersionCode() {
    Integer override = emergencyOverride.latestVersionCode();
    return override != null ? override : release.latestVersionCode();
  }

  /**
   * Returns the release page in force.
   *
   * @return the override when set and not blank, otherwise the release default
   */
  public String releasesUrl() {
    return releasesUrlOverridden() ? emergencyOverride.releasesUrl() : release.releasesUrl();
  }

  /**
   * Tells whether the floor comes from the emergency override.
   *
   * @return {@code true} when {@link #minimumVersionCode()} is the override
   */
  public boolean minimumVersionCodeOverridden() {
    return emergencyOverride.minimumVersionCode() != null;
  }

  /**
   * Tells whether the newest published build comes from the emergency override.
   *
   * @return {@code true} when {@link #latestVersionCode()} is the override
   */
  public boolean latestVersionCodeOverridden() {
    return emergencyOverride.latestVersionCode() != null;
  }

  /**
   * Tells whether the release page comes from the emergency override.
   *
   * @return {@code true} when {@link #releasesUrl()} is the override
   */
  public boolean releasesUrlOverridden() {
    String override = emergencyOverride.releasesUrl();
    return override != null && !override.isBlank();
  }
}
