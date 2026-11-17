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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The member's choices for the upload names that may mean several ships, sent with a repeated
 * import of the same file (REQ-HANGAR-019).
 *
 * @param choices one ship type per ambiguous upload name
 */
public record HangarImportChoicesDto(
    @NotNull @Size(max = HangarImportChoicesDto.MAX_CHOICES)
        List<@NotNull @Valid HangarImportChoiceDto> choices) {

  /** The most choices one import may carry. */
  public static final int MAX_CHOICES = 1000;
}
