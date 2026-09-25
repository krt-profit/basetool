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

/**
 * Create payload for the singleton Organisationsleitung, relayed to the backend {@code POST
 * /api/v1/org-hierarchy/organisationsleitung} (REQ-ORG-014); a second create is rejected with 409.
 *
 * @param name the OL's display name; required, unique across all org units.
 * @param shorthand the OL's short tag; required.
 * @param description free-form text; nullable.
 */
public record OrganisationsleitungCreateRequest(
    String name, String shorthand, String description) {}
