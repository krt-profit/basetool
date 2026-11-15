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

package de.greluc.krt.profit.basetool.frontend.patchchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Mirror of one catalogue type a wipe group claims (REQ-PATCH-010).
 *
 * @param source which catalogue fact the value is compared with
 * @param value the type key, e.g. {@code Food} or {@code WeaponAttachment:Magazine}
 */
@DtoMirror
public record WipeGroupType(Source source, String value) {

  /**
   * Mirror of the catalogue fact a type key is compared with; labelled by {@code
   * admin.patchChange.typeSource.<SOURCE>}.
   *
   * <p>Must carry every backend constant under the same name, or deserialising a response that
   * contains it fails.
   */
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
