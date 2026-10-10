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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What an undo of a client's writes did, as the backend reports it (REQ-XCH-022).
 *
 * @param restored how many entries were set back
 * @param skipped the entries left as they are, with the reason
 */
@DtoMirror
public record ExchangeUndoResultDto(int restored, @NotNull List<Skipped> skipped) {

  /**
   * One entry the undo left alone.
   *
   * @param resource {@code BLUEPRINT}, {@code STOCK} or {@code SHIP}
   * @param label the entry's name, or {@code null} when it is no longer known
   * @param reason {@code CHANGED_AFTERWARDS} or {@code GONE}
   */
  public record Skipped(@NotNull String resource, @Nullable String label, @NotNull String reason) {}
}
