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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A partner as the partner list and the deal form show it (REQ-PUR-004, REQ-PUR-021). The notes are
 * returned only to the delegates and the governing leadership; the history is computed from the
 * deals.
 *
 * @param id the partner
 * @param kind organisation or individual
 * @param name the organisation's name or the individual's handle
 * @param orgTag the organisation's tag, or {@code null}
 * @param channel how the partner is reached
 * @param conditionsNote the conditions note, or {@code null} when absent or not readable
 * @param reputationNote the reputation note, or {@code null} when absent or not readable
 * @param contactVia how the contact was made
 * @param status whether the partner may be used on a new deal
 * @param erased whether an individual's handle and notes were erased
 * @param dealCount the number of deals with the partner
 * @param settledClean the number of settled deals without any loss
 * @param version the optimistic-locking version to echo
 */
public record PartnerDto(
    UUID id,
    PartnerKind kind,
    String name,
    @Nullable String orgTag,
    PartnerChannel channel,
    @Nullable String conditionsNote,
    @Nullable String reputationNote,
    PartnerContact contactVia,
    PartnerStatus status,
    boolean erased,
    long dealCount,
    long settledClean,
    Long version) {}
