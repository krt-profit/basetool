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
 * The material demand of one <em>responsible</em> (processing) org unit in the cross-order overview
 * (REQ-ORDERS-034) — the group a user reads to answer "what does my unit still have to gather".
 * Grouping on the responsible rather than the requesting unit is deliberate: the responsible unit
 * is the one that has to procure the material, and it is the side the job-order visibility scope
 * keys on (REQ-ORG-003).
 *
 * @param orgUnit the responsible squadron or Spezialkommando; {@code null} only for the fallback
 *     group collecting orders whose responsible unit could not be resolved, so such demand is
 *     surfaced rather than silently dropped
 * @param materials the unit's aggregated material buckets, SCU materials first, then by material
 *     name, then {@code GOOD} before {@code NONE} — the ordering the order detail's material tables
 *     already use; never empty (a unit with no demand produces no group at all)
 */
public record MaterialDemandGroupDto(
    SquadronReferenceDto orgUnit, List<MaterialDemandRowDto> materials) {}
