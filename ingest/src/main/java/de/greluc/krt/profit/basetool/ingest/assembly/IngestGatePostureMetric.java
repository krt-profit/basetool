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

package de.greluc.krt.profit.basetool.ingest.assembly;

import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code basetool_ingest_gate_enforcing{gate="audience"}}: {@code 1} while the decoder
 * refuses tokens without a configured audience, {@code 0} while no audience is configured
 * (REQ-INGEST-011).
 *
 * <p>The value is fixed at startup; the posture is also logged once, without configured values.
 */
@Slf4j
@Component
public class IngestGatePostureMetric {

  /** {@code gate} label value: the JWT audience check in the resource server's decoder. */
  public static final String GATE_AUDIENCE = "audience";

  /** Registry the gauge is published to. */
  private final MeterRegistry registry;

  /** The derived posture, computed once from the bound configuration. */
  private final Posture posture;

  /**
   * Derives the posture from the bound configuration.
   *
   * @param registry the registry the gauge is published to
   * @param expectedAudiences the raw {@code app.security.jwt.expected-audiences}; blank entries are
   *     ignored
   */
  public IngestGatePostureMetric(
      @NotNull MeterRegistry registry,
      @Value("${app.security.jwt.expected-audiences:}") List<String> expectedAudiences) {
    this.registry = registry;
    this.posture = Posture.of(SecurityConfig.effectiveAudiences(expectedAudiences));
  }

  /**
   * Registers the gauge and logs the posture once, at WARN while the audience check is off and at
   * INFO otherwise.
   */
  @PostConstruct
  public void register() {
    double value = posture.audienceEnforcing() ? 1.0d : 0.0d;
    Gauge.builder(MetricNames.INGEST_GATE_ENFORCING, () -> value)
        .description("1 while the ingest audience gate refuses tokens, 0 while it does not.")
        .tag(MetricNames.TAG_GATE, GATE_AUDIENCE)
        .register(registry);
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
   * The configured posture of the audience gate, holding only a boolean and a count so it is safe
   * to log.
   *
   * @param audienceEnforcing the decoder refuses tokens without a configured audience
   * @param audienceCount number of configured audiences
   */
  public record Posture(boolean audienceEnforcing, int audienceCount) {

    /**
     * Derives the posture from the effective audience list.
     *
     * @param audiences the non-blank configured audiences
     * @return the posture
     */
    static @NotNull Posture of(@NotNull List<String> audiences) {
      return new Posture(!audiences.isEmpty(), audiences.size());
    }

    /**
     * Renders the posture as one log-safe line: a boolean and a count only.
     *
     * @return e.g. {@code audience=on(1)}
     */
    public @NotNull String describe() {
      return "audience=" + (audienceEnforcing ? "on" : "off") + "(" + audienceCount + ")";
    }
  }
}
