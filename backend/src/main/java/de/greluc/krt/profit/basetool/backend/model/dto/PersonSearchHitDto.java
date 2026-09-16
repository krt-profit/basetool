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

import org.jetbrains.annotations.Nullable;

/**
 * One hit of the admin Personensuche: a place where the searched name appears (REQ-SEC-060).
 *
 * <p>The snippet is the matched column, clipped. It is the entry itself rather than a summary,
 * because the admin has to judge whether this occurrence is the person who made the request - two
 * members can share a spelling, and a handle is not a unique key.
 *
 * @param area the bounded area label, rendered via {@code admin.personSearch.area.*}
 * @param table the physical table the hit came from, shown so a rectification can be applied
 *     precisely and so a report to the requester can name the surface
 * @param column the matched column
 * @param rowId the row identity as text, for the link; {@code null} when the row has no id column
 * @param snippet the matched text, clipped to a readable length
 * @param linkKind the bounded route key the frontend turns into a link, or {@code null} when the
 *     row has no page of its own and the hit is informational
 */
public record PersonSearchHitDto(
    String area,
    String table,
    String column,
    @Nullable String rowId,
    @Nullable String snippet,
    @Nullable String linkKind) {}
