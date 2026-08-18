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

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The client ids {@code basetool_api_client_requests_total} may carry verbatim as its {@code
 * client_id} label (A8, REQ-OBS-018).
 *
 * <p>The list exists to <em>bound the label</em>, not to authorise anyone: nothing here grants or
 * refuses anything, and a client absent from it is counted all the same — under the literal {@code
 * other}, which is precisely the series {@code ApiUnknownClient} watches. Authorisation of client
 * software is a separate concern and lives on the token's audience and roles.
 *
 * <p>Defaulted rather than empty, unlike {@link IngestGatewayProperties}: an empty allowlist there
 * fails closed and refuses, an empty list here would only collapse every caller into {@code other}
 * and make the metric useless while looking healthy. The default names the two first-party clients
 * that exist in the realm; a deployment that renames them overrides the key rather than losing the
 * attribution silently.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.monitoring.api-clients")
public class ApiClientMetricsProperties {

  /** Client ids whose {@code azp} is safe to use as a metric label verbatim. */
  private List<String> knownClientIds = List.of("basetool-frontend", "basetool-android");

  /**
   * Whether {@code azp} names a client this deployment knows by name.
   *
   * <p>Deliberately does not consult {@link IngestGatewayProperties}: the caller merges the two, so
   * this class keeps one job and the gateway list keeps its single, security-relevant meaning.
   *
   * @param azp the authorized-party claim from the caller's token, may be {@code null}
   * @return {@code true} when the claim may be used as a label value as-is
   */
  public boolean isKnownClient(String azp) {
    return azp != null && !azp.isBlank() && knownClientIds.contains(azp);
  }
}
