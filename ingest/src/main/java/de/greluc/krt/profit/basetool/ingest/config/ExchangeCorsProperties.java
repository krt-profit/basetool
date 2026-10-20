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

package de.greluc.krt.profit.basetool.ingest.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The browser-extension origins the gateway admits cross-origin on {@code /exchange/**}
 * (REQ-XCH-027, ADR-0303).
 *
 * @param extensionOrigins the fixed {@code chrome-extension://<id>} origin of each approved
 *     Chromium extension build (Chrome and Edge); blank entries are ignored
 * @param firefoxExtensions whether {@value #FIREFOX_EXTENSION_ORIGINS} is admitted, since a Firefox
 *     extension's origin is random per installation
 */
@Validated
@ConfigurationProperties(prefix = "app.exchange.cors")
public record ExchangeCorsProperties(
    @NotNull @DefaultValue({})
        List<@NotNull @Pattern(regexp = "\\s*|" + CHROMIUM_EXTENSION_ORIGIN) String>
            extensionOrigins,
    @DefaultValue("true") boolean firefoxExtensions) {

  /**
   * A Chromium extension origin: the scheme and a 32-character extension id of the letters {@code
   * a} to {@code p}.
   */
  public static final String CHROMIUM_EXTENSION_ORIGIN = "chrome-extension://[a-p]{32}";

  /** The origin pattern every Firefox extension installation matches. */
  public static final String FIREFOX_EXTENSION_ORIGINS = "moz-extension://*";

  /**
   * The origin patterns the exchange CORS configuration admits.
   *
   * @return each configured Chromium origin without the blank entries, then the Firefox pattern
   *     when enabled
   */
  public @Unmodifiable List<String> allowedOriginPatterns() {
    List<String> patterns = new ArrayList<>();
    for (String origin : extensionOrigins) {
      if (!origin.isBlank()) {
        patterns.add(origin);
      }
    }
    if (firefoxExtensions) {
      patterns.add(FIREFOX_EXTENSION_ORIGINS);
    }
    return List.copyOf(patterns);
  }
}
