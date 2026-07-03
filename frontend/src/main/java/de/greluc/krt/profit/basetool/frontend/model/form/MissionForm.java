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
 * service validates it via {@code OwnerScopeService.resolveSquadronForPickerOutput} and rejects
 * Spezialkommando selections with 400 until the destructive cleanup release loosens NOT NULL on the
 * legacy {@code owning_squadron_id} column. {@code null} preserves the legacy stamping path.
 *
 * <p>{@code objectivesJson} / {@code stepsJson} carry the create form's optional Ziele / Ablauf
 * rows as a compact JSON array, client-serialized into a hidden input on submit (blank when none).
 * They are used only on the create path — the edit page manages goals and steps through their own
 * AJAX section editors — and the write controller parses them into the backend create request's
 * nested {@code objectives} / {@code steps} lists. Binding them as form fields makes them survive a
 * validation-failure re-render / error re-flash exactly like the other inputs.
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
    @Size(max = 65535) String stepsJson) {}
