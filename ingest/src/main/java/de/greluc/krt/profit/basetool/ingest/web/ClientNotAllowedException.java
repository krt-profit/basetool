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

package de.greluc.krt.profit.basetool.ingest.web;

import java.io.Serial;

/**
 * Raised when an authenticated caller's <em>client software</em> is not approved for the ingest
 * path (REQ-INGEST-011) — the payload-level half of the check, thrown from {@code ProvenanceGuard}
 * once the body has been parsed. The token-level half short-circuits earlier, in {@code
 * ClientIdentityFilter}, which writes the identical problem shape directly to the response.
 *
 * <p>Mapped by {@link GlobalExceptionHandler} to a {@code 403} carrying the stable {@code
 * CLIENT_NOT_ALLOWED} code — deliberately not the generic {@code ACCESS_DENIED}, because the user
 * is fully entitled here and it is the tool that is refused. The extractor surfaces the detail
 * verbatim, so conflating the two would tell a member "you are not allowed" when the accurate
 * answer is "use the official extractor".
 */
public class ClientNotAllowedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception with the detail sent to the caller.
   *
   * @param message the non-sensitive, human-readable problem detail; must never quote the rejected
   *     payload back, which would echo unvalidated internet-facing input into a response
   */
  public ClientNotAllowedException(String message) {
    super(message);
  }
}
