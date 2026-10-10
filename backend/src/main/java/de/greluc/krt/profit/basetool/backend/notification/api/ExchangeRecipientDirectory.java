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

package de.greluc.krt.profit.basetool.backend.notification.api;

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves the holders of external-client installations as notification recipients.
 *
 * <p>Owned by the notification module and implemented by the exchange module.
 */
public interface ExchangeRecipientDirectory {

  /**
   * The members with a connected, non-revoked installation of one registry client.
   *
   * @param clientId the registry client's id
   * @return their user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> holdersOfClient(@NotNull UUID clientId);

  /**
   * The members with a connected, non-revoked installation of any registry client.
   *
   * @return their user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> holdersOfAnyClient();
}
