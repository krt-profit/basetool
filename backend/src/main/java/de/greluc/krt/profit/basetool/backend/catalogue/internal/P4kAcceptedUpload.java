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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBuild;
import org.jetbrains.annotations.NotNull;

/**
 * An upload the gate accepted.
 *
 * @param storedPayload the bytes the job stores, always compressed (REQ-P4K-002)
 * @param uploadedBytes the size of the file as uploaded
 * @param build the Build the catalogue names, which the gate requires
 */
public record P4kAcceptedUpload(
    byte @NotNull [] storedPayload, long uploadedBytes, @NotNull P4kBuild build) {}
