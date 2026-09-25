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

package de.greluc.krt.profit.basetool.frontend.model.form;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Form-binding object for the admin member-edit page.
 *
 * <p>A member may belong to up to two Staffeln (REQ-ORG-017), modelled as two fixed slots ({@code
 * staffel1*} / {@code staffel2*}) with per-squadron flags (REQ-SEC-005). The controller folds the
 * non-empty slots into one {@code PATCH /api/v1/users/{id}/memberships}; clearing both removes
 * every Staffel membership.
 *
 * @param rank pay-grade rank (1-20)
 * @param description profile description
 * @param displayName visible display name
 * @param version {@code app_user} row {@code @Version} for the optimistic-lock check
 * @param source origin marker; {@code "profile"} returns to the profile page, anything else to the
 *     member list
 * @param joinDate squadron-join date
 * @param staffel1Id first slot's target Squadron id, or {@code null} when the slot is empty
 * @param staffel1Logistician first slot's desired Logistician flag (defaults {@code false})
 * @param staffel1MissionManager first slot's desired Mission Manager flag (defaults {@code false})
 * @param staffel2Id second slot's target Squadron id, or {@code null} when the slot is empty; a
 *     duplicate of {@code staffel1Id} is dropped by the controller
 * @param staffel2Logistician second slot's desired Logistician flag (defaults {@code false})
 * @param staffel2MissionManager second slot's desired Mission Manager flag (defaults {@code false})
 * @param staffelDetailLoaded whether the Staffel-membership detail loaded when the form was
 *     rendered; when {@code false} the controller must not reconcile the Staffel set on save, since
 *     the blank slots would strip every membership
 */
public record MemberEditForm(
    Integer rank,
    @Size(max = 2000, message = "{validation.description.max}") String description,
    @Size(max = 255, message = "{validation.displayname.max}") String displayName,
    Long version,
    String source,
    @Nullable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinDate,
    @Nullable UUID staffel1Id,
    @Nullable Boolean staffel1Logistician,
    @Nullable Boolean staffel1MissionManager,
    @Nullable UUID staffel2Id,
    @Nullable Boolean staffel2Logistician,
    @Nullable Boolean staffel2MissionManager,
    @Nullable Boolean staffelDetailLoaded) {

  /**
   * Convenience constructor that defaults {@link #staffelDetailLoaded()} to {@code true} — the
   * normal "detail loaded" path. Lets callers (and tests) that do not track the Staffel-detail load
   * outcome build the form without the trailing flag; the page controller uses the full canonical
   * constructor to thread the real load outcome.
   *
   * @param rank pay-grade rank (1-20).
   * @param description profile description.
   * @param displayName visible display name.
   * @param version {@code app_user} row {@code @Version} for the optimistic-lock check.
   * @param source origin marker — {@code "profile"} keeps the round-trip on the profile page.
   * @param joinDate squadron-join date.
   * @param staffel1Id first Staffel slot's target Squadron id, or {@code null}.
   * @param staffel1Logistician first slot's desired Logistician flag.
   * @param staffel1MissionManager first slot's desired Mission Manager flag.
   * @param staffel2Id second Staffel slot's target Squadron id, or {@code null}.
   * @param staffel2Logistician second slot's desired Logistician flag.
   * @param staffel2MissionManager second slot's desired Mission Manager flag.
   */
  public MemberEditForm(
      Integer rank,
      String description,
      String displayName,
      Long version,
      String source,
      @Nullable LocalDate joinDate,
      @Nullable UUID staffel1Id,
      @Nullable Boolean staffel1Logistician,
      @Nullable Boolean staffel1MissionManager,
      @Nullable UUID staffel2Id,
      @Nullable Boolean staffel2Logistician,
      @Nullable Boolean staffel2MissionManager) {
    this(
        rank,
        description,
        displayName,
        version,
        source,
        joinDate,
        staffel1Id,
        staffel1Logistician,
        staffel1MissionManager,
        staffel2Id,
        staffel2Logistician,
        staffel2MissionManager,
        Boolean.TRUE);
  }
}
