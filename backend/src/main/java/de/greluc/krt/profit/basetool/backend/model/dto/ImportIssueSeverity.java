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
 * Severity grading of an {@link ImportIssueDto}, driving its styling in the review UI.
 *
 * <p>Never blocks the response: even a {@link #BLOCKING} issue returns 200 with the draft.
 */
public enum ImportIssueSeverity {

  /**
   * A required pre-fill is impossible (e.g. every row un-quoted). The draft is returned but the
   * user cannot meaningfully save without resolving the underlying capture problem.
   */
  BLOCKING,

  /** Something needs user review before saving (unmatched name, checksum mismatch, skip). */
  WARNING,

  /** Heads-up with no required action (e.g. a missing admin-curated refined-material link). */
  INFO
}
