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
 * Which Keycloak clients mint tokens whose realm-role claim is <strong>deliberately
 * incomplete</strong>, and whose role claim must therefore never be written to {@code app_user}
 * (REQ-SEC-036).
 *
 * <p>Ordinarily a token's {@code realm_access.roles} is the whole truth about a member and {@code
 * UserReconciliationService#syncUser(Jwt)} may replace the stored role set from it. That stops
 * being true for a client provisioned with {@code fullScopeAllowed: false} and a narrowed scope:
 * the mobile client withholds {@code Admin} on purpose (REQ-SEC-035), so its tokens describe a
 * member who is deliberately smaller than the real one. Persisting that description would let the
 * client a member happened to use last decide what the database says they are.
 *
 * <p>Matched on the token's {@code azp} — a claim inside a Keycloak-signed token, not something a
 * client can set — the same handle {@link IngestGatewayProperties} already uses for the far more
 * dangerous on-behalf-of decision, so this adds no new trust.
 *
 * <p><strong>Non-empty by default, and for the opposite reason to the gateway's empty one.</strong>
 * There, empty means "nobody may act for another member" and is the safe end of the range. Here the
 * unsafe end is empty: a deployment that forgot to list the mobile client would silently resume
 * overwriting stored roles from partial tokens, which is the defect this exists to close. The
 * default therefore names the client that is known to be partial, and an override is only needed by
 * a deployment that renames its realm clients.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.security.partial-role-scope")
public class PartialRoleScopeProperties {

  /**
   * The {@code azp} values whose role claim is not authoritative. Defaults are supplied by {@code
   * application.yml}; an empty list disables the guard entirely.
   */
  private List<String> clientIds = List.of();

  /**
   * Whether {@code azp} names a client whose realm-role claim must not be persisted.
   *
   * <p>The single place the rule lives, so the two things that depend on it — "may this token
   * rewrite the stored role set" and "which roles authorise this request" — cannot drift apart. A
   * blank or absent {@code azp} is never a partial-scope client, and neither is anything when the
   * list is empty.
   *
   * @param azp the authorized-party claim from the caller's token, may be {@code null}
   * @return {@code true} when this caller's role claim describes less than the whole member
   */
  public boolean isPartialRoleScopeClient(String azp) {
    return azp != null && !azp.isBlank() && clientIds.contains(azp);
  }
}
