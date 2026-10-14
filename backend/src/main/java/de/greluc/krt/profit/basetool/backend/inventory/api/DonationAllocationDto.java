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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The part of a Lager row that is pledged as a donation and cannot be sold, moved or pledged again
 * until the pledge is settled (REQ-INV-058).
 *
 * @param donationId the pledged donation
 * @param receivingUnitName the name of the unit the pledge is made to, or {@code null} when unknown
 * @param amount the pledged amount earmarked on the row
 */
public record DonationAllocationDto(
    UUID donationId, @Nullable String receivingUnitName, Double amount) {}
