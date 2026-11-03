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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One location where a material can be found, as served by {@code GET
 * /api/v1/materials/{id}/occurrences} (REQ-DATA-031).
 *
 * @param locationId the local id of the location row of {@code kind}
 * @param kind which kind of location this is
 * @param name the location's name
 * @param starSystemName the star system the location lies in; {@code null} for a star system and
 *     when UEX names none
 * @param parentName the body the location belongs to — a moon's planet, a point of interest's moon,
 *     planet or orbit; {@code null} when there is none
 */
public record MaterialOccurrenceDto(
    @NotNull UUID locationId,
    @NotNull MaterialOccurrenceKind kind,
    @Nullable String name,
    @Nullable String starSystemName,
    @Nullable String parentName) {}
