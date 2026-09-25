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
 * One Kommando within a Staffel: the Kommando row itself, its optional Kommandoleiter and Stv.
 * Kommandoleiter, and the Ensigns reporting into it.
 *
 * <p>The leader lives on the Kommando row and is projected inline as an account ({@link
 * #leaderUserId} / {@link #leaderUserName}) or a free-text name ({@link #leaderDisplayName}); all
 * are {@code null} while the seat is vacant.
 *
 * @param positionId id of the Kommando row; the handle for rename / remove / assign-lead /
 *     add-child
 * @param name the Kommando's display name, or {@code null} when unnamed
 * @param version optimistic-lock version of the Kommando row
 * @param sortIndex display order among the Staffel's Kommandos
 * @param kommandoGroupId id of the mirrored {@code kommando_group}, or {@code null} for a
 *     chart-only Kommando; when set, the subtree is managed under Leitung and rendered read-only
 *     (REQ-ROLE-006)
 * @param leaderUserId id of the Kommandoleiter account, or {@code null}
 * @param leaderUserName the Kommandoleiter account's display name, or {@code null}
 * @param leaderDisplayName free-text Kommandoleiter name, or {@code null}; mutually exclusive with
 *     {@code leaderUserId}
 * @param deputy the Stv. Kommandoleiter node, or {@code null}
 * @param ensigns the Ensigns in display order; never {@code null}, possibly empty
 */
public record CommandChartDto(
    UUID positionId,
    String name,
    Long version,
    int sortIndex,
    UUID kommandoGroupId,
    UUID leaderUserId,
    String leaderUserName,
    String leaderDisplayName,
    OrgChartNodeDto deputy,
    List<OrgChartNodeDto> ensigns) {}
