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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * Why the P4K import refused a catalogue (REQ-P4K-001, REQ-P4K-002, REQ-P4K-004); the lower-case
 * name is the {@code reason} tag of {@code basetool_p4k_import_rejected_total}.
 */
public enum P4kRejectionReason {

  /** {@code schemaVersion} is not {@code 2}, absent, or the head cannot be read. */
  SCHEMA_VERSION,

  /** {@code source.channel} is not {@code LIVE}. */
  CHANNEL,

  /** {@code source.build} is {@code null} or its {@code changeNumber} is no decimal integer. */
  BUILD_MISSING,

  /** The upload or its decompressed content passes its size limit. */
  TOO_LARGE,

  /** An APPLY of a Build older than that of the last applied run. */
  OLDER_BUILD;

  /**
   * Returns the metric tag value.
   *
   * @return the lower-case name, e.g. {@code schema_version}
   */
  @NotNull
  public String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
