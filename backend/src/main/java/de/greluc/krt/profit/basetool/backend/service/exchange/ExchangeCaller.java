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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Who makes an exchange write: the acting member, the registry client and its installation
 * (REQ-XCH-010).
 *
 * @param member the acting member
 * @param clientId the registry client id
 * @param installationKey the installation's DPoP key thumbprint
 */
public record ExchangeCaller(
    @NotNull UUID member, @NotNull String clientId, @NotNull String installationKey) {

  /**
   * Reads the caller of a request the ingest gateway relayed.
   *
   * @param authentication the relayed authentication
   * @return the caller
   * @throws IllegalStateException when the request was not relayed for a client
   */
  public static @NotNull ExchangeCaller of(@NotNull SubjectAuthentication authentication) {
    String client = authentication.externalClient();
    String key = authentication.exchangeInstallationKey();
    if (client == null || key == null) {
      throw new IllegalStateException("Not an exchange client request");
    }
    return new ExchangeCaller(UUID.fromString(authentication.subject()), client, key);
  }
}
