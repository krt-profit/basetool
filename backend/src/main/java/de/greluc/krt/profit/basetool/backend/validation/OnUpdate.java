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

package de.greluc.krt.profit.basetool.backend.validation;

/**
 * Bean Validation group for constraints that apply only on update, letting one write DTO serve both
 * create and update.
 *
 * <p>The optimistic-lock version is declared {@code @NotNull(groups = OnUpdate.class)}: create
 * endpoints validate with {@code @Valid}, update endpoints with
 * {@code @Validated(&#123;Default.class, OnUpdate.class&#125;)}, so only an update requires the
 * version.
 */
public interface OnUpdate {}
