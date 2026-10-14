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
 * The totals of a purchasing report over its whole period (REQ-PUR-023).
 *
 * @param dealCount deals that reached the bank, cancelled drafts excluded
 * @param settledCount deals settled
 * @param failedCount deals whose delivery failed
 * @param overdueCount deals whose settlement is overdue now
 * @param boughtAmount the amount of goods accepted
 * @param funded the money the bank confirmed, in whole aUEC
 * @param returned the remainders settled back to the funding account
 * @param spent the money funded minus the remainders returned
 * @param loss the money lost on deals
 */
public record ReportTotalsDto(
    int dealCount,
    int settledCount,
    int failedCount,
    int overdueCount,
    double boughtAmount,
    long funded,
    long returned,
    long spent,
    long loss) {}
