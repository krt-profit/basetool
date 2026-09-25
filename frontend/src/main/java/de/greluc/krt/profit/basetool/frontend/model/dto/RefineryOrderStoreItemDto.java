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

import java.util.UUID;

/**
 * Frontend mirror of one refinery-order output to store in the Lager.
 *
 * <p>{@code owningOrgUnitId} is the picked owning OrgUnit, or {@code null} for the backend to stamp
 * it. {@code personal} books the output as the receiver's private stock (REQ-INV-035) and cannot be
 * combined with {@code jobOrderId}.
 */
public record RefineryOrderStoreItemDto(
    UUID materialId,
    UUID locationId,
    Integer quality,
    Double amount,
    UUID userId,
    UUID jobOrderId,
    String note,
    UUID owningOrgUnitId,
    Boolean personal) {}
