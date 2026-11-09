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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

/**
 * The per-op reasons several resources' change results share, as the error registry names them
 * ({@code docs/exchange/errors.md}); a reason only one resource gives stays with that resource.
 */
final class ExchangeReasons {

  /** The reason of a reference that names nothing. */
  static final String UNMATCHED = "UNMATCHED";

  /** The reason of a reference that names several entries. */
  static final String AMBIGUOUS = "AMBIGUOUS";

  /** The refusal of a place without a Lager location. */
  static final String LOCATION_UNKNOWN = "LOCATION_UNKNOWN";

  /** The refusal of a version or section counter the entry no longer has. */
  static final String VERSION_CONFLICT = "VERSION_CONFLICT";

  /** The refusal of an org unit that is not one of the member's direct memberships. */
  static final String NOT_A_MEMBER = "NOT_A_MEMBER";

  /** The refusal of an org unit the op may not book or put the entry in. */
  static final String ORG_UNIT_NOT_ALLOWED = "ORG_UNIT_NOT_ALLOWED";

  /** The refusal of a quantity that is not in its material's or item's own unit. */
  static final String UNIT_MISMATCH = "UNIT_MISMATCH";

  private ExchangeReasons() {}
}
