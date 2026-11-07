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
 * One entry of the curated {@code shipStoreSlugs} kind (contract, "Ship store slugs"), bound to the
 * fields the store-URL cross-check reads.
 *
 * @param slug the last segment of the ship's RSI pledge URL
 * @param className the class name of the mapped ship, {@code null} for a retired slug
 * @param shipGuid the GUID of the mapped ship, {@code null} for a retired slug
 * @param retired whether the slug's ship no longer exists in the game data
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kShipStoreSlug(
    @Nullable String slug,
    @Nullable String className,
    @Nullable String shipGuid,
    @Nullable Boolean retired) {}
