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
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.ingest.client-identity")
public class ClientIdentityProperties {

  /**
   * Keycloak client ids ({@code azp} claim) allowed to call the ingest endpoints — normally just
   * the desktop extractor's {@code basetool-sc-extractor}. Empty (the default) disables the check.
   *
   * <p>When non-empty the check is <b>fail-closed in both directions</b>: a token whose {@code azp}
   * is absent is rejected just like one whose {@code azp} is unknown. A missing claim must never be
   * the lenient branch — that would turn "Keycloak stopped stamping azp" from a loud failure into a
   * silently disabled gate.
   *
   * <p>Holding several ids at once is intentional: it makes a client-id rotation possible without
   * downtime (ship the new extractor with the new id, run both, drop the old id once telemetry
   * shows no traffic on it) and it is where an approved third-party integration would be listed.
   */
  private List<String> allowedClientIds = List.of();

  /**
   * OAuth scope an ingest caller's token must carry, mapped by Spring Security to the {@code
   * SCOPE_<value>} authority. Blank (the default) disables the check.
   *
   * <p>Complements {@link #allowedClientIds} rather than duplicating it: the client id answers "who
   * is calling", the scope answers "what was this token issued to do". The scope survives a client
   * rename and it is what an approved integration would be granted, so both are checked.
   *
   * <p><b>Operator trap — verified against the realm, not hypothetical.</b> Do <em>not</em> set
   * this to {@code extractor-ingest}. Per {@code docs/keycloak/realm-config.reference.json} that
   * scope is broken for this purpose in two independent ways: it is a <b>default scope on the
   * frontend client as well</b>, so it cannot discriminate, and it carries {@code
   * include.in.token.scope: "false"}, so its name never reaches the {@code scope} claim at all —
   * the derived {@code SCOPE_} authority would be absent and this check would reject <em>every</em>
   * caller, the real extractor included. It must name a scope only the extractor carries
   * <em>and</em> that is emitted into the claim; the setup runbook provisions {@code
   * extractor-ingest-only} for exactly this reason (step 7a).
   */
  private String requiredScope = "";

  /**
   * Producer identifiers ({@code tool} field of the extract / blueprint export) accepted as
   * provenance — normally just {@code basetool-sc-extractor}, which the extractor emits as a
   * compile-time constant. Empty (the default) disables the check.
   *
   * <p>This is the weakest of the gates and is not load-bearing: the field is client-supplied and
   * therefore trivially forgeable by anyone who reads the published contract. It is here because it
   * costs nothing, because it fails a casual hand-rolled payload that never thought to set it, and
   * because — via {@code basetool_ingest_client_total} — it makes a producer that starts drifting
   * from the registered client visible. Treat it as telemetry with a reject attached, never as
   * authentication.
   */
  private List<String> allowedTools = List.of();

  /**
   * When {@code true}, every configured check above logs and counts its verdict but <b>never
   * rejects</b> the request. The gate runs in full, the caller is unaffected.
   *
   * <p>This is the safe way to turn the gates on in production. Enabling a fail-closed check
   * against a live token population is a leap: the operator cannot know in advance whether some
   * member is still running an extractor build that predates the {@code tool} constant, or whether
   * the Keycloak scope assignment actually landed on every token. Audit-only answers that
   * empirically — configure the values, watch {@code basetool_ingest_client_rejected_total} for a
   * scrape interval, and flip this off once it stays at zero.
   */
  private boolean auditOnly = false;

  /**
   * When {@code true}, an ingest caller must present a DPoP-bound token (RFC 9449) — a plain {@code
   * Authorization: Bearer} request is rejected. Default {@code false}, which accepts both schemes.
   *
   * <p>The default must stay {@code false} until the desktop extractor ships DPoP support: it sends
   * {@code Authorization: Bearer} today, so enabling this would break every send on deploy. Spring
   * Security validates a DPoP-bound token whenever one is presented regardless of this flag, so the
   * dual-mode window costs nothing — the flag only decides whether a plain bearer is still
   * <em>allowed</em>, i.e. it is the switch that closes the downgrade path once the client
   * population has migrated.
   */
  private boolean dpopRequired = false;
}
