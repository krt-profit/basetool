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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The compact outcome of a change set: counts, and per-op detail only for ops that were not applied
 * (REQ-XCH-015…-017).
 *
 * @param dryRun whether nothing was written
 * @param applied the ops that changed something, or would have in a dry run
 * @param unchanged the ops that found the state they asked for
 * @param notApplied the ops refused, unmatched or ambiguous
 * @param results the detail of every op that was not applied
 * @param offersReduced the Materialbörse offers a stock book-out lowered, or {@code null} for other
 *     resources
 * @param offersRemoved the Materialbörse offers a stock book-out removed, or {@code null} for other
 *     resources
 * @param detachedFromMissions the mission units the removed ships were detached from, or {@code
 *     null} for other resources
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeChangeResultDto(
    boolean dryRun,
    int applied,
    int unchanged,
    int notApplied,
    @NotNull @Unmodifiable List<OpResult> results,
    @Nullable Integer offersReduced,
    @Nullable Integer offersRemoved,
    @Nullable Integer detachedFromMissions) {

  /**
   * The outcome of one op that was not applied.
   *
   * @param index the op's position in the change set
   * @param opId the client's id for the op, or {@code null}
   * @param result {@code unchanged}, {@code unmatched}, {@code ambiguous} or {@code rejected}
   * @param reason the error-registry code of a refusal, or {@code null}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OpResult(
      int index, @Nullable String opId, @NotNull String result, @Nullable String reason) {}
}
