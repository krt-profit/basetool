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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The runs of one synchronisation or import that finished inside a range, and when it last ran.
 *
 * @param sourceSystem the source's name ({@code UEX}, {@code SCWIKI} or {@code P4K})
 * @param runs how many distinct runs finished inside the range
 * @param lastRanAt when the source last attempted a sync, absent when it never did
 * @param lastCompleteAt newest fully applied attempt finish
 * @param lastStatus outcome of the newest attempt
 * @param attempts bounded recent attempt history, including event-free attempts
 */
public record SyncRunSummaryDto(
    @NotNull String sourceSystem,
    long runs,
    @Nullable Instant lastRanAt,
    @Nullable Instant lastCompleteAt,
    @Nullable String lastStatus,
    List<SyncRunDto> attempts) {

  /**
   * Represents a source whose history contains only legacy anomaly events.
   *
   * @param sourceSystem source name
   * @param runs retained legacy runs in the requested range
   * @param lastRanAt newest legacy event timestamp
   */
  public SyncRunSummaryDto(String sourceSystem, long runs, @Nullable Instant lastRanAt) {
    this(sourceSystem, runs, lastRanAt, null, null, List.of());
  }
}
