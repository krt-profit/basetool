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
 * The caller belongs to more than one org unit and neither picked an owner nor carries an
 * honourable active-context pin, so §5.5.1's "pin, else choose" rule cannot stamp the aggregate
 * being created (REQ-ORG-017, REQ-ORG-023).
 *
 * <p>Answers {@code 400} with the stable problem code {@code OWNER_ORG_UNIT_REQUIRED}. It exists as
 * its own type purely so that code is stable: this is the one rejection on the stamping path the
 * member can fix themselves, and under the generic {@code BAD_REQUEST} the frontend had nothing to
 * branch on. It fell back to displaying the backend's own English {@code detail} in a German toast,
 * which told the member neither what was wrong nor what to do.
 *
 * <p>It is deliberately <em>not</em> a permission failure. A pick the caller is not allowed to make
 * stays an {@code AccessDeniedException}; this one means no pick was made at all.
 */
public final class OwnerOrgUnitRequiredException extends AppException {

  /**
   * Creates the exception with the developer-facing detail that is also relayed as the RFC 7807
   * {@code detail}.
   *
   * <p>Callers pass prose here rather than an i18n key because the localized text the member
   * actually reads is rendered by the frontend from the stable {@code code}, per picker surface —
   * the surfaces name different things ("Staffel", "Bereich", the SK queue) and a single backend
   * sentence cannot be right on all of them.
   *
   * @param message human-readable description of why no owner could be resolved
   */
  public OwnerOrgUnitRequiredException(String message) {
    super(AppExceptionKind.OWNER_ORG_UNIT_REQUIRED, message);
  }
}
