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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.time.Instant;

/**
 * Frontend mirror of the backend {@code BlueprintImportResolutionDto}: the user's decision for one
 * external name; a blank {@code productKey} means skip.
 *
 * @param externalName the external name this decision applies to
 * @param productKey normalized key of the chosen product, or blank to skip
 * @param acquiredAt optional acquisition time to stamp
 * @param note optional free-form note
 */
public record BlueprintImportResolutionDto(
    String externalName, String productKey, Instant acquiredAt, String note) {}
