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

import org.jetbrains.annotations.Nullable;

/**
 * Whether a P4K import is due and the versions that decide it (REQ-OBS-022, REQ-P4K-047).
 *
 * @param due whether the UEX or the SC Wiki version is newer than the basis, or there is no basis
 * @param uexVersion the highest game version UEX stamped on the items, or {@code null}
 * @param wikiVersion the game version of the last complete SC Wiki sync, or {@code null}
 * @param basisVersion the patch version of the last APPLY whose version is known, or {@code null}
 *     when no such run exists
 * @param basisOrigin whether the basis is a public patch or a branch lower bound, {@code null}
 *     exactly when {@code basisVersion} is
 */
public record P4kImportDueDto(
    boolean due,
    @Nullable String uexVersion,
    @Nullable String wikiVersion,
    @Nullable String basisVersion,
    @Nullable P4kPatchVersionOrigin basisOrigin) {}
