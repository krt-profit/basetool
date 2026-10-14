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
 * Frontend mirror of the backend {@code PartnerDto}; must match it field for field.
 *
 * @param id the partner
 * @param kind organisation or individual
 * @param name the name or handle
 * @param orgTag the tag, or {@code null}
 * @param channel the contact channel
 * @param conditionsNote the conditions note
 * @param reputationNote the reputation note
 * @param contactVia how the contact came about
 * @param status active or blocked
 * @param erased whether the handle was erased
 * @param dealCount deals with the partner
 * @param settledClean deals settled without loss
 * @param version the optimistic-locking version
 */
@DtoMirror
public record PartnerDto(
    UUID id,
    @BackendEnumAsString String kind,
    String name,
    String orgTag,
    @BackendEnumAsString String channel,
    String conditionsNote,
    String reputationNote,
    @BackendEnumAsString String contactVia,
    @BackendEnumAsString String status,
    boolean erased,
    long dealCount,
    long settledClean,
    Long version) {}
