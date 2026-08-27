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

import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying Operation Create payload.
 *
 * <p>R5.d.e added the trailing {@link #owningOrgUnitId} picker output. For an authenticated caller
 * the service layer routes the stamp through {@code
 * OwnerScopeService.resolveOrgUnitForPickerOutputNullable} — <b>all four</b> org-unit kinds
 * (Staffel, Spezialkommando, Bereich, Organisationsleitung) are accepted; the strict, Staffel-only
 * {@code resolveSquadronForPickerOutput} is not on this path. A non-null pick is honoured when it
 * is one of the caller's DIRECT memberships or an org unit the caller may edit ({@code
 * AccessGateService.canEditOrgUnit}, cascade-aware — epic #692 Phase 4 / REQ-ORG-016), and rejected
 * with 400 otherwise. A {@code null} pick auto-stamps a single-membership caller, honours an
 * active-context pin (REQ-ORG-017), 400s a multi-membership caller with neither, and yields an
 * <em>ownerless leadership operation</em> ({@code owningOrgUnit == null}, V145 / ADR-0005) for a
 * membershipless caller.
 *
 * <p>The legacy "stamp from {@code OwnerScopeService.currentOrgUnit()}" path is <b>not</b> keyed on
 * this field being {@code null} — it applies only when there is no authenticated caller at all (an
 * admin in "alle Staffeln" mode, an anonymous form submit), where a picker output cannot be
 * membership-validated and is therefore ignored. See {@code OperationService#createOperation}.
 */
public record OperationCreateDto(
    @NotBlank String name,
    String description,
    @NotNull OperationStatus status,
    @Nullable UUID owningOrgUnitId) {}
