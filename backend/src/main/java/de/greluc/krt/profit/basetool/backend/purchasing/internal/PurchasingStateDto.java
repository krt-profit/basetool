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

/**
 * Whether the purchasing surface is on and what the caller may do in it (REQ-PUR-001); answers
 * while the switch is off, when every other read answers 404.
 *
 * @param enabled whether the purchasing surface is switched on
 * @param delegate whether the caller is an active trade delegate
 * @param governing whether the caller belongs to the governing leadership
 * @param bankStaff whether the caller works in the bank
 * @param managesUnit whether the caller leads or is a Logistician of a unit
 * @param canReadReport whether the caller reads the report
 * @param paused whether purchasing is paused for the whole organisation
 */
public record PurchasingStateDto(
    boolean enabled,
    boolean delegate,
    boolean governing,
    boolean bankStaff,
    boolean managesUnit,
    boolean canReadReport,
    boolean paused) {}
