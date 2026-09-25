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

package de.greluc.krt.profit.basetool.backend.model.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPQL projection of one posting reduced to the columns the bank dashboard needs to derive deltas,
 * totals and sparklines in memory (REQ-BANK-016).
 *
 * @param accountId the account the posting belongs to
 * @param createdAt the booking instant (UTC), used for the daily bucketing
 * @param amount the signed posting amount
 */
public record BankPostingSlice(UUID accountId, Instant createdAt, BigDecimal amount) {}
