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

import jakarta.validation.constraints.Size;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An admin's decision on a member's erasure request, used for both refusing and carrying it out
 * (REQ-SEC-061).
 *
 * @param grantHistoryErasure whether the surviving handle snapshots are anonymised too; ignored
 *     when refusing
 * @param note the reason; required for a refusal, ignored on execution
 * @param version the request row's optimistic-lock version; {@code null} skips the check
 */
public record DecideDeletionRequestRequest(
    boolean grantHistoryErasure, @Nullable @Size(max = 4000) String note, @Nullable Long version) {

  /**
   * The note as a refusal requires it: present and not blank.
   *
   * @return the note, guaranteed non-blank
   * @throws IllegalArgumentException when it is absent or blank
   */
  public @NotNull String requiredNote() {
    if (note == null || note.isBlank()) {
      throw new IllegalArgumentException("A refused erasure request must carry a reason");
    }
    return note;
  }
}
