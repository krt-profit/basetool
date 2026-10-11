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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A de-duplicated export entry: external product name, structural tag and earliest acquisition
 * instant.
 *
 * @param externalName the trimmed external product name
 * @param tag the scmdb.net structural blueprint key (REQ-INV-019), or {@code null}
 * @param suggestedAcquiredAt the earliest acquisition instant, or {@code null}
 */
public record ParsedEntry(
    @NotNull String externalName, @Nullable String tag, @Nullable Instant suggestedAcquiredAt) {}
