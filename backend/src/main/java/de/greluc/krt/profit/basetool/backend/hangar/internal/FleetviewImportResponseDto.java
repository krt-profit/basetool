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

import java.util.List;

/**
 * Response DTO for the Fleetview JSON import endpoint.
 *
 * <p>Returns import statistics and lists of ships that could not be processed, so the user can take
 * corrective action (e.g. run a UEX sync or rename ships manually).
 *
 * @param importedCount ships created
 * @param skippedCount distinct ship names that matched no ship type
 * @param duplicateCount upload entries matched to a ship already in the hangar
 * @param skippedShips the unmatched ship names
 * @param duplicateShips reserved, always empty
 * @param updatedCount matched ships whose production status or pledge data changed
 */
public record FleetviewImportResponseDto(
    int importedCount,
    int skippedCount,
    int duplicateCount,
    List<String> skippedShips,
    List<String> duplicateShips,
    int updatedCount) {}
