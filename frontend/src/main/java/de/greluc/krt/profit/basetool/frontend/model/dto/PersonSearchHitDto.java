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

import org.jetbrains.annotations.Nullable;

/**
 * One hit of the admin Personensuche (REQ-SEC-060).
 *
 * @param area the bounded area label, rendered via {@code admin.personSearch.area.*}
 * @param table the physical table, shown so a rectification can be applied precisely
 * @param column the matched column
 * @param rowId the row identity as text, for the link
 * @param snippet the matched text, clipped by the backend
 * @param linkKind the bounded route key this page turns into a link, or {@code null} when the row
 *     has no page of its own
 */
public record PersonSearchHitDto(
    String area,
    String table,
    String column,
    @Nullable String rowId,
    @Nullable String snippet,
    @Nullable String linkKind) {}
