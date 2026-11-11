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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The proposed provision and captured holder revision.
 *
 * @param amount the whole-aUEC principal
 * @param bankHandling whether Bank handling was selected
 * @param holderVersion the observed holder revision
 * @param holderId the selected existing holder
 * @param holderUserId the explicitly selected account
 * @param holderName the entered accountless holder name
 */
@DtoMirror
public record PreviewJobOrderMoneyProvisionDto(
    @NotBlank String amount,
    @NotNull Boolean bankHandling,
    @Nullable Long holderVersion,
    @Nullable UUID holderId,
    @Nullable UUID holderUserId,
    @Nullable @Size(max = 200) String holderName) {}
