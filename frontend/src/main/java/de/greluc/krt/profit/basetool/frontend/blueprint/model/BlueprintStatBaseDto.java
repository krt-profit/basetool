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

package de.greluc.krt.profit.basetool.frontend.blueprint.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Frontend mirror of the backend blueprint stat base value: the crafted item's value of one stat
 * before ingredient quality applies.
 *
 * @param propertyKey the SC Wiki property key the modifiers name
 * @param baseValue the base value in {@code unit}; a {@code PERCENT} value is a fraction of one
 * @param unit the unit name, e.g. {@code "NONE"}, {@code "PERCENT"} or {@code "CELSIUS"}
 */
@DtoMirror
public record BlueprintStatBaseDto(
    String propertyKey, double baseValue, @BackendEnumAsString String unit) {}
