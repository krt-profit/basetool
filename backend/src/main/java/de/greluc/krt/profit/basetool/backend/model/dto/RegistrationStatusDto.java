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

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;

/**
 * The calling user's own approval status (epic #720, Track 1). The frontend reads this once per
 * session to route a {@code PENDING}/{@code REJECTED} user to the "waiting for approval" page
 * instead of into the tool — it read "instead of the guest surface" until ADR-0159 removed it.
 *
 * @param approvalStatus the caller's current approval lifecycle state
 */
public record RegistrationStatusDto(ApprovalStatus approvalStatus) {}
