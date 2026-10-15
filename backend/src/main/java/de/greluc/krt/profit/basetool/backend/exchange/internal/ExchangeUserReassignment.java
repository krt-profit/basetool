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
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The exchange's part of the account merge: installations and ship links follow the member, the
 * revocation, change feed, journal and bulk-undo rows stay with the act.
 */
@Component
public class ExchangeUserReassignment extends OwnedRowsReassignment {

  static final int POSITION = 150;

  /**
   * Declares the module's columns.
   *
   * @param mover the engine that runs the statements
   */
  public ExchangeUserReassignment(@NotNull OwnedRowsMover mover) {
    super(
        mover,
        POSITION,
        List.of(
            OwnedRows.deduped(
                "exchange_installation", "user_id", "exchange_client_id", "key_thumbprint"),
            OwnedRows.deduped(
                "exchange_ship_link", "user_id", "client_id", "installation_key", "external_id")),
        List.of(
            "exchange_client_revocation.user_id",
            "exchange_change.user_id",
            "exchange_journal.user_id",
            "exchange_bulk_undo_run.requested_by",
            "exchange_bulk_undo_skip.user_id",
            "exchange_name_withholding.user_id"));
  }
}
