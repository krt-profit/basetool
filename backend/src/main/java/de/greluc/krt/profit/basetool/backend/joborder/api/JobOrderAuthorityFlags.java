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

package de.greluc.krt.profit.basetool.backend.joborder.api;

/**
 * Caller-specific order authority projected from one current hierarchy snapshot.
 *
 * @param responsibilityInvalid whether processing requires a valid responsibility transfer
 * @param canEdit whether the current responsible-unit writer may process this order
 * @param canTransferResponsibility whether current-unit recovery authority permits transfer
 * @param canEditAsRequester whether the current requesting-unit writer may edit requested need
 */
public record JobOrderAuthorityFlags(
    boolean responsibilityInvalid,
    boolean canEdit,
    boolean canTransferResponsibility,
    boolean canEditAsRequester) {}
