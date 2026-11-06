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
 * Where a catalogue came from, {@code source} of the contract; the archive fields are not read.
 *
 * @param channel the Channel, e.g. {@code LIVE}, or {@code null}
 * @param build the Build, or {@code null} when unknown
 * @param toolVersion the p4kx version that wrote the catalogue
 * @param exportedAt when the catalogue was written, RFC 3339 UTC text
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kSource(
    @Nullable String channel,
    @Nullable P4kBuild build,
    @Nullable String toolVersion,
    @Nullable String exportedAt) {}
