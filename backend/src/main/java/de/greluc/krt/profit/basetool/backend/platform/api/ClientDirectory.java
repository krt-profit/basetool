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

import java.util.Collection;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What the platform and the lower modules may ask of the exchange's client registry: the ingest
 * gateways, the registered exchange clients and their display names (plan §5.3), implemented by the
 * exchange module.
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

  /**
   * Checks whether a Keycloak username is the service account of a configured ingest gateway.
   *
   * @param username the username Keycloak reports for an account, may be {@code null}
   * @return {@code true} when the name is a configured gateway client's service-account name
   */
  boolean isGatewayServiceAccount(@Nullable String username);

  /**
   * Returns the display names of the registered exchange clients among the given client ids.
   *
   * @param clientIds the client ids to look up
   * @return display name by client id; an id that is not registered is absent
   */
  @NotNull
  Map<String, String> displayNames(@NotNull Collection<String> clientIds);
}
