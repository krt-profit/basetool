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
 * An explicit review action with the versions displayed in its preview.
 *
 * @param action the chosen operation
 * @param targetId the selected catalogue target
 * @param targetVersion the version of the target seen in the preview
 * @param subjectVersion the version of the catalogue row being changed
 * @param version the version of the review case
 */
@DtoMirror
public record P4kReviewApplyRequest(
    @NotNull @BackendEnumAsString String action,
    @NotNull UUID targetId,
    @Nullable Long targetVersion,
    @Nullable Long subjectVersion,
    @NotNull Long version) {}
