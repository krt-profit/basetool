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

package de.greluc.krt.profit.basetool.backend.exception;

/**
 * Thrown when a caller in several org units creates an aggregate without choosing an owner and
 * without a usable active-context pin (REQ-ORG-017).
 *
 * <p>Answers {@code 400} with code {@code OWNER_ORG_UNIT_REQUIRED}; it is not a permission failure.
 */
public final class OwnerOrgUnitRequiredException extends AppException {

  /**
   * Creates the exception with a developer-facing detail; the frontend renders the user-visible
   * text from the stable code.
   *
   * @param message why no owner could be resolved
   */
  public OwnerOrgUnitRequiredException(String message) {
    super(AppExceptionKind.OWNER_ORG_UNIT_REQUIRED, message);
  }
}
