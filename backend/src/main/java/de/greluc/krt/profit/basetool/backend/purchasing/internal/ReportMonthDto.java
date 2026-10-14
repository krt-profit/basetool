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
 * One month of a purchasing report, by the month the deal was created.
 *
 * @param month the month as {@code yyyy-MM}
 * @param dealCount deals created in the month
 * @param boughtAmount the amount of goods accepted
 * @param funded the money the bank confirmed, in whole aUEC
 * @param returned the remainders settled back
 * @param spent funded minus returned
 * @param loss the money lost
 */
public record ReportMonthDto(
    String month,
    int dealCount,
    double boughtAmount,
    long funded,
    long returned,
    long spent,
    long loss) {}
