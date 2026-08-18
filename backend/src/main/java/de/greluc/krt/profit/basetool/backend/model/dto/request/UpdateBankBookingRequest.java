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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import de.greluc.krt.profit.basetool.backend.validation.WholeNumber;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload for a requester correcting their own still-pending booking request (REQ-BANK-056).
 *
 * <p><strong>Deliberately no {@code sourceAccountId} and no {@code type}.</strong> The source
 * account decides the applicable approval limit, whether a Begr&uuml;ndung is mandatory and whether
 * the caller may request against it at all; the movement kind decides the whole field shape.
 * Changing either makes the row a <em>different</em> request rather than a correction, so the
 * supported move is to cancel and re-raise — one click, already available on the same row. Keeping
 * them out also keeps this endpoint off the eligibility-resolution path, where a mistake would mean
 * an approval-gate bypass rather than a bad edit.
 *
 * <p>Every field is replaced wholesale (PUT semantics, not PATCH): omitting {@code note} clears it.
 * The service re-derives the approval snapshot from the new {@code amount}, so raising it past the
 * requester's limit re-arms the responsible holder's approval instead of riding the original
 * below-limit snapshot.
 *
 * @param amount the corrected whole-aUEC amount, at least 1
 * @param note the corrected free-text note, or {@code null} to clear it
 * @param justification the corrected Begr&uuml;ndung (REQ-BANK-045), or {@code null} to clear it;
 *     still required by the service when the source account type mandates a reason
 * @param targetAccountId the corrected destination for a {@code TRANSFER}; must be {@code null} for
 *     a {@code DEPOSIT} / {@code WITHDRAWAL}, and the type cannot be changed here
 * @param counterpartyUserId the corrected Empf&auml;nger of a {@code WITHDRAWAL} (REQ-BANK-055), or
 *     {@code null} to fall back to deriving the requester at confirmation
 * @param counterpartyOrgUnitId that Empf&auml;nger's org unit, validated against their own
 *     memberships; requires a counterparty user
 * @param version the optimistic-locking version the client echoes back
 */
public record UpdateBankBookingRequest(
    @NotNull @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal amount,
    @Nullable @Size(max = 500) String note,
    @Nullable @Size(max = 500) String justification,
    @Nullable UUID targetAccountId,
    @Nullable UUID counterpartyUserId,
    @Nullable UUID counterpartyOrgUnitId,
    long version) {}
