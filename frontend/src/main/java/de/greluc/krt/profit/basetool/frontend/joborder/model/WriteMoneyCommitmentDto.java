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
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A unit's planned outstanding amount and the occurrence the caller last saw.
 *
 * @param amount the amount the unit still plans to provide
 * @param commitmentId the current occurrence id when revising an existing commitment
 * @param version the observed revision when revising an existing commitment
 */
@DtoMirror
public record WriteMoneyCommitmentDto(
    @NotBlank String amount, @Nullable UUID commitmentId, @Nullable Long version) {}
