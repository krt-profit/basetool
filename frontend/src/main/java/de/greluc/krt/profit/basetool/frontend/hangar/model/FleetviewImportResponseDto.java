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

package de.greluc.krt.profit.basetool.frontend.hangar.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;

/**
 * The backend's result of a hangar ship-export import.
 *
 * @param importedCount how many ships were imported
 * @param skippedCount how many entries were skipped
 * @param duplicateCount how many entries were already in the hangar
 * @param skippedShips the names of the skipped entries
 * @param duplicateShips the names of the duplicate entries
 */
@DtoMirror
public record FleetviewImportResponseDto(
    int importedCount,
    int skippedCount,
    int duplicateCount,
    List<String> skippedShips,
    List<String> duplicateShips) {}
