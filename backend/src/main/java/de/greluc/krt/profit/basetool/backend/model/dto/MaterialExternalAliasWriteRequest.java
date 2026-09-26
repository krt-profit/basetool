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

import de.greluc.krt.profit.basetool.backend.validation.DtoConstraints;
import de.greluc.krt.profit.basetool.backend.validation.OnUpdate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create/update payload for {@code /api/v1/material-external-aliases}.
 *
 * <p>Server-managed fields are absent; {@code createdBy} is stamped from the JWT. {@code version}
 * is required only on update, via the {@link OnUpdate} validation group.
 *
 * @param materialId UUID of the local material to link to (required)
 * @param sourceSystem catalogue identifier, one of {@code "UEX"}, {@code "SCWIKI"} or {@code
 *     "REFINERY_SCREEN"}
 * @param externalName the external commodity name (required, max 255 chars)
 * @param externalKey optional external internal key (max 255 chars)
 * @param externalUuid optional external UUID
 * @param externalCode optional external short code (max 64 chars)
 * @param note optional provenance note
 * @param version optimistic-lock version; required on update, ignored on create
 */
public record MaterialExternalAliasWriteRequest(
    @NotNull UUID materialId,
    @NotBlank @Pattern(regexp = DtoConstraints.SOURCE_SYSTEM_REGEX) String sourceSystem,
    @NotBlank @Size(max = DtoConstraints.MAX_NAME) String externalName,
    @Size(max = DtoConstraints.MAX_NAME) String externalKey,
    UUID externalUuid,
    @Size(max = DtoConstraints.MAX_CODE) String externalCode,
    String note,
    @NotNull(groups = OnUpdate.class) Long version) {}
