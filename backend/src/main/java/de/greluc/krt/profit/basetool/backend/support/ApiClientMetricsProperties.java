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
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The client ids {@code basetool_api_client_requests_total} may carry verbatim as its {@code
 * client_id} label (REQ-OBS-018).
 *
 * <p>Bounds the label only and authorizes nothing: unlisted clients are counted as {@code other}.
 * Defaults to the realm's first-party clients.
 *
 * @param knownClientIds the client ids whose {@code azp} is safe to use as a metric label verbatim
 */
@Validated
@ConfigurationProperties(prefix = "app.monitoring.api-clients")
public record ApiClientMetricsProperties(
    @DefaultValue({"basetool-frontend", "basetool-android"}) List<String> knownClientIds) {

  /**
   * Whether {@code azp} names a client listed in this configuration. Does not consult {@link
   * IngestGatewayProperties}.
   *
   * @param azp the authorized-party claim from the caller's token, may be {@code null}
   * @return {@code true} when the claim may be used as a label value as-is
   */
  public boolean isKnownClient(String azp) {
    return azp != null && !azp.isBlank() && knownClientIds.contains(azp);
  }
}
