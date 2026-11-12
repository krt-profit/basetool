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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A catalogue proposal that an administrator inspected and confirmed.
 *
 * @param action the selected action
 * @param targetId the selected target
 * @param targetVersion the target version in the proposal, if versioned
 * @param subjectVersion the subject version in the proposal, if versioned
 * @param version the review case version
 */
public record P4kReviewApplyRequest(
    @NotNull P4kReviewAction action,
    @NotNull UUID targetId,
    @Nullable Long targetVersion,
    @Nullable Long subjectVersion,
    @NotNull Long version) {}
