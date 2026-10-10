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

import java.util.UUID;

/**
 * Frontend mirror of one individually granted account viewer (REQ-BANK-035) shown in the org-unit
 * account settings modal.
 *
 * @param userId the granted user's id (echoed back to revoke the grant)
 * @param displayName the user's effective display name (handle)
 */
public record OrgUnitBankViewUserDto(UUID userId, String displayName) {}
