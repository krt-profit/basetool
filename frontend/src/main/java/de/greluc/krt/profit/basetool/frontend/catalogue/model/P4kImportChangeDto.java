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
 * Frontend mirror of the backend {@code P4kImportChangeDto}: one row of a P4K change report
 * (REQ-P4K-014, REQ-P4K-015), of an applied run or a preview.
 *
 * @param kind the kind of the record
 * @param changeType the type of the change
 * @param guid the record's P4K GUID, or {@code null} for a skipped sweep
 * @param fieldName the column, the review reason or the sweep outcome, or {@code null}
 * @param oldValue the value before the run, or {@code null}
 * @param newValue the value the run read, or {@code null}
 * @param className the record's class name, or {@code null}
 * @param nameEn the record's English short name, or {@code null}
 * @param recordPath the record's path in the archive, or {@code null}
 * @param stillLiveElsewhere whether a vanished row is still live in UEX or the SC Wiki
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@DtoMirror
public record P4kImportChangeDto(
    @BackendEnumAsString String kind,
    @BackendEnumAsString String changeType,
    String guid,
    String fieldName,
    String oldValue,
    String newValue,
    String className,
    String nameEn,
    String recordPath,
    boolean stillLiveElsewhere) {}
