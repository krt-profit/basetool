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
import java.time.Instant;
import java.util.List;

/**
 * Frontend mirror of the backend {@code JobOrderItemHandoverCreateDto}: the create payload the
 * frontend sends when a logistician hands over produced items from an item order. Itemises the
 * delivered ordered-item lines (one {@link JobOrderItemHandoverEntryCreateDto} per line).
 *
 * @param handoverTime when the handover occurred (UTC)
 * @param recipientHandle the recipient's handle
 * @param entries the delivered item-line quantities (at least one)
 */
@DtoMirror
public record JobOrderItemHandoverCreateDto(
    Instant handoverTime,
    String recipientHandle,
    List<JobOrderItemHandoverEntryCreateDto> entries) {}
