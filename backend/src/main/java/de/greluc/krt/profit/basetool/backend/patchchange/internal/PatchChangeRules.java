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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The order rules of the patch-change history (REQ-PATCH-001, REQ-PATCH-003): labels increase with
 * the live times, and a new live time lies after the previous one and not after now.
 */
final class PatchChangeRules {

  /** Not instantiable. */
  private PatchChangeRules() {}

  /**
   * Resolves and checks the live time of a new patch change.
   *
   * @param requested the live time the admin chose, or {@code null} for now
   * @param now the moment of the check
   * @param previous the latest patch change, or {@code null} before the first
   * @return the live time
   * @throws BadRequestException {@code error.patch_change.live_at_future} for a live time after
   *     now, {@code error.patch_change.live_at_not_after_previous} for one not after the previous
   *     patch change's
   */
  static @NotNull Instant requireLiveAt(
      @Nullable Instant requested, @NotNull Instant now, @Nullable PatchChange previous) {
    Instant liveAt = requested == null ? now : requested;
    if (liveAt.isAfter(now)) {
      throw new BadRequestException("error.patch_change.live_at_future");
    }
    if (previous != null && !liveAt.isAfter(previous.getLiveAt())) {
      throw new BadRequestException("error.patch_change.live_at_not_after_previous");
    }
    return liveAt;
  }

  /**
   * Checks that a label names a later patch than the patch change before it.
   *
   * @param label the label
   * @param previous the patch change live before, or {@code null} for the first
   * @throws BadRequestException {@code error.patch_change.label_not_higher} otherwise
   */
  static void requireLabelAfter(@NotNull PatchLabel label, @Nullable PatchChange previous) {
    if (previous != null && !label.isAfter(previous.patchLabel())) {
      throw new BadRequestException("error.patch_change.label_not_higher");
    }
  }

  /**
   * Checks that a label names an earlier patch than the patch change after it.
   *
   * @param label the label
   * @param next the patch change live after, or {@code null} for the latest
   * @throws BadRequestException {@code error.patch_change.label_not_lower} otherwise
   */
  static void requireLabelBefore(@NotNull PatchLabel label, @Nullable PatchChange next) {
    if (next != null && !next.patchLabel().isAfter(label)) {
      throw new BadRequestException("error.patch_change.label_not_lower");
    }
  }
}
