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

package de.greluc.krt.profit.basetool.frontend.blueprint.model;

/**
 * Read DTO mirroring the backend {@code PersonalBlueprintBulkDeleteResult} (REQ-INV-023 /
 * REQ-INV-024): the number of blueprints removed by a "delete all my blueprints" clear or the admin
 * "delete all users' blueprints" purge, surfaced to the user as a toast. Auto-granted defaults
 * (REQ-INV-016) are preserved, so this never counts a default.
 *
 * @param deleted number of removable owned-blueprint rows removed by the operation
 */
public record PersonalBlueprintBulkDeleteResultDto(int deleted) {}
