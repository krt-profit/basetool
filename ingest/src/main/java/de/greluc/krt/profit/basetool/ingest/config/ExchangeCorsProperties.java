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

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The browser-extension origins the gateway admits on {@code /exchange/**}, each bound to the
 * registry client it belongs to (REQ-XCH-027, ADR-0303).
 *
 * @param extensionOrigins one {@code <clientId>=chrome-extension://<id>} entry per approved
 *     Chromium build (Chrome and Edge); blank entries are ignored
 * @param firefoxClients the client ids with an approved Firefox build, for which {@value
 *     #FIREFOX_EXTENSION_ORIGINS} is admitted; blank entries are ignored
 */
@Validated
@ConfigurationProperties(prefix = "app.exchange.cors")
public record ExchangeCorsProperties(
    @NotNull @DefaultValue({})
        List<
                @NotNull @Pattern(regexp = "\\s*|" + CLIENT_ID + "=" + CHROMIUM_EXTENSION_ORIGIN)
                String>
            extensionOrigins,
    @NotNull @DefaultValue({})
        List<@NotNull @Pattern(regexp = "\\s*|" + CLIENT_ID) String> firefoxClients) {

  /** A registry client id. */
  public static final String CLIENT_ID = "[a-z0-9][a-z0-9-]{1,62}";

  /**
   * A Chromium extension origin: the scheme and a 32-character extension id of the letters {@code
   * a} to {@code p}.
   */
  public static final String CHROMIUM_EXTENSION_ORIGIN = "chrome-extension://[a-p]{32}";

  /** The origin pattern every Firefox extension installation matches. */
  public static final String FIREFOX_EXTENSION_ORIGINS = "moz-extension://*";

  /** The scheme of every Firefox extension origin. */
  static final String FIREFOX_SCHEME = "moz-extension://";

  /**
   * The client each configured Chromium origin belongs to.
   *
   * @return the origins in configuration order, each with its client id
   */
  public @Unmodifiable Map<String, String> chromiumOrigins() {
    Map<String, String> origins = new LinkedHashMap<>();
    for (String entry : extensionOrigins) {
      if (!entry.isBlank()) {
        int separator = entry.indexOf('=');
        origins.putIfAbsent(entry.substring(separator + 1), entry.substring(0, separator));
      }
    }
    return Map.copyOf(origins);
  }

  /**
   * The origin patterns the exchange CORS configuration admits.
   *
   * @return each configured Chromium origin, then the Firefox pattern when a client has a Firefox
   *     build
   */
  public @Unmodifiable List<String> allowedOriginPatterns() {
    List<String> patterns = new ArrayList<>();
    for (String entry : extensionOrigins) {
      if (!entry.isBlank()) {
        patterns.add(entry.substring(entry.indexOf('=') + 1));
      }
    }
    if (!firefox().isEmpty()) {
      patterns.add(FIREFOX_EXTENSION_ORIGINS);
    }
    return List.copyOf(patterns);
  }

  /**
   * Whether a request's {@code Origin} may carry a token of this client.
   *
   * @param clientId the token's {@code azp}
   * @param origin the request's {@code Origin}, or {@code null} when it sends none
   * @return {@code true} without an origin, for a Chromium origin bound to this client, and for a
   *     Firefox extension origin when this client has a Firefox build; {@code false} otherwise
   */
  public boolean admits(@Nullable String clientId, @Nullable String origin) {
    if (origin == null || origin.isEmpty()) {
      return true;
    }
    if (clientId == null) {
      return false;
    }
    if (origin.startsWith(FIREFOX_SCHEME)) {
      return firefox().contains(clientId);
    }
    return clientId.equals(chromiumOrigins().get(origin));
  }

  /**
   * Whether every Chromium origin is bound to one client only.
   *
   * @return {@code true} when no origin appears twice
   */
  @AssertTrue(message = "a chrome-extension origin may be bound to one client only")
  public boolean isEachOriginBoundOnce() {
    Set<String> seen = new HashSet<>();
    for (String entry : extensionOrigins) {
      if (entry != null && !entry.isBlank() && !seen.add(entry.substring(entry.indexOf('=') + 1))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The client ids with a Firefox build.
   *
   * @return the configured ids without the blank entries
   */
  private @Unmodifiable Set<String> firefox() {
    Set<String> clients = new HashSet<>();
    for (String client : firefoxClients) {
      if (!client.isBlank()) {
        clients.add(client);
      }
    }
    return Set.copyOf(clients);
  }
}
