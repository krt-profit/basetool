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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Request DTO for generating a handover report PDF preview (before the handover is persisted).
 *
 * <p>{@code handoverTime} is intentionally a {@link LocalDateTime} (not {@link java.time.Instant}):
 * it represents exactly what the user typed into the modal in their local time zone, and the PDF
 * preview must show that same value back to the user without any time-zone round-trip. Using {@link
 * LocalDateTime} avoids the bug where a server-side {@code ZoneId.systemDefault()} would shift the
 * displayed time relative to the user's actual time zone.
 */
public record HandoverReportPreviewRequestDto(
    @NotBlank String jobOrderNumber,
    @NotNull LocalDateTime handoverTime,
    @NotBlank String recipientHandle,
    @NotNull List<@Valid HandoverReportItemDto> items) {
  /** Represents a single material item in the handover report preview. */
  public record HandoverReportItemDto(
      @NotBlank String materialName,
      String locationName,
      @NotNull Double amount,
      @NotNull Integer quality,
      String quantityType) {}
}
