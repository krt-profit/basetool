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

package de.greluc.krt.profit.basetool.backend.model.dto;

/**
 * Machine-readable reason attached to an {@link ImportIssueDto} of a refinery screenshot import
 * draft.
 *
 * <p>The frontend translates each code via {@code refineryImport.issue.*}; renaming a constant
 * breaks the API.
 */
public enum ImportIssueCode {

  /**
   * No master-data material matched the raw screen name through any stage (canonical, alias,
   * suffix, fuzzy). The draft row is kept with a {@code null} material; ranked {@code suggestions}
   * accompany the issue so the review UI can offer a pick list.
   */
  UNMATCHED_MATERIAL,

  /**
   * A material was matched only fuzzily; the issue carries the score and ranked alternatives for
   * the user to verify.
   */
  LOW_CONFIDENCE_MATERIAL,

  /**
   * The matched input material has no admin-curated {@code refinedMaterial} link, so {@code
   * outputMaterial} stays empty. Informational: the existing create path falls back to the input
   * material itself, and gaps are expected (the link is neither UEX- nor Wiki-synced).
   */
  NO_REFINED_MATERIAL,

  /**
   * The row's quality lies outside 0..1000; the value stays unclamped and must be corrected before
   * saving.
   */
  OUT_OF_RANGE_QUALITY,

  /** {@code rawLocationName} was null or did not resolve to a refinery-equipped location. */
  UNRESOLVED_LOCATION,

  /** {@code rawMethodName} did not resolve to a known refining method (case-insensitive). */
  UNRESOLVED_METHOD,

  /** The row's REFINE toggle was OFF, so it was not added to the draft. */
  SKIPPED_REFINE_OFF,

  /** The row carried a zero input or output quantity and was not added to the draft. */
  SKIPPED_ZERO_QTY,

  /** The row's YIELD cell was unquoted ({@code "--"}); the screenshot predates GET QUOTE. */
  UNQUOTED_ROW,

  /**
   * Every row of the order (or the order itself per the producer's {@code quoted} flag) is in the
   * pre-GET-QUOTE state — nothing can be pre-filled. Blocking: the user must press GET QUOTE in
   * game and re-capture.
   */
  UNQUOTED_ORDER,

  /**
   * The refine-ON row quantities exceed the {@code TO REFINE} total beyond ±1 per row of rounding.
   *
   * <p>A shortfall is never flagged.
   */
  SUM_MISMATCH,

  /**
   * The extract carried more than one order; v1 processes only {@code orders[0]} and ignores the
   * rest. Informational.
   */
  MULTIPLE_ORDERS_TRUNCATED,

  /**
   * Reserved for content-level panel-type problems in future schema versions. In v1 an unsupported
   * {@code panelType} on {@code orders[0]} is an envelope-level 400, so this code is not emitted
   * yet — it stays in the contract for forward compatibility.
   */
  UNSUPPORTED_PANEL_TYPE
}
