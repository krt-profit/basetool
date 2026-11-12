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

/** Catalogue facts shown alongside a P4K review candidate. */
public enum P4kReviewEvidence {
  /** The incoming GUID equals a known Wiki identity. */
  EXACT_SCWIKI_UUID,
  /** The incoming and stored engine class agree. */
  EXACT_CLASS,
  /** The proposed link preserves a known UEX identity. */
  EXACT_UEX_ID,
  /** The candidate already holds the incoming P4K GUID. */
  EXACT_P4K_GUID,
  /** The incoming name matches a stored source name. */
  SAME_NAME,
  /** The incoming code matches a stored source code. */
  SAME_CODE,
  /** The terminal and shop belong to the same resolved place. */
  SAME_PLACE,
  /** The shop and terminal names agree under the catalogue link rule. */
  SHOP_NAME_MATCH,
  /** The candidate belongs to the applicable place type. */
  SAME_TYPE,
  /** The candidate belongs under the resolved ancestor. */
  PARENT_LINK,
  /** The P4K variant relationship names the candidate as its base. */
  P4K_VARIANT,
  /** The case already refers to this row. */
  CURRENT_ROW,
  /** The candidate holds a P4K GUID different from the incoming GUID. */
  OTHER_P4K_GUID,
  /** The source and target have different known manufacturers. */
  DIFFERENT_MANUFACTURER,
  /** The source and target have different known engine classes. */
  DIFFERENT_CLASS
}
