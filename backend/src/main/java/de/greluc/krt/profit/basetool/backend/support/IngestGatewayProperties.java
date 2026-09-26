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
 * The Keycloak clients, matched on the token's {@code azp}, that may act for another member via
 * {@link ActingMemberHeader#ON_BEHALF_OF_HEADER} (ADR-0129).
 *
 * <p>Empty by default, and empty admits no client.
 *
 * @param clientIds the {@code azp} values allowed to act for another member; empty disables the
 *     mechanism
 */
@Validated
@ConfigurationProperties(prefix = "app.security.ingest-gateway")
public record IngestGatewayProperties(@DefaultValue List<String> clientIds) {

  /**
   * Keycloak's name prefix for the user backing a client's service account.
   *
   * <p>Any user can carry this name, so it must never drive a security decision; it is used only to
   * exclude service accounts from a monitoring gauge.
   */
  public static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

  /**
   * Checks whether {@code azp} names a configured ingest gateway; a blank or absent value never
   * does.
   *
   * @param azp the authorized-party claim from the caller's token, may be {@code null}
   * @return {@code true} when this caller is a configured gateway
   */
  public boolean isGatewayClient(String azp) {
    return azp != null && !azp.isBlank() && clientIds.contains(azp);
  }
}
