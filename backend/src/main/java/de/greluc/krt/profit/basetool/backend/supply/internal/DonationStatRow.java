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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import java.util.UUID;

/**
 * One row of the donation statistics: the received donations of a receiving unit in a calendar
 * month (REQ-SUP-013).
 *
 * @param unitId the receiving unit
 * @param year the calendar year
 * @param month the calendar month, 1 to 12
 * @param donations the number of received donations
 * @param distinctDonors the number of distinct identified donors
 */
public record DonationStatRow(
    UUID unitId, Integer year, Integer month, Long donations, Long distinctDonors) {}
