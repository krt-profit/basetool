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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One wipe exception of a patch change (REQ-PATCH-011): a single game item, or a material in every
 * quality, whose stock is kept although its wipe group is selected, or wiped although it is not.
 *
 * @param target the catalogue the entry lives in
 * @param id the game item's or material's id
 * @param mode whether its stock is kept or wiped
 */
@Embeddable
public record WipeException(
    @NotNull @Enumerated(EnumType.STRING) @Column(name = "target", nullable = false, length = 16)
        WipeExceptionTarget target,
    @NotNull @Column(name = "catalogue_id", nullable = false) UUID id,
    @NotNull @Enumerated(EnumType.STRING) @Column(name = "mode", nullable = false, length = 8)
        WipeExceptionMode mode) {}
