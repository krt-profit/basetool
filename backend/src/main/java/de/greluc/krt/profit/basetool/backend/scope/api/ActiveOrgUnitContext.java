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

package de.greluc.krt.profit.basetool.backend.scope.api;

import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What the active org-unit context of a request covers (REQ-ORG-024): one org unit, all of the
 * caller's org units, every org unit for an admin, or none.
 *
 * @param kind what the context covers; never {@code null}
 * @param orgUnitId the one org unit the context covers, set exactly when {@code kind} is {@link
 *     Kind#ORG_UNIT}
 */
public record ActiveOrgUnitContext(@NotNull Kind kind, @Nullable UUID orgUnitId) {

  /** The context of a caller who belongs to no org unit. */
  public static final ActiveOrgUnitContext NONE = new ActiveOrgUnitContext(Kind.NONE, null);

  /** The context of a member whose context spans all of their org units. */
  public static final ActiveOrgUnitContext ALL_MY_ORG_UNITS =
      new ActiveOrgUnitContext(Kind.ALL_MY_ORG_UNITS, null);

  /** The context of an admin without a pin. */
  public static final ActiveOrgUnitContext ALL_ORG_UNITS =
      new ActiveOrgUnitContext(Kind.ALL_ORG_UNITS, null);

  /** What an active org-unit context covers. */
  public enum Kind {

    /** Exactly one org unit: the pinned one, or the only one the caller belongs to. */
    ORG_UNIT,

    /** Every org unit the non-admin caller belongs to or reaches through a leadership seat. */
    ALL_MY_ORG_UNITS,

    /** Every org unit; an admin without a pin. */
    ALL_ORG_UNITS,

    /** No org unit; a non-admin caller who belongs to none. */
    NONE
  }

  /**
   * Checks that the org unit is set exactly for {@link Kind#ORG_UNIT}.
   *
   * @throws IllegalArgumentException when the org unit does not match the kind
   */
  public ActiveOrgUnitContext {
    if ((kind == Kind.ORG_UNIT) != (orgUnitId != null)) {
      throw new IllegalArgumentException("orgUnitId must be set exactly for ORG_UNIT");
    }
  }

  /**
   * The context of exactly one org unit.
   *
   * @param orgUnitId the org unit; never {@code null}
   * @return the context
   */
  @Contract(pure = true)
  public static @NotNull ActiveOrgUnitContext orgUnit(@NotNull UUID orgUnitId) {
    return new ActiveOrgUnitContext(Kind.ORG_UNIT, orgUnitId);
  }
}
