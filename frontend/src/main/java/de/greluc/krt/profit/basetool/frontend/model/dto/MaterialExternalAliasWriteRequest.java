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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.UUID;

/**
 * Frontend mirror of the backend {@code MaterialExternalAliasWriteRequest}, the create/update
 * payload for an external material alias.
 *
 * @param materialId UUID of the local material to link to
 * @param sourceSystem catalogue identifier ({@code "UEX"}, {@code "SCWIKI"} or {@code
 *     "REFINERY_SCREEN"})
 * @param externalName commodity name in the external catalogue
 * @param externalKey optional external internal key
 * @param externalUuid optional external UUID
 * @param externalCode optional external short code
 * @param note free-form provenance note
 * @param version optimistic-lock token; {@code null} on create, from the GET response on update
 */
public record MaterialExternalAliasWriteRequest(
    UUID materialId,
    String sourceSystem,
    String externalName,
    String externalKey,
    UUID externalUuid,
    String externalCode,
    String note,
    Long version) {}
