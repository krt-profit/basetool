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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One squadron's material claim ("Eintragung") on a public SK order, as exposed at the API
 * boundary. The owning bucket (material + quality) is carried by the enclosing {@link
 * ClaimBucketDto}; this record only adds the per-squadron facts.
 *
 * @param id claim primary key
 * @param claimingOrgUnit the squadron that signed up (slim reference for the badge)
 * @param amount the claimed partial quantity in the material's own unit
 * @param claimedByUserId id of the user who last created/updated the claim (audit; never a name, so
 *     no PII is exposed)
 * @param claimedAt creation instant (UTC)
 * @param version optimistic-lock version (echoed back on update/withdraw)
 */
public record ClaimDto(
    UUID id,
    @Nullable SquadronReferenceDto claimingOrgUnit,
    Double amount,
    @Nullable UUID claimedByUserId,
    Instant claimedAt,
    Long version) {}
