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

package de.greluc.krt.profit.basetool.backend.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What an undo of a client's writes did (REQ-XCH-022).
 *
 * @param restored how many entries were set back to their state before the client's writes
 * @param skipped the entries left as they are, with the reason
 */
public record ExchangeUndoResultDto(int restored, @NotNull @Unmodifiable List<Skipped> skipped) {

  /**
   * One entry the undo left alone.
   *
   * @param resource {@code BLUEPRINT}, {@code STOCK} or {@code SHIP}
   * @param label the entry's name — a blueprint, a material or item, or a ship type — or {@code
   *     null} when it is no longer known
   * @param reason {@code CHANGED_AFTERWARDS} when it was changed after the client's last write, or
   *     {@code GONE} when it no longer belongs to the member or what it names is gone
   */
  @Schema(name = "Skipped")
  public record Skipped(@NotNull String resource, @Nullable String label, @NotNull String reason) {}
}
