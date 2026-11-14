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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Makes a member's home-location choice through an exchange client (REQ-PATCH-052): the web's rules
 * and the same relocation, recorded as chosen by the client.
 */
public interface PatchChangeExchangeWriter {

  /**
   * Stores the member's choice in the open phase and relocates the member in one transaction.
   *
   * @param member the member the client acts for
   * @param locationId the chosen home location
   * @param clientId the registry id of the client that chose, recorded in the audit event
   * @return what moved
   * @throws HomeLocationChoiceRefusedException when no open phase asks the member, the member chose
   *     already or the location is not on offer; nothing moves then
   */
  @NotNull
  Chosen chooseHomeLocation(
      @NotNull UUID member, @NotNull UUID locationId, @NotNull String clientId);

  /**
   * What a member's relocation moved.
   *
   * @param location the chosen home location
   * @param rows how many stock rows moved
   * @param ships how many ships moved
   */
  record Chosen(@NotNull PatchChangeExchangeView.Place location, int rows, int ships) {}
}
