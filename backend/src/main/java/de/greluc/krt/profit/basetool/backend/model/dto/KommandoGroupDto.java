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

import java.util.UUID;

/**
 * Read model for a Kommandogruppe, a named sub-structure of a Staffel that grants no rights
 * (REQ-ROLE-003).
 *
 * @param id the Kommandogruppe id; never {@code null} for a persisted group.
 * @param squadronId the owning Staffel ({@code SQUADRON}) org-unit id; never {@code null}.
 * @param name the group's display name (e.g. "Alpha", "Jagd"); never {@code null}.
 * @param sortIndex the ascending display order within the squadron.
 * @param version the optimistic-lock version echoed back for concurrent-edit detection.
 */
public record KommandoGroupDto(
    UUID id, UUID squadronId, String name, int sortIndex, Long version) {}
