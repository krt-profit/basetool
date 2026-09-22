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

package de.greluc.krt.profit.basetool.ingest.metrics;

import de.greluc.krt.profit.basetool.ingest.config.ClientIdentityProperties;
import de.greluc.krt.profit.basetool.ingest.config.SecurityConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code basetool_ingest_gate_enforcing{gate}}, the configured posture of the four
 * client-discriminating gates of the ingest gateway (REQ-INGEST-011): {@code 1} while a gate
 * actually refuses callers, {@code 0} while it is unconfigured or only counting.
 *
 * <p><b>Why it exists.</b> Every one of these gates is inert by default and switched on by the
 * environment alone, so nothing in the code, the image or the deploy log says whether production is
 * protected. The one time it was read — 2026-08-28 — the audience check carried the backend's value
 * and the allowlist ran in audit-only, i.e. <em>neither</em> refused anything, and that was only
 * found by reading the host's environment by hand. This gauge makes the posture a scraped fact, so
 * {@code IngestAudienceGateOff} can say so without anybody logging in.
 *
 * <p>The {@code gate} label is one of four fixed literals, never a configured value (REQ-OBS-011):
 *
 * <ul>
 *   <li>{@code azp} — the client-id allowlist is non-empty and {@code audit-only} is off;
 *   <li>{@code scope} — a required scope is set and {@code audit-only} is off;
 *   <li>{@code tool} — the provenance allowlist is non-empty and {@code audit-only} is off;
 *   <li>{@code audience} — {@code app.security.jwt.expected-audiences} holds at least one value.
 *       {@code audit-only} does not apply to it: the audience lives in the {@code JwtDecoder} and
 *       refuses from the moment it is set.
 * </ul>
 *
 * <p>Modelled on {@link TracingEnabledMetric}: registered once at startup with constant values,
 * because every input is bound at startup and cannot change without a restart. The same facts —
 * booleans and list sizes, never the configured client ids, scopes, tools or audiences — are logged
 * once, here, and repeated in the startup banner.
 */
@Slf4j
@Component
public class IngestGatePostureMetric {

  /** {@code gate} label value: the {@code azp} client-id allowlist. */
  public static final String GATE_AZP = "azp";

  /** {@code gate} label value: the required OAuth scope. */
  public static final String GATE_SCOPE = "scope";

  /** {@code gate} label value: the payload {@code tool} provenance allowlist. */
  public static final String GATE_TOOL = "tool";

  /** {@code gate} label value: the JWT audience check in the resource server's decoder. */
  public static final String GATE_AUDIENCE = "audience";

  /** Registry the four gauges are published to. */
  private final MeterRegistry registry;

  /** The derived posture, computed once from the bound configuration. */
  private final Posture posture;

  /**
   * Derives the posture from the bound configuration.
   *
   * @param registry the registry the gauges are published to
   * @param clientIdentityProperties the {@code azp} / scope / {@code tool} gate configuration
   * @param expectedAudiences the raw {@code app.security.jwt.expected-audiences}; blank entries are
   *     ignored exactly as the decoder ignores them
   */
  public IngestGatePostureMetric(
      @NotNull MeterRegistry registry,
      @NotNull ClientIdentityProperties clientIdentityProperties,
      @Value("${app.security.jwt.expected-audiences:}") List<String> expectedAudiences) {
    this.registry = registry;
    this.posture =
        Posture.of(clientIdentityProperties, SecurityConfig.effectiveAudiences(expectedAudiences));
  }

  /**
   * Registers one gauge per gate and logs the posture once. At {@code WARN} while the audience
   * check is off, because that is the one gate an operator must turn on by hand and the one whose
   * absence the alert reports; at {@code INFO} otherwise.
   */
  @PostConstruct
  public void register() {
    gauge(GATE_AZP, posture.azpEnforcing());
    gauge(GATE_SCOPE, posture.scopeEnforcing());
    gauge(GATE_TOOL, posture.toolEnforcing());
    gauge(GATE_AUDIENCE, posture.audienceEnforcing());
    if (posture.audienceEnforcing()) {
      log.info("Ingest gate posture: {}", posture.describe());
    } else {
      log.warn(
          "Ingest gate posture: {} — the audience check is OFF, so any valid realm token passes"
              + " the decoder (set IRI_INGEST_EXPECTED_AUDIENCES=basetool-ingest once the realm"
              + " stamps it)",
          posture.describe());
    }
  }

  /**
   * The posture this gauge reports, for the startup banner.
   *
   * @return the derived posture, never {@code null}
   */
  public @NotNull Posture posture() {
    return posture;
  }

  /**
   * Registers one constant {@code 0}/{@code 1} gauge.
   *
   * @param gate the bounded {@code gate} label value
   * @param enforcing whether that gate refuses callers
   */
  private void gauge(@NotNull String gate, boolean enforcing) {
    double value = enforcing ? 1.0d : 0.0d;
    Gauge.builder(MetricNames.INGEST_GATE_ENFORCING, () -> value)
        .description("1 while this ingest client gate refuses callers, 0 while it does not.")
        .tag(MetricNames.TAG_GATE, gate)
        .register(registry);
  }

  /**
   * The configured posture of the ingest client gates: whether each refuses, plus the sizes of the
   * configured lists. Holds no configured value, only counts and booleans, so it is safe to log.
   *
   * @param azpEnforcing the client-id allowlist refuses unknown clients
   * @param scopeEnforcing the required-scope check refuses tokens without it
   * @param toolEnforcing the provenance allowlist refuses unknown producers
   * @param audienceEnforcing the decoder refuses tokens without a configured audience
   * @param auditOnly the three client-identity checks only count and never refuse
   * @param allowedClientIdCount number of configured client ids
   * @param allowedToolCount number of configured producers
   * @param audienceCount number of configured audiences
   */
  public record Posture(
      boolean azpEnforcing,
      boolean scopeEnforcing,
      boolean toolEnforcing,
      boolean audienceEnforcing,
      boolean auditOnly,
      int allowedClientIdCount,
      int allowedToolCount,
      int audienceCount) {

    /**
     * Derives the posture from the gate configuration and the effective audience list.
     *
     * @param properties the client-identity configuration
     * @param audiences the non-blank configured audiences
     * @return the posture
     */
    static @NotNull Posture of(
        @NotNull ClientIdentityProperties properties, @NotNull List<String> audiences) {
      boolean enforcing = !properties.auditOnly();
      return new Posture(
          enforcing && !properties.allowedClientIds().isEmpty(),
          enforcing && !properties.requiredScope().isBlank(),
          enforcing && !properties.allowedTools().isEmpty(),
          !audiences.isEmpty(),
          properties.auditOnly(),
          properties.allowedClientIds().size(),
          properties.allowedTools().size(),
          audiences.size());
    }

    /**
     * Renders the posture as one log-safe line: booleans and counts only.
     *
     * @return e.g. {@code azp=on(1) scope=off tool=on(2) audience=off(0) auditOnly=false}
     */
    public @NotNull String describe() {
      return "azp="
          + (azpEnforcing ? "on" : "off")
          + "("
          + allowedClientIdCount
          + ") scope="
          + (scopeEnforcing ? "on" : "off")
          + " tool="
          + (toolEnforcing ? "on" : "off")
          + "("
          + allowedToolCount
          + ") audience="
          + (audienceEnforcing ? "on" : "off")
          + "("
          + audienceCount
          + ") auditOnly="
          + auditOnly;
    }
  }
}
