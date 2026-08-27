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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend's {@code CreateMissionRequest} write-only DTO at {@code POST
 * /api/v1/missions}. Separate from {@link MissionDto} so the create flow does not have to thread
 * dozens of read-only fields (sub-missions, participants, inventory, version counters) through a
 * null-filled constructor every time.
 *
 * <p>R5.d.d added the trailing {@link #owningOrgUnitId} picker output — the backend resolves it
 * through {@code OwnerScopeService.resolveOrgUnitForPickerOutputNullable}, which accepts all four
 * org-unit kinds (Staffel, Spezialkommando, Bereich, Organisationsleitung). A non-null value is
 * honoured when it is one of the mission owner's DIRECT memberships or an org unit the caller may
 * edit ({@code AccessGateService.canEditOrgUnit}, cascade-aware — epic #692 Phase 4 / REQ-ORG-016),
 * and rejected with 400 otherwise. {@code null} auto-stamps a single-membership owner, honours an
 * active-context pin (REQ-ORG-017), 400s a multi-membership owner with neither, and leaves the
 * mission ownerless for a membershipless leadership owner (V144 / ADR-0004).
 *
 * <p>{@link #objectives} / {@link #steps} carry the optional Ziele / Ablauf rows the create form
 * seeds together with the mission (both {@code null} when none). The write controller builds them
 * from the form's JSON carriers; the backend maps each {@code kind} string to its {@code
 * MissionObjectiveKind} and validates title/kind before persisting.
 */
public record CreateMissionRequest(
    String name,
    String description,
    String calendarLink,
    String status,
    Instant meetingTime,
    Instant plannedStartTime,
    Instant plannedEndTime,
    Boolean isInternal,
    UUID operationId,
    UUID owningOrgUnitId,
    String meetingPoint,
    List<NewObjective> objectives,
    List<NewStep> steps) {

  /**
   * A goal (Ziel) seeded together with the mission; mirrors the backend's {@code
   * CreateMissionRequest.NewObjective}.
   *
   * @param title the goal text
   * @param kind the classification enum name ("PRIMARY" / "SECONDARY" / "NON_GOAL"), mapped to the
   *     backend's {@code MissionObjectiveKind}
   */
  public record NewObjective(String title, @BackendEnumAsString String kind) {}

  /**
   * A step (Ablauf-Schritt) seeded together with the mission; mirrors the backend's {@code
   * CreateMissionRequest.NewStep}.
   *
   * @param title the step title
   * @param meta the optional free-text time/place hint
   */
  public record NewStep(String title, String meta) {}
}
