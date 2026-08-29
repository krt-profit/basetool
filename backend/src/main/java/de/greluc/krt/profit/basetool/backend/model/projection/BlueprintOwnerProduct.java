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

package de.greluc.krt.profit.basetool.backend.model.projection;

import java.util.UUID;

/**
 * Lightweight JPQL constructor projection of a {@code personal_blueprint} row carrying only the two
 * columns the availability-overview aggregation and the item-order owner drill-down actually read:
 * the owner and the product name. Both surfaces group rows by variant family and count distinct
 * owners, so hydrating the full entity (all columns, the whole table for an admin all-scope view)
 * just to read these two fields is wasted I/O and heap (REQ-DATA-003) — the projection scales with
 * the two needed columns instead.
 *
 * @param ownerSub the {@code app_user.id} of the blueprint's owner.
 * @param productName the product name as stored on the blueprint (case-preserving).
 */
public record BlueprintOwnerProduct(UUID ownerSub, String productName) {}
