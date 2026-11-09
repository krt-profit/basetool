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

import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsReassignment;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The exchange's part of the account merge: installations, ship links and refinery-order keys
 * follow the member, the revocation, change feed, journal and bulk-undo rows stay with the act. Of
 * two live keys alike, the one whose order still exists is kept; a retired key always moves.
 */
@Component
public class ExchangeUserReassignment extends OwnedRowsReassignment {

  static final int POSITION = 150;

  private final ExchangeRefineryOrderKeys keys;

  /**
   * Declares the module's columns.
   *
   * @param mover the engine that runs the statements
   * @param keys the refinery-order keys, settled before they move
   */
  public ExchangeUserReassignment(
      @NotNull OwnedRowsMover mover, @NotNull ExchangeRefineryOrderKeys keys) {
    super(
        mover,
        POSITION,
        List.of(
            OwnedRows.deduped(
                "exchange_installation", "user_id", "exchange_client_id", "key_thumbprint"),
            OwnedRows.deduped(
                "exchange_ship_link", "user_id", "client_id", "installation_key", "external_id"),
            OwnedRows.deduped(
                "exchange_refinery_order_link",
                "user_id",
                "client_id",
                "client_key",
                "retired_at")),
        List.of(
            "exchange_client_revocation.user_id",
            "exchange_change.user_id",
            "exchange_journal.user_id",
            "exchange_bulk_undo_run.requested_by",
            "exchange_bulk_undo_skip.user_id",
            "exchange_name_withholding.user_id"));
    this.keys = keys;
  }

  /**
   * Retires a kept account's live key whose order is gone where the moving account's order holds
   * the same key, then moves the rows; {@code retired_at} in the refinery link's conflict keys
   * matches the live-key unique index, which covers only links not retired.
   *
   * @param source the account being emptied
   * @param target the account being kept
   * @return one entry per table that had a row to move or to drop
   */
  @Override
  public @NotNull List<Moved> reassign(@NotNull UUID source, @NotNull UUID target) {
    keys.handOver(source, target);
    return super.reassign(source, target);
  }
}
