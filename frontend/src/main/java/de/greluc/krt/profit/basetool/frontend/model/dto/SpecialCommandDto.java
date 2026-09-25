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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.UUID;

/**
 * Frontend mirror of the backend {@code SpecialCommandDto}: like {@link SquadronDto} but without
 * {@code isPromotionEnabled}, which is always {@code false} for a Spezialkommando.
 *
 * @param id Spezialkommando identifier; nullable on create payloads.
 * @param name display name.
 * @param shorthand short tag used on chips / badges.
 * @param description free-form text; nullable.
 * @param active soft-delete flag; {@code true} for the active reference data.
 * @param isProfitEligible whether the SK appears in the Job-Order responsible (processing) picker.
 * @param version optimistic-lock counter.
 */
public record SpecialCommandDto(
    UUID id,
    String name,
    String shorthand,
    String description,
    Boolean active,
    Boolean isProfitEligible,
    Long version) {}
