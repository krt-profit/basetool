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

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import java.util.UUID;

/**
 * Minimal org-unit reference for rendering a badge, embedded as a list in {@link
 * MissionParticipantDto}.
 *
 * @param id the org unit's id
 * @param name the long-form display name
 * @param shorthand the short handle shown on the chip; may be {@code null}
 * @param kind distinguishes a Staffel from a Spezialkommando
 */
public record OrgUnitReferenceDto(UUID id, String name, String shorthand, OrgUnitKind kind) {}
