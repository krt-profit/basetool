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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Frontend mirror of the backend {@code P4kReviewCaseDto}: one case of the P4K review list
 * (REQ-P4K-016).
 *
 * @param id the case id
 * @param version the case version a resolution echoes
 * @param kind the kind of the record
 * @param guid the record's P4K GUID
 * @param reason why the record is on the review list
 * @param status {@code OPEN} or {@code REVIEWED}
 * @param className the record's class name, or {@code null}
 * @param nameEn the record's English short name, or {@code null}
 * @param rowId the Basetool row, or {@code null}
 * @param rowName that row's name, or {@code null}
 * @param otherRowId the row a GUID conflict between two rows collides with, or {@code null}
 * @param firstRunId the run that found the case first
 * @param lastRunId the last run that found it
 * @param createdAt ISO-8601 instant the case was opened
 * @param resolution {@code KEEP}, {@code HIDE} or {@code null} while open
 * @param resolvedAt ISO-8601 instant of the resolution, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@DtoMirror
public record P4kReviewCaseDto(
    String id,
    long version,
    @BackendEnumAsString String kind,
    String guid,
    @BackendEnumAsString String reason,
    @BackendEnumAsString String status,
    String className,
    String nameEn,
    String rowId,
    String rowName,
    String otherRowId,
    String firstRunId,
    String lastRunId,
    String createdAt,
    @BackendEnumAsString String resolution,
    String resolvedAt) {}
