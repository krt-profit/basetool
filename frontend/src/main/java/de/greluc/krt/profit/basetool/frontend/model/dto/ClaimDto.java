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

import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code ClaimDto}: one squadron's material claim ("Eintragung") on
 * a public SK order.
 *
 * @param id claim primary key
 * @param claimingOrgUnit the squadron that signed up (slim reference)
 * @param amount the claimed partial quantity
 * @param claimedByUserId id of the user who last touched the claim
 * @param claimedAt creation instant (UTC)
 * @param version optimistic-lock version
 */
public record ClaimDto(
    UUID id,
    SquadronReferenceDto claimingOrgUnit,
    Double amount,
    UUID claimedByUserId,
    Instant claimedAt,
    Long version) {}
