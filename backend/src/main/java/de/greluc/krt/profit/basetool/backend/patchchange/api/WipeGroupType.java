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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import org.jetbrains.annotations.NotNull;

/**
 * One catalogue type a wipe group claims (REQ-PATCH-010).
 *
 * @param source which catalogue fact the value is compared with
 * @param value the type key, e.g. {@code Food}, {@code WeaponAttachment:Magazine} or {@code RAW}
 */
public record WipeGroupType(@NotNull Source source, @NotNull String value) {

  /** The catalogue fact a type key is compared with, in the order an item is matched. */
  public enum Source {
    /** A material's type. */
    MATERIAL_TYPE,
    /** An item's P4K type and sub-type, joined by a colon. */
    ITEM_SUB_TYPE,
    /** An item's P4K type. */
    ITEM_TYPE,
    /** The start of an item's P4K type. */
    ITEM_TYPE_PREFIX,
    /** The start of an untyped item's SC Wiki classification. */
    WIKI_CLASSIFICATION_PREFIX,
    /** An untyped item's kind. */
    ITEM_KIND,
    /** A ship's origin. */
    SHIP_ORIGIN
  }
}
