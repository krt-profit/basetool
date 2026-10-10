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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;

/**
 * One material line of a handover report preview.
 *
 * @param materialName the material's display name
 * @param locationName the stock location's name, or {@code null}
 * @param amount the handed-over amount
 * @param quality the line's quality value
 * @param quantityType the material's unit, {@code SCU} or {@code PIECE}, or {@code null}
 */
@DtoMirror
public record HandoverReportItemDto(
    String materialName,
    String locationName,
    Double amount,
    Integer quality,
    String quantityType) {}
