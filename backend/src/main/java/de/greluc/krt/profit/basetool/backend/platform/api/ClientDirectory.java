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

package de.greluc.krt.profit.basetool.backend.platform.api;

import org.jetbrains.annotations.Nullable;

/**
 * The client ids that {@link ClientAttribution} keeps verbatim beyond its own configured clients:
 * the ingest gateways and the registered exchange clients (plan §5.3), implemented by the exchange
 * module.
 */
public interface ClientDirectory {

  /**
   * Checks whether the client id names a configured ingest gateway; a blank or absent value never
   * does.
   *
   * @param clientId the token's {@code azp}, may be {@code null}
   * @return {@code true} when the client is a configured gateway
   */
  boolean isGatewayClient(@Nullable String clientId);

  /**
   * Checks whether the client id is registered in the exchange registry; a blank or absent value
   * never is.
   *
   * @param clientId the client id, may be {@code null}
   * @return {@code true} when the registry holds the client
   */
  boolean isRegisteredClient(@Nullable String clientId);
}
