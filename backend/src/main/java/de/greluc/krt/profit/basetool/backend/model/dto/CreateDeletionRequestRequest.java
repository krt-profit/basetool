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

/**
 * A member raising an Art. 17 erasure request on their own profile (REQ-SEC-061).
 *
 * <p>Deliberately minimal: no reason field. Art. 17 does not require the data subject to justify
 * the request, and a free-text field here would create one more store of personal data about
 * somebody who is asking to be forgotten.
 *
 * @param eraseHistory whether the member also asks for the handle snapshots that survive a deletion
 *     to be anonymised - both audit trails, the bank booking history, the booking requests and the
 *     two handover recipients. A <em>wish</em>: an admin weighs it against the legitimate interest
 *     in an auditable ledger and decides it deliberately.
 */
public record CreateDeletionRequestRequest(boolean eraseHistory) {}
