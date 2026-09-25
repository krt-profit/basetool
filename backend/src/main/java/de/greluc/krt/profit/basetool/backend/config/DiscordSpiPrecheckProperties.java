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

package de.greluc.krt.profit.basetool.backend.config;

import jakarta.validation.constraints.AssertTrue;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code app.discord.spi-precheck.*} (REQ-SEC-022): the shared
 * secret guarding the internal account-existence endpoint called by the Keycloak Discord SPI.
 *
 * <p>The secret is checked by {@link
 * de.greluc.krt.profit.basetool.backend.controller.DiscordAccountExistenceController}; {@link
 * #toString()} redacts it.
 *
 * @param sharedSecret the secret the SPI presents in {@code X-KRT-SPI-Secret}; blank (the default)
 *     disables the endpoint with {@code 503}
 */
@Validated
@ConfigurationProperties(prefix = "app.discord.spi-precheck")
public record DiscordSpiPrecheckProperties(@DefaultValue("") String sharedSecret) {

  /**
   * Minimum length of a configured (non-blank) shared secret, which is the endpoint's only
   * credential.
   */
  private static final int MIN_SECRET_LENGTH = 32;

  /**
   * Validates at startup that the secret is blank or at least {@link #MIN_SECRET_LENGTH} characters
   * long.
   *
   * @return {@code true} when the secret is blank or long enough
   */
  @AssertTrue(
      message =
          "app.discord.spi-precheck.shared-secret must be blank (to disable the endpoint) or at"
              + " least 32 characters")
  public boolean isSharedSecretBlankOrStrong() {
    return sharedSecret == null
        || sharedSecret.isBlank()
        || sharedSecret.length() >= MIN_SECRET_LENGTH;
  }

  /**
   * Describes the record without its secret, so a logged or printed instance never carries it.
   *
   * @return the record name with the secret shown only as configured or blank
   */
  @Override
  @NotNull
  public String toString() {
    boolean configured = sharedSecret != null && !sharedSecret.isBlank();
    return "DiscordSpiPrecheckProperties[sharedSecret=" + (configured ? "<redacted>" : "") + "]";
  }
}
