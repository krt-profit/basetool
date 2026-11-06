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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.List;

/**
 * Frontend mirror of the backend {@code SyncRunSummaryDto}, read by the sync status widget; must
 * match it field for field.
 *
 * @param sourceSystem the source's name ({@code UEX}, {@code SCWIKI} or {@code P4K})
 * @param runs source sweeps started inside the range
 * @param lastRanAt newest source attempt start
 * @param lastCompleteAt newest completely applied source sweep finish
 * @param lastStatus newest source attempt outcome
 * @param attempts bounded recent source and aggregate attempts
 */
@DtoMirror
public record SyncRunSummaryDto(
    String sourceSystem,
    long runs,
    Instant lastRanAt,
    Instant lastCompleteAt,
    String lastStatus,
    List<SyncRunDto> attempts) {

  /**
   * Represents a source with only legacy event history.
   *
   * @param sourceSystem external source
   * @param runs retained legacy runs in the range
   * @param lastRanAt latest legacy event timestamp
   */
  public SyncRunSummaryDto(String sourceSystem, long runs, Instant lastRanAt) {
    this(sourceSystem, runs, lastRanAt, null, null, List.of());
  }
}
