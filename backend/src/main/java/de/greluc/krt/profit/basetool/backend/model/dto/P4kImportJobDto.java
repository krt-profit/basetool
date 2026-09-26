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

package de.greluc.krt.profit.basetool.backend.model.dto;

import de.greluc.krt.profit.basetool.backend.model.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One asynchronous P4K catalog-import run, as polled by the admin page.
 *
 * @param id the job id
 * @param kind whether the run previews or applies the catalog
 * @param status the lifecycle status
 * @param seedNew for an APPLY run, whether new unmatched rows are seeded
 * @param sourceFilename original upload filename, or {@code null}
 * @param fileSizeBytes size of the uploaded catalog in bytes, or {@code null}
 * @param previewJobId for an APPLY run, the preview it was launched from, else {@code null}
 * @param result the per-type result once {@code SUCCEEDED}, else {@code null}
 * @param errorMessage the failure reason once {@code FAILED}, else {@code null}
 * @param createdAt when the run was enqueued
 * @param startedAt when the worker began, or {@code null}
 * @param finishedAt when the worker finished, or {@code null}
 */
public record P4kImportJobDto(
    UUID id,
    P4kImportJobKind kind,
    P4kImportJobStatus status,
    boolean seedNew,
    String sourceFilename,
    Long fileSizeBytes,
    UUID previewJobId,
    P4kImportResultDto result,
    String errorMessage,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {}
