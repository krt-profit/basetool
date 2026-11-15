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
 * Mirror of one patch change in the admin history (REQ-PATCH-008).
 *
 * @param id the patch change's id
 * @param version the optimistic-lock version a label correction echoes
 * @param label the patch label
 * @param kind the patch kind
 * @param liveAt the live time
 * @param triggeredAt when the admin triggered it
 * @param triggeredBy the triggering admin's display name, or {@code null} when the account is gone
 * @param options the options it was triggered with
 * @param wipeExceptions its wipe exceptions with their entries' names, never {@code null} once
 *     constructed
 * @param counts what its effects touched, one entry per kind
 * @param phase its home-location phase, or {@code null} when it did not ask for a home location
 */
@DtoMirror
public record PatchChangeDto(
    UUID id,
    long version,
    String label,
    PatchKind kind,
    Instant liveAt,
    Instant triggeredAt,
    String triggeredBy,
    PatchChangeOptions options,
    List<WipeExceptionDto> wipeExceptions,
    List<PatchCountDto> counts,
    PatchChangePhaseDto phase) {

  /** Copies the exceptions, so a missing list reads as none. */
  public PatchChangeDto {
    wipeExceptions = wipeExceptions == null ? List.of() : List.copyOf(wipeExceptions);
  }
}
