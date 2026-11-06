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
 * The Build a catalogue was made from, {@code source.build} of the contract.
 *
 * @param branch the build branch, e.g. {@code sc-alpha-4.10.2}
 * @param version the build version, for display only
 * @param changeNumber the Perforce change number as decimal text; orders Builds within a Channel
 * @param buildId the random build identifier, for equality only
 * @param publicPatch the public patch version from the RSI Launcher log, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kBuild(
    @Nullable String branch,
    @Nullable String version,
    @Nullable String changeNumber,
    @Nullable String buildId,
    @Nullable String publicPatch) {}
