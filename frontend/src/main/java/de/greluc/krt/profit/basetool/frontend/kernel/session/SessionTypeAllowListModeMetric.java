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

package de.greluc.krt.profit.basetool.frontend.kernel.session;

import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Publishes the session type allow-list mode this process runs with as {@code
 * basetool_session_type_allow_list_mode{mode}}, so a deploy's effective mode can be read from
 * Prometheus and not only from the startup log line (REQ-SEC-067, ADR-0206).
 *
 * <p>The mode is resolved from the same {@code app.session.type-allow-list} property and parser as
 * {@link RedisSessionConfig}, once at startup.
 */
@Component
@RequiredArgsConstructor
public class SessionTypeAllowListModeMetric implements MeterBinder {

  /** The property selecting the mode, as {@link RedisSessionConfig} reads it. */
  static final String PROPERTY = "app.session.type-allow-list";

  private final Environment environment;

  /**
   * Registers one gauge per {@link SessionTypeAllowList.Mode}, {@code 1.0} for the effective mode
   * and {@code 0.0} for the others.
   *
   * @param registry the registry the gauges bind to
   */
  @Override
  public void bindTo(@NotNull MeterRegistry registry) {
    SessionTypeAllowList.Mode effective =
        SessionTypeAllowList.Mode.parse(environment.getProperty(PROPERTY));
    for (SessionTypeAllowList.Mode mode : SessionTypeAllowList.Mode.values()) {
      double value = mode == effective ? 1.0 : 0.0;
      Gauge.builder(MetricNames.SESSION_TYPE_ALLOW_LIST_MODE, () -> value)
          .tag(MetricNames.TAG_MODE, mode.name().toLowerCase(Locale.ROOT))
          .description("1 on the series naming the session type allow-list mode in effect.")
          .register(registry);
    }
  }
}
