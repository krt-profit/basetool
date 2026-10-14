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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import java.util.UUID;

/**
 * A bank account an admin may choose as the funding account of purchasing (REQ-PUR-002).
 *
 * @param id the account
 * @param accountNo the human-readable account number
 * @param name the display name
 * @param type the account type, {@code CARTEL} or {@code SPECIAL}
 */
public record FundingAccountOptionDto(UUID id, String accountNo, String name, String type) {}
