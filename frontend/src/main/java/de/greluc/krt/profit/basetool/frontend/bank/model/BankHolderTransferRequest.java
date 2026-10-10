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

package de.greluc.krt.profit.basetool.frontend.bank.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Write payload for a holder-to-holder Umbuchung (REQ-BANK-031).
 *
 * @param sourceHolderId the player whose stash shrinks
 * @param destinationHolderId the player whose stash grows
 * @param amount whole-aUEC amount
 * @param note free-text note, or {@code null}
 */
@DtoMirror
public record BankHolderTransferRequest(
    UUID sourceHolderId, UUID destinationHolderId, BigDecimal amount, String note) {}
