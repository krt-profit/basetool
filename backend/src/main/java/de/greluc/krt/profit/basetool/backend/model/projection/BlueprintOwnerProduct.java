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
 * JPQL projection of a {@code personal_blueprint} row reduced to its owner and product name, for
 * the availability overview and the item-order owner drill-down (REQ-DATA-003).
 *
 * @param ownerUserId the {@code app_user.id} of the blueprint's owner.
 * @param productName the product name as stored on the blueprint (case-preserving).
 */
public record BlueprintOwnerProduct(UUID ownerUserId, String productName) {}
