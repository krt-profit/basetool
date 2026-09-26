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
 * One job-order assignee: the user, their optional note and the edge's own optimistic-lock version,
 * independent of the order's version.
 *
 * @param user the assigned user
 * @param note the assignee's note, or {@code null} when none is set
 * @param version optimistic-lock version of the assignee edge
 */
public record JobOrderAssigneeDto(UserDto user, @Nullable String note, Long version) {}
