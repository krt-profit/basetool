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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * An admin's resolution of a P4K review case (REQ-P4K-016), relayed to the backend.
 *
 * @param resolution {@code KEEP}, {@code HIDE}, or {@code ASSIGN} for a missing resource group or a
 *     resource that joins no single commodity
 * @param categoryId the resource group an {@code ASSIGN} of a missing group gives the material,
 *     else {@code null}
 * @param materialId the material an {@code ASSIGN} of a resource gives its recipe lines, else
 *     {@code null}
 * @param version the case version the admin saw
 */
@DtoMirror
public record P4kReviewResolveRequest(
    @NotNull @BackendEnumAsString String resolution,
    @Nullable UUID categoryId,
    @Nullable UUID materialId,
    @NotNull Long version) {}
