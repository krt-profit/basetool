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

import de.greluc.krt.profit.basetool.backend.notification.api.ExchangeRecipientDirectory;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The exchange module's {@link ExchangeRecipientDirectory}, reading live installations. */
@Service
@RequiredArgsConstructor
public class ExchangeInstallationRecipientDirectory implements ExchangeRecipientDirectory {

  private final ExchangeInstallationRepository exchangeInstallationRepository;

  /**
   * The members with a connected installation of one registry client.
   *
   * @param clientId the registry client's id
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> holdersOfClient(@NotNull UUID clientId) {
    return exchangeInstallationRepository.findHolderUserIdsByClient(clientId);
  }

  /**
   * The members with a connected installation of any registry client.
   *
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> holdersOfAnyClient() {
    return exchangeInstallationRepository.findAllHolderUserIds();
  }
}
