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
import de.greluc.krt.profit.basetool.frontend.model.GameItemReferenceDto;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderItemHandoverEntryDto}: one delivered line of an
 * item handover.
 *
 * @param id the handover-entry id
 * @param jobOrderItemId the ordered item line this entry fulfilled
 * @param gameItem the produced item (slim reference)
 * @param amount whole-unit count delivered in this entry
 */
@DtoMirror
public record JobOrderItemHandoverEntryDto(
    UUID id, UUID jobOrderItemId, GameItemReferenceDto gameItem, Integer amount) {}
