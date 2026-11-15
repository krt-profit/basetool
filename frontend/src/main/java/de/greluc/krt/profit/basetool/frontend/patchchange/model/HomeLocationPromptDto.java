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

package de.greluc.krt.profit.basetool.frontend.patchchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mirror of a member's home-location prompt (REQ-PATCH-021, REQ-PATCH-022): where the member stands
 * in the latest home-location phase, the home locations on offer and what the relocation would
 * move.
 *
 * @param state where the member stands
 * @param patchChangeId the phase's patch change, or {@code null} when no phase ever asked
 * @param label its label, or {@code null} when no phase ever asked
 * @param liveAt its live time, or {@code null} when no phase ever asked
 * @param chosen the chosen home location, or {@code null} before the choice or once deleted
 * @param chosenAt when the member relocated, or {@code null} before the choice
 * @param locations the curated home locations on offer, never {@code null} once constructed
 * @param preview what moves, per current location, never {@code null} once constructed; empty
 *     unless the member is pending
 */
@DtoMirror
public record HomeLocationPromptDto(
    MemberRelocationState state,
    UUID patchChangeId,
    String label,
    Instant liveAt,
    HomeLocationOptionDto chosen,
    Instant chosenAt,
    List<HomeLocationOptionDto> locations,
    List<HomeLocationPreviewDto> preview) {

  /** Copies the locations and the preview, so a missing list reads as none. */
  public HomeLocationPromptDto {
    locations = locations == null ? List.of() : List.copyOf(locations);
    preview = preview == null ? List.of() : List.copyOf(preview);
  }
}
