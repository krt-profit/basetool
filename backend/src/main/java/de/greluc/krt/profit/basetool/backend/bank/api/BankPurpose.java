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

package de.greluc.krt.profit.basetool.backend.bank.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The opaque reference a purpose-bound booking request carries (REQ-BANK-061).
 *
 * @param kind what the request is for, which also names the observer that hears of its outcome
 * @param ref the owner's id for the thing the request pays for, never read by the bank
 */
public record BankPurpose(@NotNull BankPurposeKind kind, @NotNull UUID ref) {}
