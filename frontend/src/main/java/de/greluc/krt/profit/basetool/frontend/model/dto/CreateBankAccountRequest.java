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

import java.util.UUID;

/**
 * Write payload for creating a bank account (REQ-BANK-030).
 *
 * @param name display name of the new account
 * @param type the account type constant
 * @param orgUnitId owning org unit, or {@code null}
 * @param areaName free-form Bereich name of an {@code AREA} account without an org unit, or {@code
 *     null}
 */
public record CreateBankAccountRequest(
    String name, @BackendEnumAsString String type, UUID orgUnitId, String areaName) {}
