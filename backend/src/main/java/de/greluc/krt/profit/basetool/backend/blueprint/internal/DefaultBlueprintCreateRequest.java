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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import jakarta.validation.constraints.NotBlank;

/**
 * Write DTO for adding one product to the default-blueprint set (REQ-INV-017). The admin picks a
 * product from the blueprint catalog type-ahead and submits its normalized {@code productKey}; the
 * service resolves the canonical name and output item from the catalog before stamping the row.
 *
 * @param productKey normalized product key of the blueprint product to mark as default
 */
public record DefaultBlueprintCreateRequest(@NotBlank String productKey) {}
