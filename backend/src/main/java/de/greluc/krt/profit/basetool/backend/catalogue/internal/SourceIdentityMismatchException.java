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

import java.io.Serial;
import java.util.UUID;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Rejects a detail payload that identifies another asset than the requested catalogue record. */
@Getter
public final class SourceIdentityMismatchException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** The asset UUID requested by the importer. */
  private final UUID requestedUuid;

  /** The payload's asset UUID, or null when the source omitted its identity. */
  private final @Nullable UUID servedUuid;

  /**
   * Captures the conflicting source identities without retaining the rejected payload.
   *
   * @param requestedUuid asset requested by the importer
   * @param servedUuid asset identified by the source payload
   */
  public SourceIdentityMismatchException(@NotNull UUID requestedUuid, @Nullable UUID servedUuid) {
    super("External item detail returned another asset identity");
    this.requestedUuid = requestedUuid;
    this.servedUuid = servedUuid;
  }
}
