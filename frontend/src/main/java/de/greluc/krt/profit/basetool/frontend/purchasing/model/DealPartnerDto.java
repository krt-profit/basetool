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

package de.greluc.krt.profit.basetool.frontend.purchasing.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code DealPartnerDto}; must match it field for field.
 *
 * @param id the partner
 * @param kind organisation or individual
 * @param name the name or handle
 * @param orgTag the tag, or {@code null}
 * @param status whether the partner may still be used
 * @param conditionsNote the conditions note, or {@code null} when not readable
 * @param reputationNote the reputation note, or {@code null} when not readable
 */
@DtoMirror
public record DealPartnerDto(
    UUID id,
    @BackendEnumAsString String kind,
    String name,
    String orgTag,
    @BackendEnumAsString String status,
    String conditionsNote,
    String reputationNote) {}
