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
 * Which Keycloak clients may act for another member via {@link
 * ActingMemberHeader#ON_BEHALF_OF_HEADER} (ADR-0129).
 *
 * <p>Matched on the token's {@code azp}, so only a token minted for one of these clients qualifies
 * — possessing a user's token is not enough, and neither is holding any role.
 *
 * <p><strong>Empty by default, and empty means nobody.</strong> A deployment that has not created
 * the gateway's confidential client refuses every on-behalf-of header rather than trusting one, so
 * the dangerous direction requires a deliberate act of configuration.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.security.ingest-gateway")
public class IngestGatewayProperties {

  /**
   * Keycloak's naming convention for the user row backing a client's service account.
   *
   * <p>A display convention rather than a reserved namespace — an ordinary user can be created with
   * that exact name — so it must never be the basis of a security decision. {@code
   * UserDeletionService} therefore asks Keycloak which user backs a configured client before it
   * waives its delete guard. It is good enough for a <em>gauge</em>, though, which is the one place
   * it is used: excluding a hand-made lookalike from a monitoring count is a nuisance and not a
   * hole, and the alternative is a Keycloak round trip on every metrics tick.
   */
  public static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

  /** The {@code azp} values allowed to act for another member. Empty disables the mechanism. */
  private List<String> clientIds = List.of();

  /**
   * The usernames of the configured gateways' service accounts.
   *
   * @return one {@code service-account-<clientId>} per configured client, empty when none is
   *     configured
   */
  public java.util.Set<String> serviceAccountUsernames() {
    return clientIds.stream()
        .map(clientId -> SERVICE_ACCOUNT_PREFIX + clientId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /**
   * Whether {@code azp} names a configured ingest gateway.
   *
   * <p>The single place the rule lives, so the two decisions that depend on it — "may act for
   * another member" and "is a machine, not a member" — cannot drift apart. A blank or absent {@code
   * azp} is never a gateway, and neither is anything when the list is empty.
   *
   * @param azp the authorized-party claim from the caller's token, may be {@code null}
   * @return {@code true} when this caller is a configured gateway
   */
  public boolean isGatewayClient(String azp) {
    return azp != null && !azp.isBlank() && clientIds.contains(azp);
  }
}
