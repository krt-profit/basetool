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

package de.greluc.krt.profit.basetool.frontend.exchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirror of an admin's bulk undo run and its progress (REQ-XCH-034).
 *
 * @param id the run
 * @param exchangeClientId the registry id of the client
 * @param clientId the client id
 * @param clientName the registry display name of the client
 * @param status {@code RUNNING}, {@code COMPLETED} or {@code FAILED}
 * @param since the start of the undone span
 * @param installationId the one installation undone, or {@code null} for all
 * @param resource the one resource undone, or {@code null} for all
 * @param requestedByName the starting admin's display name, or {@code null}
 * @param membersTotal how many members had writes in scope
 * @param membersDone how many members were processed
 * @param membersFailed how many members could not be processed
 * @param restored how many entries were restored
 * @param skipped how many entries were left alone
 * @param startedAt when the run started
 * @param finishedAt when it ended, or {@code null} while it runs
 */
@DtoMirror
public record ExchangeBulkUndoRunDto(
    UUID id,
    UUID exchangeClientId,
    String clientId,
    String clientName,
    String status,
    Instant since,
    UUID installationId,
    String resource,
    String requestedByName,
    int membersTotal,
    int membersDone,
    int membersFailed,
    int restored,
    int skipped,
    Instant startedAt,
    Instant finishedAt) {

  /**
   * Whether the run is still working through the members.
   *
   * @return {@code true} while it runs
   */
  public boolean running() {
    return "RUNNING".equals(status);
  }
}
