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

package de.greluc.krt.profit.basetool.frontend.model;

import de.greluc.krt.profit.basetool.frontend.mission.model.MissionParticipantDto;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code OrgUnitReferenceDto}. Carries the slim org-unit reference
 * (a Staffel or a Spezialkommando) rendered as a chip in a mission participant's affiliation
 * column. Embedded as a list into {@link MissionParticipantDto} so a participant affiliated with
 * both a Staffel and one or more Spezialkommandos renders all of its badges.
 *
 * @param id surrogate id of the org unit.
 * @param name long-form display name (tooltip / full-width cell).
 * @param shorthand abbreviated chip handle; may be {@code null}.
 * @param kind discriminator string ({@code SQUADRON} or {@code SPECIAL_COMMAND}), kept as a plain
 *     string so the frontend needs no parallel enum that could drift from the backend.
 */
public record OrgUnitReferenceDto(
    UUID id, String name, String shorthand, @BackendEnumAsString String kind) {}
