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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Maps the caller's client ({@code azp}) onto a bounded label shared by the {@code client_id}
 * request metric (REQ-OBS-018) and the audit rows' client column (REQ-AUDIT-005).
 *
 * <p>Known clients are kept verbatim, unknown ones become {@link MetricNames#CLIENT_ID_OTHER}, and
 * a caller without a token or without an {@code azp} becomes {@code none}.
 */
@Component
@RequiredArgsConstructor
public class ClientAttribution {

  private final ApiClientMetricsProperties clientProperties;
  private final IngestGatewayProperties gatewayProperties;

  /**
   * Returns the bounded client label for the given authentication.
   *
   * @param authentication the current authentication, may be {@code null}
   * @return a known client id verbatim, else {@code other} / {@code none}
   */
  public @NotNull String labelOf(@Nullable Authentication authentication) {
    return label(AuthenticatedSubject.authorizedParty(authentication).orElse(null));
  }

  /**
   * Maps an {@code azp} claim onto a bounded value. Configured ingest gateways ({@link
   * IngestGatewayProperties}) count as known.
   *
   * @param authorizedParty the token's {@code azp}, may be {@code null} or blank
   * @return the claim itself for a known client, else {@link MetricNames#CLIENT_ID_NONE} for an
   *     absent claim or {@link MetricNames#CLIENT_ID_OTHER}
   */
  public @NotNull String label(@Nullable String authorizedParty) {
    if (authorizedParty == null || authorizedParty.isBlank()) {
      return MetricNames.CLIENT_ID_NONE;
    }
    if (clientProperties.isKnownClient(authorizedParty)
        || gatewayProperties.isGatewayClient(authorizedParty)) {
      return authorizedParty;
    }
    return MetricNames.CLIENT_ID_OTHER;
  }

  /**
   * Normalises an audit-viewer client filter value, treating blank as "no filter".
   *
   * @param clientId the raw filter value, or {@code null}
   * @return the value, or {@code null} when it is absent or blank
   */
  public @Nullable String filterValue(@Nullable String clientId) {
    return clientId == null || clientId.isBlank() ? null : clientId;
  }
}
