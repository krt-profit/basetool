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

package de.greluc.krt.profit.basetool.ingest.observability;

import de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;

/**
 * The MDC fields that tag every log line of an exchange request with its registry client and route
 * template (REQ-XCH-028, REQ-OBS-001).
 *
 * <p>Both values are bounded: the client is the registry-bounded label of {@link
 * ExchangeRefusals#clientLabel(String)}, the route one of {@link ExchangeRoutes}. The exchange gate
 * sets them; {@code CorrelationIdFilter} removes them when the request ends, after its access-log
 * line.
 */
public final class ExchangeLogContext {

  /** MDC key of the registry client label. */
  public static final String CLIENT_KEY = "exchangeClientId";

  /** MDC key of the route template. */
  public static final String ROUTE_KEY = "exchangeRoute";

  /** Not instantiable. */
  private ExchangeLogContext() {}

  /**
   * Tags the current request with its registry client.
   *
   * @param clientLabel the registry client id, {@code none}, {@code unregistered} or {@code
   *     unknown}
   */
  public static void client(@NotNull String clientLabel) {
    MDC.put(CLIENT_KEY, clientLabel);
  }

  /**
   * Tags the current request with its route template.
   *
   * @param route the matched route
   */
  public static void route(@NotNull ExchangeRoutes.Route route) {
    MDC.put(ROUTE_KEY, route.template());
  }

  /** Removes both fields from the current thread's MDC. */
  public static void clear() {
    MDC.remove(CLIENT_KEY);
    MDC.remove(ROUTE_KEY);
  }
}
