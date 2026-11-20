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
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * An upload the gate accepted, handed from the gate to the import job. It carries the payload array
 * itself without copying it and has no value equality.
 */
@Getter
@RequiredArgsConstructor
public final class P4kAcceptedUpload {

  /** The bytes the job stores, always compressed (REQ-P4K-002). */
  private final byte @NotNull [] storedPayload;

  /** The size of the file as uploaded. */
  private final long uploadedBytes;

  /** The Build the catalogue names, which the gate requires. */
  @NotNull private final P4kBuild build;
}
