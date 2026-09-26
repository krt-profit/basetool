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

import java.util.List;

/**
 * One member row of the item job-order blueprint-coverage view: a member owning the blueprint for
 * at least one of the order's required items. Carries the display name only, never account
 * identifiers.
 *
 * @param ownerName the member's effective display name
 * @param ownedProductNames the order's required products this member owns the blueprint for, sorted
 *     case-insensitively; never empty
 * @param orgUnitMember {@code false} when the owner appears only through global blueprint sharing
 *     (REQ-INV-018)
 */
public record JobOrderBlueprintOwnerDto(
    String ownerName, List<String> ownedProductNames, boolean orgUnitMember) {}
