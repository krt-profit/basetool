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

package de.greluc.krt.profit.basetool.backend.validation;

/**
 * Shared validation constants for write DTOs, referenced from {@code @Size(max = …)} and
 * {@code @Pattern(regexp = …)} so a shared limit or regex is defined once (ADR-0060).
 */
public final class DtoConstraints {

  private DtoConstraints() {}

  /** Max length of a short name / label (e.g. a promotion topic or category name): 120 chars. */
  public static final int MAX_SHORT_NAME = 120;

  /** Max length of a general name / external material name: 255 chars. */
  public static final int MAX_NAME = 255;

  /** Max length of a short code (e.g. an external source code): 64 chars. */
  public static final int MAX_CODE = 64;

  /** Max length of a description free-text field: 2000 chars. */
  public static final int MAX_DESCRIPTION = 2000;

  /**
   * Max length of a long description free-text field (e.g. promotion level content): 4000 chars.
   */
  public static final int MAX_LONG_DESCRIPTION = 4000;

  /**
   * Allows an empty value or one that starts with {@code https://} — the "plaintext link must be
   * https" rule shared by the Mission request DTOs. Pair with {@code message = "must start with
   * https://"} to keep the existing violation text.
   */
  public static final String HTTPS_URL_REGEX = "^(https://.*)?$";

  /** The permitted external material-alias source systems. */
  public static final String SOURCE_SYSTEM_REGEX = "UEX|SCWIKI|REFINERY_SCREEN";
}
