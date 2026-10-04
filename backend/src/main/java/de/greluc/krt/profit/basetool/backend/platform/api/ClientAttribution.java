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

package de.greluc.krt.profit.basetool.backend.platform.api;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.internal.ApiClientMetricsProperties;
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
  private final ClientDirectory clientDirectory;

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
   * Returns the bounded client label of a request that may relay an exchange client: when the
   * caller is a configured gateway and names one, the label is that client if the registry holds
   * it, else {@link MetricNames#CLIENT_ID_OTHER}; otherwise it is {@link #labelOf(Authentication)}.
   *
   * @param authentication the caller's authentication, may be {@code null}
   * @param relayedClient the {@code X-Exchange-Client} header, may be {@code null}
   * @return the bounded label
   */
  public @NotNull String relayedLabelOf(
      @Nullable Authentication authentication, @Nullable String relayedClient) {
    String authorizedParty = AuthenticatedSubject.authorizedParty(authentication).orElse(null);
    if (clientDirectory.isGatewayClient(authorizedParty)
        && relayedClient != null
        && !relayedClient.isBlank()) {
      return clientDirectory.isRegisteredClient(relayedClient)
          ? relayedClient
          : MetricNames.CLIENT_ID_OTHER;
    }
    return label(authorizedParty);
  }

  /**
   * Maps a client id onto a bounded value. Configured ingest gateways and exchange registry clients
   * ({@link ClientDirectory}) count as known.
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
        || clientDirectory.isGatewayClient(authorizedParty)
        || clientDirectory.isRegisteredClient(authorizedParty)) {
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
