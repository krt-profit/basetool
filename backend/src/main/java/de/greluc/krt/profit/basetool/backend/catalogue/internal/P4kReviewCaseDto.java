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
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One P4K review case as the admin review list shows it (REQ-P4K-016).
 *
 * @param id the case
 * @param version the case's optimistic-lock version, echoed by a resolution
 * @param kind the kind of the record
 * @param guid the record's P4K GUID
 * @param reason why the record is on the review list
 * @param status whether the case waits for an admin
 * @param className the record's class name as the last run that found the case read it, or {@code
 *     null}
 * @param nameEn the record's English short name as that run read it, or {@code null}
 * @param rowId the Basetool row the case is about, or {@code null}
 * @param rowName that row's name, or {@code null} when there is no row
 * @param otherRowId the row a GUID conflict between two rows collides with, or {@code null}
 * @param firstRunId the run that found the case first
 * @param lastRunId the last run that found it
 * @param createdAt when the case was opened
 * @param resolution the admin's decision, or {@code null} while open
 * @param resolvedAt when it was resolved, or {@code null}
 */
public record P4kReviewCaseDto(
    @NotNull UUID id,
    long version,
    @NotNull P4kKind kind,
    @NotNull UUID guid,
    @NotNull P4kReviewReason reason,
    @NotNull P4kReviewStatus status,
    @Nullable String className,
    @Nullable String nameEn,
    @Nullable UUID rowId,
    @Nullable String rowName,
    @Nullable UUID otherRowId,
    @NotNull UUID firstRunId,
    @NotNull UUID lastRunId,
    @Nullable Instant createdAt,
    @Nullable P4kReviewResolution resolution,
    @Nullable Instant resolvedAt) {}
