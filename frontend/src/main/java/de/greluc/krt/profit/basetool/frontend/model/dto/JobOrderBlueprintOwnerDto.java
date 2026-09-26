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

import java.util.List;

/**
 * Frontend mirror of the backend {@code JobOrderBlueprintOwnerDto}: a member who owns the blueprint
 * for at least one of an item order's required products, with those products.
 *
 * @param ownerName the member's effective display name
 * @param ownedProductNames the required products this member owns the blueprint for
 * @param orgUnitMember {@code true} when the owner is a member of the order's responsible org unit,
 *     {@code false} when visible only via global blueprint sharing (REQ-INV-018)
 */
public record JobOrderBlueprintOwnerDto(
    String ownerName, List<String> ownedProductNames, boolean orgUnitMember) {}
