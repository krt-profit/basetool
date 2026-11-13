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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A review proposal and the catalogue facts used to verify it.
 *
 * @param action the action the administrator may confirm
 * @param targetId the local target
 * @param targetVersion the version of the target, if versioned
 * @param subjectVersion the version of the other row the action writes, if versioned
 * @param name the target's catalogue name
 * @param className the target's engine class or source code, if available
 * @param p4kGuid the target's P4K identity, if known
 * @param scwikiUuid the target's SC Wiki identity, if known
 * @param uexIds the target's UEX identifiers
 * @param evidence the facts supporting or qualifying the proposal
 */
public record P4kReviewCandidate(
    @NotNull P4kReviewAction action,
    @NotNull UUID targetId,
    @Nullable Long targetVersion,
    @Nullable Long subjectVersion,
    @NotNull String name,
    @Nullable String className,
    @Nullable UUID p4kGuid,
    @Nullable UUID scwikiUuid,
    @NotNull List<Integer> uexIds,
    @NotNull List<P4kReviewEvidence> evidence) {}
