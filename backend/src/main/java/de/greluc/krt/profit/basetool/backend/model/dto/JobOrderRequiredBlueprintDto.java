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

/**
 * One required-product row of the item job-order blueprint-coverage view: a variant family the
 * order requests, with the number of responsible-org-unit members owning a blueprint for it.
 *
 * <p>Cosmetic variants collapse onto one family key; magazines stay atomic. An {@code ownerCount}
 * of zero marks a coverage gap.
 *
 * @param productKey the variant family key the order's item line resolves to
 * @param productName the display name of the ordered item (the variant name when a variant was
 *     ordered)
 * @param ownerCount the number of distinct responsible-org-unit members owning the blueprint for
 *     this item or any variant of it
 * @param variantInclusive whether the count includes owners of cosmetic variants; {@code false} for
 *     an exactly matched magazine row
 */
public record JobOrderRequiredBlueprintDto(
    String productKey, String productName, int ownerCount, boolean variantInclusive) {}
