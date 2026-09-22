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
 * Type-safe configuration for the client-identity gate (prefix {@code app.ingest.client-identity},
 * REQ-INGEST-011). This is the control that decides <em>which client software</em> may drive the
 * gateway, as opposed to <em>which user</em> — the latter stays {@code isAuthenticated()} for every
 * member (REQ-INGEST-002/-008, unchanged).
 *
 * <p><b>Every check is inert until configured.</b> An empty list or a blank string disables that
 * check entirely, exactly like the {@code app.security.jwt.expected-audiences} knob this module
 * already carries. That is deliberate and load-bearing: the Keycloak-side mappers, scopes and
 * audiences are an <em>operator</em> step that cannot be done by a PR ({@code
 * docs/INGEST_KEYCLOAK_SETUP.md}), so a build that shipped these gates pre-enabled would reject
 * every real extractor token the moment it deployed. Configure, verify against a live token, then
 * enable — the same sequencing discipline REQ-INGEST-008 imposes on the audience validator.
 *
 * <p>No value carries a default. The allowlist contents are operational configuration, not source:
 * the code shows <em>that</em> a gate exists, the environment decides <em>who</em> passes it.
 * Values come from the environment ({@code APP_INGEST_CLIENT_IDENTITY_*}) so a client can be added
 * or revoked without a rebuild — which also makes the allowlist the fast kill switch for a client
 * whose access must end now rather than at the next release.
 *
 * @param allowedClientIds Keycloak client ids ({@code azp} claim) allowed to call the ingest
 *     endpoints — normally just the desktop extractor's {@code basetool-sc-extractor}. Empty (the
 *     default) disables the check. When non-empty the check is <b>fail-closed in both
 *     directions</b>: a token whose {@code azp} is absent is rejected just like one whose {@code
 *     azp} is unknown. Holding several ids at once makes a client-id rotation possible without
 *     downtime
 * @param requiredScope OAuth scope an ingest caller's token must carry, mapped by Spring Security
 *     to the {@code SCOPE_<value>} authority; blank (the default) disables the check. <b>Operator
 *     trap:</b> do <em>not</em> set this to {@code extractor-ingest} — per {@code
 *     docs/keycloak/realm-config.reference.json} that scope is a default scope on the frontend
 *     client as well and carries {@code include.in.token.scope: "false"}, so it can neither
 *     discriminate nor reach the {@code scope} claim; the setup runbook provisions {@code
 *     extractor-ingest-only} for exactly this reason (step 7a)
 * @param allowedTools producer identifiers ({@code tool} field of the extract / blueprint export)
 *     accepted as provenance. Empty (the default) disables the check. The weakest of the gates and
 *     not load-bearing: the field is client-supplied and therefore trivially forgeable — telemetry
 *     with a reject attached, never authentication
 * @param auditOnly when {@code true}, every configured check above logs and counts its verdict but
 *     <b>never rejects</b> the request. The safe way to turn the gates on in production: configure
 *     the values, watch {@code basetool_ingest_client_rejected_total} for a scrape interval, and
 *     flip this off once it stays at zero
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
