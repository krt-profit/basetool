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
 * Frontend mirror of the backend {@code P4kImportDueDto}: whether a P4K import is due and the
 * versions that decide it (REQ-P4K-047).
 *
 * @param due whether the "P4K import due" banner shows
 * @param uexVersion the highest UEX game version, or {@code null}
 * @param wikiVersion the SC Wiki game version of the last complete sync, or {@code null}
 * @param basisVersion the patch version of the last APPLY of known version, or {@code null}
 * @param basisOrigin {@code PUBLIC_PATCH}, {@code BRANCH_LOWER_BOUND} or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@DtoMirror
public record P4kImportDueDto(
    boolean due,
    String uexVersion,
    String wikiVersion,
    String basisVersion,
    @BackendEnumAsString String basisOrigin) {}
