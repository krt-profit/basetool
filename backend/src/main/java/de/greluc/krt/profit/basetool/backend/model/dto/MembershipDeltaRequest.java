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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Payload of {@code PATCH /api/v1/users/{id}/memberships}, applying all Staffel and SK membership
 * changes of the admin member-edit page in one transaction.
 *
 * @param staffeln the desired complete Staffel membership set (0–2 entries, REQ-ORG-017), which the
 *     backend reconciles against the current state; {@code null} leaves the Staffel side untouched.
 * @param specialCommands the SK changes to apply, or {@code null} / empty to leave the SK side
 *     untouched.
 */
public record MembershipDeltaRequest(
    @Size(max = 2, message = "A user may belong to at most two Staffeln") @Nullable
        List<@Valid StaffelChange> staffeln,
    @Nullable List<@Valid SpecialCommandChange> specialCommands) {

  /**
   * One entry of the desired Staffel membership set; the reconcile decides whether it adds a
   * membership or patches its flags, and an omitted squadron is removed.
   *
   * @param squadronId the target Squadron id; never {@code null}.
   * @param isLogistician desired Logistician flag, scoped to this squadron (REQ-SEC-005); {@code
   *     null} means {@code false}.
   * @param isMissionManager desired Mission Manager flag; {@code null} means {@code false}.
   */
  public record StaffelChange(
      @NotNull UUID squadronId,
      @Nullable Boolean isLogistician,
      @Nullable Boolean isMissionManager) {}

  /**
   * One SK membership change, selected by its {@link #action}. The lead flag is not part of it.
   *
   * @param orgUnitId the {@code SpecialCommand} id this entry targets; never {@code null}.
   * @param action {@link Action#ADD} creates a membership, {@link Action#REMOVE} deletes it, {@link
   *     Action#PATCH} updates its flags under optimistic lock.
   * @param isLogistician new flag value for ADD or PATCH; {@code null} means unchanged on PATCH and
   *     {@code false} on ADD.
   * @param isMissionManager new flag value for ADD or PATCH; ignored on REMOVE.
   * @param version the membership row's current version; required for PATCH only.
   */
  public record SpecialCommandChange(
      @NotNull UUID orgUnitId,
      @NotNull Action action,
      @Nullable Boolean isLogistician,
      @Nullable Boolean isMissionManager,
      @Nullable Long version) {

    /**
     * Action discriminator for {@link SpecialCommandChange}. Three operations the delta endpoint
     * can apply per SK membership row.
     */
    public enum Action {
      /** Create a new membership row. */
      ADD,
      /** Delete an existing membership row. */
      REMOVE,
      /** Update flags on an existing membership row under optimistic-lock. */
      PATCH
    }
  }
}
