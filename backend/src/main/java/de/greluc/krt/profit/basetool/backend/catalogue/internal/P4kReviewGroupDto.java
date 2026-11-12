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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A group of open review cases.
 *
 * @param id the stable group identifier
 * @param kind the record kind
 * @param reason the review reason
 * @param name the source label used to identify the group
 * @param contextPath the shared source path, if known
 * @param count the number of open members
 * @param representative the first current member
 * @param readiness the next action supported by current source evidence
 * @param batchable whether the group supports a shared decision
 */
public record P4kReviewGroupDto(
    @NotNull UUID id,
    @NotNull P4kKind kind,
    @NotNull P4kReviewReason reason,
    @NotNull String name,
    @Nullable String contextPath,
    int count,
    @NotNull P4kReviewCaseDto representative,
    @NotNull P4kReviewReadiness readiness,
    boolean batchable) {}
