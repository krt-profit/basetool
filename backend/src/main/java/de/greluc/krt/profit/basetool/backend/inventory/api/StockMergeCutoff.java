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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Tells the Lager's write-time merge from when on a holder's stock rows may take part in it
 * (REQ-INV-026, REQ-PATCH-004): while a cutoff stands, a row created before it, or without a
 * creation time, neither absorbs nor is folded into another, so it keeps holding only what it held
 * when it was created. Implemented by the patch-change module; each read joins the caller's
 * transaction.
 */
public interface StockMergeCutoff {

  /**
   * Returns the instant before which a holder's rows never merge.
   *
   * @param holderId the member whose rows the merge would touch
   * @return the open home-location phase's live time while the holder's relocation is pending in
   *     it, otherwise empty, when every row of the holder merges
   */
  @NotNull
  Optional<Instant> cutoffFor(@NotNull UUID holderId);
}
