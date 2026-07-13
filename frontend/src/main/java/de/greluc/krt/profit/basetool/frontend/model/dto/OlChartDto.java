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

import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the Organisationsleitung tier of the org chart (epic #692, REQ-ORG-018),
 * decoded from {@code GET /api/v1/org-chart}: the OL org unit's id + name plus its OL_MEMBER nodes.
 *
 * <p>The whole record is {@code null} on the parent {@link OrgChartDto} when no active OL exists,
 * so the template omits the OL tier entirely. The {@code orgUnitId} is the scope the inline editor
 * stamps a new OL member against, so the "add OL member" affordance works even while the tier holds
 * no members yet.
 *
 * @param orgUnitId the OL org unit's id (the add-member affordance's target scope).
 * @param name the OL's display name (the tier caption).
 * @param shorthand the OL's short tag.
 * @param grandAdmiral the OL member holding the Grand Admiral post (REQ-ORG-021), rendered at the
 *     top of the OL above the other members, or {@code null} when the post is vacant. Split out of
 *     {@code members}, so it never also appears there.
 * @param members the remaining OL members (OL_MEMBER positions, excluding the Grand Admiral); never
 *     {@code null}, possibly empty.
 */
public record OlChartDto(
    UUID orgUnitId,
    String name,
    String shorthand,
    OrgChartNodeDto grandAdmiral,
    List<OrgChartNodeDto> members) {}
