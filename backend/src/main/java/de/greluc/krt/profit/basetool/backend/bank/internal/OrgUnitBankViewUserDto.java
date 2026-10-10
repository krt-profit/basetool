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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import java.util.UUID;

/**
 * One individually granted viewer of a bank account (REQ-BANK-035) — the {@code USER} view grants
 * shown in the org-unit account settings, with the user's display handle resolved for rendering.
 *
 * @param userId the granted user's id (the value echoed back to revoke the grant)
 * @param displayName the user's effective display name (handle) for the settings list
 */
public record OrgUnitBankViewUserDto(UUID userId, String displayName) {}
