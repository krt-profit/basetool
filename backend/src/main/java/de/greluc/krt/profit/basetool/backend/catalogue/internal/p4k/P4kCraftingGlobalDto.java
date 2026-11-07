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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code craftingGlobal} object of catalogue version 2, bound to the resources a dismantle
 * never returns; an entity that cannot be dismantled already has no {@code dismantle} on its
 * blueprint.
 *
 * @param dismantleExcludedResourceGuids the {@code ResourceType} GUIDs a dismantle never returns
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kCraftingGlobalDto(@Nullable List<String> dismantleExcludedResourceGuids) {}
