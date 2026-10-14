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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the part of a Lager row that is pledged as a donation (REQ-INV-058).
 *
 * @param donationId the pledged donation
 * @param receivingUnitName the name of the unit the pledge is made to, or {@code null}
 * @param amount the pledged amount earmarked on the row
 */
@DtoMirror
public record DonationAllocationDto(UUID donationId, String receivingUnitName, Double amount) {}
