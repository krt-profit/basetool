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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend membership-delta payload for {@code PATCH
 * /api/v1/users/{id}/memberships}: the desired Staffel set plus a list of SK changes.
 *
 * @param staffeln desired complete Staffel membership set (0–2 entries), or {@code null} to leave
 *     the Staffel side untouched. A non-null list is reconciled by the backend against the current
 *     state (an empty list removes every Staffel membership).
 * @param specialCommands list of SK-side changes; may be {@code null} or empty.
 */
public record MembershipDeltaRequest(
    @Nullable List<StaffelChange> staffeln, @Nullable List<SpecialCommandChange> specialCommands) {

  /**
   * One desired Staffel membership. Declarative: each entry names a target Squadron plus the flag
   * values that membership should carry. Add vs in-place flag patch is decided by the backend
   * reconcile; removal is expressed by omitting a currently-held squadron from {@link #staffeln}.
   *
   * @param squadronId target Squadron id; never {@code null} ("no Staffel" is an empty {@link
   *     #staffeln} list).
   * @param isLogistician desired Logistician flag for this squadron; {@code null} means {@code
   *     false}.
   * @param isMissionManager desired Mission Manager flag for this squadron; {@code null} means
   *     {@code false}.
   */
  public record StaffelChange(
      UUID squadronId, @Nullable Boolean isLogistician, @Nullable Boolean isMissionManager) {}

  /**
   * SK-side change record. {@link #action} picks between ADD (new membership), REMOVE (delete
   * existing), PATCH (flag update with optimistic-lock).
   *
   * @param orgUnitId SpecialCommand id this entry targets.
   * @param action ADD / REMOVE / PATCH.
   * @param isLogistician new flag value (ADD or PATCH).
   * @param isMissionManager new flag value (ADD or PATCH).
   * @param version {@code @Version} of the membership row (PATCH only).
   */
  public record SpecialCommandChange(
      UUID orgUnitId,
      Action action,
      @Nullable Boolean isLogistician,
      @Nullable Boolean isMissionManager,
      @Nullable Long version) {

    /** Action discriminator. */
    public enum Action {
      /** Create a new membership row. */
      ADD,
      /** Delete an existing membership row. */
      REMOVE,
      /** Update flags on an existing membership row. */
      PATCH
    }
  }
}
