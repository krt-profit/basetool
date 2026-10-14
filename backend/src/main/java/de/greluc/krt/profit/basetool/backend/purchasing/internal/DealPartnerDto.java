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

import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The partner of a deal as every reader of the deal sees it (REQ-PUR-021): the notes are present
 * only for the delegates and the governing leadership.
 *
 * @param id the partner
 * @param kind organisation or individual
 * @param name the organisation's name or the individual's handle
 * @param orgTag the organisation's tag, or {@code null}
 * @param status whether the partner may still be used
 * @param conditionsNote the conditions note, or {@code null} when absent or not readable
 * @param reputationNote the reputation note, or {@code null} when absent or not readable
 */
public record DealPartnerDto(
    UUID id,
    PartnerKind kind,
    String name,
    @Nullable String orgTag,
    PartnerStatus status,
    @Nullable String conditionsNote,
    @Nullable String reputationNote) {}
