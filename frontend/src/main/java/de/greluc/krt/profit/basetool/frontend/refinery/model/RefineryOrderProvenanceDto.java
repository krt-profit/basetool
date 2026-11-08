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

package de.greluc.krt.profit.basetool.frontend.refinery.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code RefineryOrderProvenanceDto}: the client that created an
 * order, its last change and how it left the refinery (REQ-REFINERY-027, REQ-REFINERY-028).
 *
 * @param createdByClient the display name of the client that created the order, {@code null} for
 *     the web
 * @param lastChangedByClient the display name of the client of the last change, {@code null} for
 *     the web
 * @param lastChangedAt when the order or its goods last changed
 * @param pickedUpAt when a client reported the pickup, or {@code null}
 * @param pickedUpByClient the display name of the client that reported the pickup, or {@code null}
 * @param storedAt when the yield was booked into the Lager, or {@code null}
 */
@DtoMirror
public record RefineryOrderProvenanceDto(
    @Nullable String createdByClient,
    @Nullable String lastChangedByClient,
    @Nullable Instant lastChangedAt,
    @Nullable Instant pickedUpAt,
    @Nullable String pickedUpByClient,
    @Nullable Instant storedAt) {

  /**
   * Whether a client closed the order by a pickup without booking its yield: the order reads
   * „Abgeholt" then, and „Eingelagert" once it is stored (REQ-REFINERY-028).
   *
   * @param provenance the order's provenance, or {@code null} when the backend sent none
   * @return {@code true} for a picked-up, unstored order
   */
  @Contract(value = "null -> false", pure = true)
  public static boolean pickedUpWithoutStore(@Nullable RefineryOrderProvenanceDto provenance) {
    return provenance != null && provenance.pickedUpAt() != null && provenance.storedAt() == null;
  }
}
