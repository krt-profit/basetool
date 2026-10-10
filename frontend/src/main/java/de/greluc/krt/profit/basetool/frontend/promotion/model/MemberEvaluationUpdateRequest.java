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

package de.greluc.krt.profit.basetool.frontend.promotion.model;

import de.greluc.krt.profit.basetool.frontend.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;

/**
 * Body of the backend upsert of a member's evaluation in one category.
 *
 * @param version the evaluation's optimistic-lock version, or {@code null} for a new one
 * @param assignedLevel the backend {@code PromotionLevel} name
 */
@DtoMirror
public record MemberEvaluationUpdateRequest(
    Long version, @BackendEnumAsString String assignedLevel) {}
