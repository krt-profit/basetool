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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names the connected application a booking request was raised through, for the staff badge
 * (REQ-BANK-067).
 */
@Component
@RequiredArgsConstructor
public class BankRequestClientNames {

  private final ClientDirectory clientDirectory;

  /**
   * Names the request's client.
   *
   * @param request the request
   * @return the registry's display name, the stored client id for a client the registry no longer
   *     holds, or {@code null} for a request raised in the Basetool
   */
  @Nullable
  public String nameOf(@NotNull BankBookingRequest request) {
    String clientId = request.getClientId();
    return clientId == null
        ? null
        : clientDirectory.displayNames(List.of(clientId)).getOrDefault(clientId, clientId);
  }
}
