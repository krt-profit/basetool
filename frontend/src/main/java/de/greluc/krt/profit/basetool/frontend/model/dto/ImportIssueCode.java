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

package de.greluc.krt.profit.basetool.frontend.model.dto;

/**
 * Frontend mirror of the backend {@code ImportIssueCode}: the machine-readable reason of one
 * refinery-import review finding, translated via {@code refineryImport.issue.<CODE>}. Must match
 * the backend constants, or Jackson binding fails.
 */
public enum ImportIssueCode {

  /** No master-data material matched the raw screen name; suggestions accompany the issue. */
  UNMATCHED_MATERIAL,

  /** Material matched only by the fuzzy stage; the user should verify the pick. */
  LOW_CONFIDENCE_MATERIAL,

  /** Matched input material has no curated refined-material link; output stays empty. */
  NO_REFINED_MATERIAL,

  /** Row quality lies outside the savable 0..1000 range (likely misread). */
  OUT_OF_RANGE_QUALITY,

  /** Location absent (pre-cropped input) or not resolvable to a refinery location. */
  UNRESOLVED_LOCATION,

  /** Refining method not resolvable (case-insensitive). */
  UNRESOLVED_METHOD,

  /** Row skipped: REFINE toggle was off (typically inert materials). */
  SKIPPED_REFINE_OFF,

  /** Row skipped: zero input or output quantity. */
  SKIPPED_ZERO_QTY,

  /** Row skipped: YIELD was un-quoted ({@code --}) — capture before GET QUOTE. */
  UNQUOTED_ROW,

  /** Whole order captured before GET QUOTE; nothing could be pre-filled. */
  UNQUOTED_ORDER,

  /** The rows read sum past the TO REFINE panel total — mis-read quantity or duplicate capture. */
  SUM_MISMATCH,

  /** The extract carried more than one order; only the first was imported. */
  MULTIPLE_ORDERS_TRUNCATED,

  /** Reserved for future schema versions; not emitted in v1. */
  UNSUPPORTED_PANEL_TYPE
}
