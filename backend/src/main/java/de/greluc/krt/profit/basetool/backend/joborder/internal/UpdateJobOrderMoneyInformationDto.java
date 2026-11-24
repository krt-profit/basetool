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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jetbrains.annotations.Nullable;

/**
 * Editable contact and comment for an aUEC order.
 *
 * @param handle the current requester contact
 * @param comment the optional order note
 * @param version the observed contact revision
 * @param commentVersion the observed comment revision
 */
public record UpdateJobOrderMoneyInformationDto(
    @NotBlank @Size(max = JobOrder.CONTACT_MAX_LENGTH) String handle,
    @Nullable @Size(max = 1000) String comment,
    @Nullable Long version,
    @Nullable Long commentVersion) {}
