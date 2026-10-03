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

package de.greluc.krt.profit.basetool.ingest.gate;

import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What the exchange gate established about an admitted request, for the relay that follows.
 *
 * @param clientId the registry client's Keycloak client id
 * @param member the acting member's subject
 * @param keyThumbprint the token's DPoP key thumbprint
 * @param capabilities the capabilities both the token and the registry hold
 * @param client the registry entry
 * @param connectedAt the connection time the gate compares with a client revocation, in epoch
 *     seconds — an offline token's {@code iat}, any other token's {@code auth_time} — or {@code
 *     null} when the token lacks that claim
 */
public record ExchangeRequestContext(
    @NotNull String clientId,
    @NotNull String member,
    @NotNull String keyThumbprint,
    @NotNull @Unmodifiable Set<String> capabilities,
    @NotNull ExchangeRegistry.Client client,
    @Nullable Long connectedAt) {

  /** The request attribute the context is stored under. */
  public static final String ATTRIBUTE = ExchangeRequestContext.class.getName();

  /** Copies the capabilities. */
  public ExchangeRequestContext {
    capabilities = Set.copyOf(capabilities);
  }

  /**
   * Returns the context of an admitted request.
   *
   * @param request the request
   * @return the context, or {@code null} when the gate did not admit it
   */
  public static @Nullable ExchangeRequestContext of(@NotNull HttpServletRequest request) {
    return request.getAttribute(ATTRIBUTE) instanceof ExchangeRequestContext context
        ? context
        : null;
  }
}
