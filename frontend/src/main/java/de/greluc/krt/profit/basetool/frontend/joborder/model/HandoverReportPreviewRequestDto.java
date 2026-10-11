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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The body of {@code POST /api/v1/orders/{jobOrderId}/handovers/report/preview}: unsaved handover
 * data rendered as a preview PDF.
 *
 * @param jobOrderNumber the order number printed on the report
 * @param handoverTime the handover time as the user typed it, rendered unchanged
 * @param recipientHandle the recipient's handle
 * @param items the handed-over material lines
 */
@DtoMirror
public record HandoverReportPreviewRequestDto(
    String jobOrderNumber,
    LocalDateTime handoverTime,
    String recipientHandle,
    List<HandoverReportItemDto> items) {}
