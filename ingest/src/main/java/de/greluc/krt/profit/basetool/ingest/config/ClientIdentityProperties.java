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

import java.util.List;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the client-identity gate ({@code app.ingest.client-identity}, REQ-INGEST-011),
 * which decides which client software may call the gateway. Every check is disabled while its value
 * is empty or blank; values come from {@code APP_INGEST_CLIENT_IDENTITY_*}.
 *
 * @param allowedClientIds Keycloak client ids ({@code azp}) allowed to call the ingest endpoints;
 *     empty disables the check. When set, a token with an absent or unknown {@code azp} is rejected
 * @param requiredScope OAuth scope the token must carry (as {@code SCOPE_<value>}); blank disables
 *     the check. Must be a scope present only on extractor tokens, e.g. {@code
 *     extractor-ingest-only}
 * @param allowedTools accepted producer identifiers ({@code tool} field); empty disables the check.
 *     Client-supplied, so provenance only, not authentication
 * @param auditOnly when {@code true}, configured checks log and count their verdict but never
 *     reject
 */
@Validated
@ConfigurationProperties(prefix = "app.ingest.client-identity")
public record ClientIdentityProperties(
    @DefaultValue({}) List<String> allowedClientIds,
    @DefaultValue("") String requiredScope,
    @DefaultValue({}) List<String> allowedTools,
    @DefaultValue("false") boolean auditOnly) {

  /**
   * Freezes both lists and normalises absent values, so the gate reads an immutable snapshot and
   * never has to null-check: a {@code null} list becomes empty and a {@code null} scope blank —
   * both of which mean "check disabled".
   */
  public ClientIdentityProperties {
    allowedClientIds = allowedClientIds == null ? List.of() : List.copyOf(allowedClientIds);
    requiredScope = requiredScope == null ? "" : requiredScope;
    allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
  }

  /**
   * The configured client-id allowlist.
   *
   * @return an immutable copy; empty when the {@code azp} check is disabled
   */
  @Override
  public @Unmodifiable List<String> allowedClientIds() {
    return allowedClientIds;
  }

  /**
   * The configured provenance allowlist.
   *
   * @return an immutable copy; empty when the {@code tool} check is disabled
   */
  @Override
  public @Unmodifiable List<String> allowedTools() {
    return allowedTools;
  }
}
