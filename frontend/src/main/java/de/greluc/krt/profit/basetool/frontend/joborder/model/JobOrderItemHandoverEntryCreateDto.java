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
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderItemHandoverEntryCreateDto}: one delivered line of
 * an item-handover create payload — how many whole units of a specific ordered item line changed
 * hands. The backend rejects an amount exceeding the line's outstanding (ordered minus delivered)
 * quantity.
 *
 * @param jobOrderItemId the ordered item line being fulfilled
 * @param amount whole-unit count delivered for that line (≥ 1)
 */
@DtoMirror
public record JobOrderItemHandoverEntryCreateDto(UUID jobOrderItemId, Integer amount) {}
