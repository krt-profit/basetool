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

package de.greluc.krt.profit.basetool.frontend.personalinventory.model;

/**
 * Outbound write DTO for creating a personal inventory entry. Validation is enforced on the
 * frontend form ({@link
 * de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryForm}); this
 * record is just the wire shape sent to the backend, so re-declaring constraints here would only
 * duplicate the backend-side validation that ultimately decides acceptance.
 */
public record PersonalInventoryItemCreateRequest(
    String name,
    String note,
    Integer locationUexId,
    PersonalInventoryLocationType locationType,
    Integer quantity) {}
