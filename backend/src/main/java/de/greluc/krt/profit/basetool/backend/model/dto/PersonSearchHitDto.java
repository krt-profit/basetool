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
 * One hit of the admin person search: a place where the searched name appears (REQ-SEC-060).
 *
 * @param area the bounded area label, rendered via {@code admin.personSearch.area.*}
 * @param table the physical table of the hit
 * @param column the matched column
 * @param rowId the row identity as text, or {@code null} when the row has no id column
 * @param snippet the matched text, clipped
 * @param linkKind the bounded route key for the frontend link, or {@code null} when the row has no
 *     page
 */
public record PersonSearchHitDto(
    String area,
    String table,
    String column,
    @Nullable String rowId,
    @Nullable String snippet,
    @Nullable String linkKind) {}
