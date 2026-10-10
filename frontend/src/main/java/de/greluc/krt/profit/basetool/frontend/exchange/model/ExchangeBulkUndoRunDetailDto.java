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
import java.util.List;

/**
 * Mirror of a bulk undo run with the entries it left alone (REQ-XCH-034).
 *
 * @param run the run
 * @param skipped the first skipped entries, failed members first
 * @param skippedTotal how many skipped entries the run has in all
 */
@DtoMirror
public record ExchangeBulkUndoRunDetailDto(
    ExchangeBulkUndoRunDto run, List<SkippedEntry> skipped, long skippedTotal) {

  /**
   * One entry the run left alone, or a member it could not process.
   *
   * @param memberName the member's display name
   * @param resource {@code BLUEPRINT}, {@code STOCK}, {@code SHIP}, or {@code null} for a failed
   *     member
   * @param label the entry's name, or {@code null} when unknown
   * @param reason {@code CHANGED_AFTERWARDS}, {@code GONE} or {@code FAILED}
   */
  public record SkippedEntry(String memberName, String resource, String label, String reason) {}
}
