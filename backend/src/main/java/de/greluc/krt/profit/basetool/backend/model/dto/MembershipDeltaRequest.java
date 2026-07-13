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
 * SPEZIALKOMMANDO_PLAN.md §7.4 single-POST membership-delta payload. Endpoint {@code PATCH
 * /api/v1/users/{id}/memberships} accepts this record so the admin member-edit page can bundle
 * every Staffel-assignment change, every SK add / remove and every flag toggle into one
 * transactional round-trip.
 *
 * <p>The payload is two-part: the {@link #staffeln} list — the caller's desired <em>complete</em>
 * Staffel membership set (REQ-ORG-017 allows up to two) — plus a list of {@link
 * SpecialCommandChange}s. Both parts are optional and treated independently:
 *
 * <ul>
 *   <li>{@code staffeln == null} → the user's Staffel memberships are left untouched.
 *   <li>{@code staffeln} non-null (including an empty list) → the backend <em>reconciles</em> the
 *       user's Staffel memberships to exactly this set: squadrons present here but not yet a
 *       membership are added, current Staffel memberships absent here are removed, and a
 *       still-present squadron's flags are patched in place. An empty list therefore removes every
 *       Staffel membership.
 *   <li>{@code specialCommands == null} / empty → the SK side is left untouched.
 * </ul>
 *
 * @param staffeln the desired complete Staffel membership set (0–2 entries), or {@code null} to
 *     leave the Staffel side untouched. A non-null list is reconciled against the current state.
 * @param specialCommands the list of SK changes to apply in this transaction, or {@code null} /
 *     empty when only the Staffel side is being touched.
 */
public record MembershipDeltaRequest(
    @Size(max = 2, message = "A user may belong to at most two Staffeln") @Nullable
        List<@Valid StaffelChange> staffeln,
    @Nullable List<@Valid SpecialCommandChange> specialCommands) {

  /**
   * One entry of the desired Staffel membership set. Declarative, not action-tagged: each entry
   * names a target Squadron plus the flag values that membership should carry once the reconcile
   * has run. Whether the entry results in an add (no membership yet) or an in-place flag patch (the
   * user is already a member) is decided by the backend reconcile, not the caller — so the client
   * sends the same shape regardless. Removal is expressed by <em>omitting</em> a currently-held
   * squadron from the {@link MembershipDeltaRequest#staffeln} list.
   *
   * @param squadronId the target Squadron id; never {@code null} (a "no Staffel" intent is an empty
   *     {@link MembershipDeltaRequest#staffeln} list, not a {@code null} entry).
   * @param isLogistician desired value of the Logistician flag on this Staffel membership row;
   *     {@code null} is treated as {@code false}. The flag is scoped to this squadron
   *     (REQ-SEC-005).
   * @param isMissionManager desired value of the Mission Manager flag on this Staffel membership
   *     row; {@code null} is treated as {@code false}.
   */
  public record StaffelChange(
      @NotNull UUID squadronId,
      @Nullable Boolean isLogistician,
      @Nullable Boolean isMissionManager) {}

  /**
   * SK-side instruction. One entry per SK the admin wants to add, remove or patch. The {@link
   * #action} tag picks between the three branches; the remaining fields are read or ignored
   * depending on the action.
   *
   * @param orgUnitId the {@code SpecialCommand} id this entry targets; never {@code null}.
   * @param action which branch to take: {@link Action#ADD} creates a new membership row (flags
   *     default to {@code false} unless explicitly supplied); {@link Action#REMOVE} deletes the
   *     existing row; {@link Action#PATCH} updates the flag values on an existing row under
   *     optimistic-lock.
   * @param isLogistician new flag value for ADD or PATCH; ignored on REMOVE. {@code null} on PATCH
   *     means "leave unchanged"; {@code null} on ADD means "default to false".
   * @param isMissionManager new flag value for ADD or PATCH; ignored on REMOVE.
   * @param version current {@code @Version} of the membership row; required for PATCH; ignored for
   *     ADD and REMOVE.
   *     <p>{@code is_lead} is intentionally NOT part of this payload — Lead toggles stay ADMIN-only
   *     and isolated in the SK detail page workflow per plan D2, so the audit trail can attribute
   *     promotion / demotion actions cleanly to a specific admin call.
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
