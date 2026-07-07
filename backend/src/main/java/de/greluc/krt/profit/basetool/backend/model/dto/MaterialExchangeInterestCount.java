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
 * A grouped interest-count projection — one row per offer carrying how many members have registered
 * interest. Populated by a JPQL constructor expression so the board list can attach the "N
 * Interessenten" count to every offer in a single grouped query instead of one count per offer (no
 * N+1). Carries only the offer id and the count — never any interessent identity, honouring the
 * owner-only-names anonymity rule (REQ-MARKET-006).
 *
 * @param offerId the offer the count belongs to.
 * @param count the number of members who have registered interest on that offer.
 */
public record MaterialExchangeInterestCount(UUID offerId, Long count) {}
