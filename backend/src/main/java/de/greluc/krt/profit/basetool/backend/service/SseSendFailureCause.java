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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

/**
 * The one mapping from a failed SSE emitter write onto the bounded {@code cause} tag of {@code
 * basetool_sse_send_failures_total}, shared by the notification stream ({@link
 * NotificationStreamService}) and the live-sync stream ({@link LiveSyncStreamService}) so the two
 * can never tag the same failure differently.
 */
final class SseSendFailureCause {

  private SseSendFailureCause() {}

  /**
   * Maps a failed emitter write onto the bounded {@code cause} tag vocabulary: an {@link
   * IOException} is the benign client hang-up, an {@link IllegalStateException} means the emitter
   * had already completed (a registry lifecycle race, not a dead client), anything else is {@code
   * other}. Derived from the exception TYPE only — a message or class name would be an unbounded
   * label (REQ-OBS-006).
   *
   * @param cause the exception the emitter write threw
   * @return {@link MetricNames#CAUSE_IO}, {@link MetricNames#CAUSE_ILLEGAL_STATE} or {@link
   *     MetricNames#CAUSE_OTHER}
   */
  @NotNull
  static String tagOf(@NotNull Throwable cause) {
    return switch (cause) {
      case IOException _ -> MetricNames.CAUSE_IO;
      case IllegalStateException _ -> MetricNames.CAUSE_ILLEGAL_STATE;
      default -> MetricNames.CAUSE_OTHER;
    };
  }
}
