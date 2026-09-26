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

import java.util.UUID;

/**
 * Wire shape of a squadron.
 *
 * @param id squadron identifier; nullable on create-request payloads
 * @param name display name (case-insensitive unique)
 * @param shorthand short tag used on badges / column headers
 * @param description free-form text
 * @param active soft-delete flag; {@code true} for the active reference data
 * @param isPromotionEnabled whether the promotion subsystem is on for this squadron; changed only
 *     through {@code /api/v1/squadrons/{id}/promotion-enabled}
 * @param isProfitEligible whether the squadron may process job orders; changed only through {@code
 *     /api/v1/squadrons/{id}/profit-eligible}
 * @param version optimistic-lock counter
 */
public record SquadronDto(
    UUID id,
    String name,
    String shorthand,
    String description,
    Boolean active,
    Boolean isPromotionEnabled,
    Boolean isProfitEligible,
    Long version) {}
