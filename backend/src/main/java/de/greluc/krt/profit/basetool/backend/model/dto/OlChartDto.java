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
import java.util.UUID;

/**
 * The Organisationsleitung tier at the top of the org chart: the OL org unit's identity plus its
 * OL_MEMBER nodes (REQ-ORG-026). The whole record is {@code null} on {@link OrgChartDto} when no
 * active OL exists.
 *
 * @param orgUnitId the OL org unit's id, the scope new OL_MEMBER positions are stamped against
 * @param name the OL's display name (the tier caption)
 * @param shorthand the OL's short tag
 * @param grandAdmiral the OL member holding the Grand Admiral post (REQ-ORG-021), or {@code null}
 *     when vacant; never also listed in {@code members}
 * @param members the remaining OL members; never {@code null}, possibly empty
 */
public record OlChartDto(
    UUID orgUnitId,
    String name,
    String shorthand,
    OrgChartNodeDto grandAdmiral,
    List<OrgChartNodeDto> members) {}
