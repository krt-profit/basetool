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

package de.greluc.krt.profit.basetool.backend.support;

/** Holds the header naming the member the ingest gateway is acting for (ADR-0129). */
public final class ActingMemberHeader {

  /**
   * Names the member the ingest gateway is acting for.
   *
   * <p>Must equal {@code BackendImportClient.ON_BEHALF_OF_HEADER} in the ingest module, which
   * {@code OnBehalfOfHeaderParityTest} verifies.
   */
  public static final String ON_BEHALF_OF_HEADER = "X-Ingest-On-Behalf-Of";

  /** Not instantiable: a constant holder, not a component. */
  private ActingMemberHeader() {}
}
