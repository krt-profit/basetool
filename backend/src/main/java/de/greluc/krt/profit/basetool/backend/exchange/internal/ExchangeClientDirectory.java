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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.api.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The exchange's answer to the platform's {@link ClientDirectory}: the configured ingest gateways
 * and the clients of the exchange registry.
 */
@Component
@RequiredArgsConstructor
public class ExchangeClientDirectory implements ClientDirectory {

  /** The configured ingest gateway clients. */
  private final IngestGatewayProperties gatewayProperties;

  /** The cached client ids of the exchange registry. */
  private final KnownExchangeClients knownExchangeClients;

  @Override
  public boolean isGatewayClient(@Nullable String clientId) {
    return gatewayProperties.isGatewayClient(clientId);
  }

  @Override
  public boolean isRegisteredClient(@Nullable String clientId) {
    return knownExchangeClients.isRegistered(clientId);
  }
}
