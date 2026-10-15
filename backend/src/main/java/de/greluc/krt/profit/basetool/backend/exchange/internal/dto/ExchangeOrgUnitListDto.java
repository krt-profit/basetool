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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The member's direct org-unit memberships, in the shape of the published {@code
 * org-unit-list.schema.json} (REQ-XCH-048).
 *
 * @param items the org units, ordered Organisationsleitung, Bereich, Staffel, Spezialkommando and
 *     then by name; empty when the member belongs to none
 */
public record ExchangeOrgUnitListDto(@NotNull @Unmodifiable List<ExchangeOrgUnitDto> items) {}
