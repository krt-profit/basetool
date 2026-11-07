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

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** The fields every catalogue entity carries (contract, "Entities"). */
public interface P4kRecord {

  /**
   * Returns the Record GUID as canonical lower-case UUID text.
   *
   * @return the GUID text, or {@code null} when absent
   */
  @Nullable
  String guid();

  /**
   * Returns the Record name without its type.
   *
   * @return the class name, or {@code null}
   */
  @Nullable
  String className();

  /**
   * Returns the record path without {@code .xml}.
   *
   * @return the record path, or {@code null}
   */
  @Nullable
  String recordPath();

  /**
   * Returns the localised name.
   *
   * @return the name, or {@code null}
   */
  @Nullable
  P4kLocalisedText name();

  /**
   * Returns the English name.
   *
   * @return the English name, or {@code null} while unknown
   */
  @Nullable
  default String nameEn() {
    return P4kLocalisedText.enOf(name());
  }

  /**
   * Returns the name a change report row stores (REQ-P4K-014).
   *
   * @return the English name, or {@code null} for a kind whose name is a game text
   */
  @Nullable
  default String reportName() {
    return nameEn();
  }

  /**
   * Returns the name a row created from the record shows: the English name, or the class name while
   * the name is unknown or a placeholder.
   *
   * @return the display name, or {@code null} when the record has neither
   */
  @Nullable
  default String displayName() {
    String en = nameEn();
    return en == null || en.isBlank() ? className() : en;
  }

  /**
   * Parses {@link #guid()}.
   *
   * @return the GUID, or {@code null} when absent or not a UUID
   */
  @Nullable
  default UUID parsedGuid() {
    return P4kGuids.parse(guid());
  }
}
