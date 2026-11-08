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
import org.jetbrains.annotations.Nullable;

/**
 * One entry of {@code gameplayProperties} in catalogue version 2, the stat a blueprint modifier's
 * {@code propertyGuid} names.
 *
 * @param guid the Record GUID
 * @param className the Record name, e.g. {@code GPP_Weapon_Damage}
 * @param name the localised stat name; {@code en} and {@code de} are {@code null} for a placeholder
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kGameplayPropertyDto(
    @Nullable String guid, @Nullable String className, @Nullable P4kLocalisedText name) {}
