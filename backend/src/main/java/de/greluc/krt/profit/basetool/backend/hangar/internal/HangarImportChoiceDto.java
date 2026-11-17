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

import de.greluc.krt.profit.basetool.backend.kernel.DtoConstraints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The ship type the member chose for one upload name that may mean several ships (REQ-HANGAR-019).
 * It applies only when the ship type is one of the name's options in the run.
 *
 * @param name the upload name as the import reported it; compared ignoring case and surrounding
 *     blanks
 * @param shipTypeId the chosen option's ship type
 */
public record HangarImportChoiceDto(
    @NotBlank @Size(max = DtoConstraints.MAX_NAME) String name, @NotNull UUID shipTypeId) {}
