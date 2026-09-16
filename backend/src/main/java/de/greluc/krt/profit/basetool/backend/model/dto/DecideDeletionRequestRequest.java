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
 * An admin's decision on a member's erasure request (REQ-SEC-061).
 *
 * <p>Used for both outcomes, because the two differ only in what the note is for and whether the
 * history wish is granted:
 *
 * <ul>
 *   <li><b>Refusing</b> - the note is <b>mandatory</b>. Art. 12(4) obliges the controller to tell
 *       the requester why, so a refusal with no recorded reason cannot be communicated. The
 *       database enforces it too.
 *   <li><b>Carrying it out</b> - the note is optional and records the reasoning, in particular for
 *       the history wish. {@code grantHistoryErasure} is the admin's answer to it and is
 *       independent of what the member asked for: granting is a deliberate act, and a wish is not
 *       an instruction.
 * </ul>
 *
 * @param grantHistoryErasure whether the surviving handle snapshots are anonymised as well; ignored
 *     when refusing
 * @param note the admin's recorded reasoning; mandatory when refusing
 */
public record DecideDeletionRequestRequest(
    boolean grantHistoryErasure, @Nullable @Size(max = 4000) String note) {

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
