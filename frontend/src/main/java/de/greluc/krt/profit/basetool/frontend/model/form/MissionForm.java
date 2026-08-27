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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Form-binding object for mission input.
 *
 * <p>{@code version} is the legacy global mission counter and remains for the create-mission flow.
 * On updates of an existing mission, the section-scoped counters {@code coreVersion}, {@code
 * scheduleVersion} and {@code flagsVersion} drive optimistic locking — they enable concurrent users
 * to edit disjoint sections (core / schedule / flags) on the same mission without producing
 * spurious 409 conflicts.
 *
 * <p>{@code calendarLink} is rendered as an {@code &lt;a href&gt;} on the public landing page. The
 * {@code @Pattern} forces an {@code https://} prefix so a mission manager cannot persist a {@code
 * javascript:fetch(document.cookie)} stored-XSS payload — Thymeleaf's {@code th:href} only
 * HTML-escapes the value, not the scheme. The backend mirrors the same constraint on its DTO so
 * both layers reject the payload independently (audit finding H-1).
 *
 * <p>{@code owningOrgUnitId} (R5.d.d) is the owner-picker output: when the caller belongs to more
 * than one OrgUnit, the picker offers each membership and the chosen id lands here. The backend
 * service resolves it via {@code OwnerScopeService.resolveOrgUnitForPickerOutputNullable}, which
 * accepts all four org-unit kinds (Staffel, Spezialkommando, Bereich, Organisationsleitung) and
 * rejects with 400 only a pick that is neither one of the mission owner's DIRECT memberships nor an
 * org unit the caller may edit ({@code AccessGateService.canEditOrgUnit}, cascade-aware — epic #692
 * Phase 4 / REQ-ORG-016). {@code null} leaves the stamp to the resolver's auto-stamp / pin /
 * ownerless branches.
 *
 * <p>{@code objectivesJson} / {@code stepsJson} carry the create form's optional Ziele / Ablauf
 * rows as a compact JSON array, client-serialized into a hidden input on submit (blank when none).
 * They are used only on the create path — the edit page manages goals and steps through their own
 * AJAX section editors — and the write controller parses them into the backend create request's
 * nested {@code objectives} / {@code steps} lists. Binding them as form fields makes them survive a
 * validation-failure re-render / error re-flash exactly like the other inputs.
 *
 * <p>{@code dirtyCore} / {@code dirtySchedule} / {@code dirtyFlags} drive the dirty-section-aware
 * edit save (#1136, REQ-FE-014): the edit page's JavaScript sets each flag to whether the user
 * actually touched that header section, and {@code applyMissionUpdate} then skips the PATCH for any
 * untouched section. This stops a peer's concurrent schedule bump (the "Jetzt" actual-time stamp or
 * a PLANNED&nbsp;→&nbsp;ACTIVE auto-transition) from 409ing a name-only edit that never touched the
 * schedule, and it never re-writes untouched schedule/flags values. They default to {@code true} in
 * the rendered form so the no-JavaScript classic fallback still saves every section; a {@code null}
 * value (an older cached page, or the create path where the flags are unused) likewise means "save
 * this section".
 */
public record MissionForm(
    @NotBlank(message = "{validation.name.required}") @Size(max = 255) String name,
    @Size(max = 20000) String description,
    @Size(max = 2048)
        @Pattern(regexp = "^(https://.*)?$", message = "{validation.calendarLink.httpsOnly}")
        String calendarLink,
    @NotBlank(message = "{validation.status.required}") String status,
    String meetingTime,
    String plannedStartTime,
    String plannedEndTime,
    String actualStartTime,
    String actualEndTime,
    Boolean isInternal,
    String operationId,
    Long version,
    Long coreVersion,
    Long scheduleVersion,
    Long flagsVersion,
    UUID owningOrgUnitId,
    @Size(max = 200) String meetingPoint,
    @Size(max = 65535) String objectivesJson,
    @Size(max = 65535) String stepsJson,
    Boolean dirtyCore,
    Boolean dirtySchedule,
    Boolean dirtyFlags) {}
