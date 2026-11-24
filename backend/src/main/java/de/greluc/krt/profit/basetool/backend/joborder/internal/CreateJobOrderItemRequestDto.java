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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import de.greluc.krt.profit.basetool.backend.joborder.api.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Create payload for an {@code ITEM} job order: finished-item lines whose required materials the
 * server derives from each line's blueprint.
 *
 * @param responsibleOrgUnitId the active Profit-hierarchy org unit that processes the order;
 *     required
 * @param requestingOrgUnitId the customer org unit of any kind; mandatory
 * @param handle optional contact handle (≤ 200 chars)
 * @param comment optional free-text note (≤ 1000 chars), HTML-escaped on display
 * @param items the ordered finished-item lines (1..50)
 * @param observed the observed order and comment versions, read from the flat {@code version} and
 *     {@code commentVersion} fields; both required on update, unused on create
 */
public record CreateJobOrderItemRequestDto(
    @Nullable UUID responsibleOrgUnitId,
    @Nullable UUID requestingOrgUnitId,
    @Size(max = JobOrder.CONTACT_MAX_LENGTH) String handle,
    @Size(max = 1000) String comment,
    @NotEmpty @Size(max = 50) List<@Valid CreateJobOrderItemLineDto> items,
    @JsonUnwrapped @NotNull JobOrderObservedVersions observed) {}
