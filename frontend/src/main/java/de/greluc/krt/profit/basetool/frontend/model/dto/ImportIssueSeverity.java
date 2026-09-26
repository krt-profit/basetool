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
 * Frontend mirror of the backend {@code ImportIssueSeverity}: the visual grading (danger / warning
 * / info) of an import review finding.
 */
public enum ImportIssueSeverity {

  /** A required pre-fill is impossible (e.g. every row un-quoted); rendered danger-tinted. */
  BLOCKING,

  /** Needs user review before saving; rendered warning-tinted. */
  WARNING,

  /** Heads-up without required action; rendered info-tinted. */
  INFO
}
