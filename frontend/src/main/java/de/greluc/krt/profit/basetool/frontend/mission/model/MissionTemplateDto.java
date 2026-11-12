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

package de.greluc.krt.profit.basetool.frontend.mission.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.UUID;

/**
 * Editable mission template with its current revision.
 *
 * @param id the template id
 * @param owningOrgUnitId the owning org unit
 * @param name the template name
 * @param version the expected revision for the next edit
 * @param objectives the ordered goals
 * @param steps the ordered procedure
 * @param units the ordered unit slots
 */
@DtoMirror
public record MissionTemplateDto(
    UUID id,
    UUID owningOrgUnitId,
    String name,
    long version,
    List<MissionTemplatePlan.Objective> objectives,
    List<MissionTemplatePlan.Step> steps,
    List<MissionTemplatePlan.Unit> units) {}
