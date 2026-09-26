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

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload for the admin {@code POST /api/v1/materials} create flow.
 *
 * <p>UEX-imported fields are absent and stay {@code null} until a UEX sync fills them; the server
 * stamps {@code sourceSystems=MANUAL}.
 *
 * @param name unique material name, also matched by the UEX sync's name fallback.
 * @param type material classification ({@code RAW}, {@code REFINED}, {@code NO_REFINE}).
 * @param quantityType inventory quantity unit ({@code SCU} or {@code PIECE}).
 * @param description optional free-text note.
 * @param refinedMaterialId optional refined output material; only allowed when {@code type=RAW} or
 *     {@code isManualRawMaterial=true}.
 * @param categoryId optional FK to {@code MaterialCategory}.
 * @param isManualRawMaterial makes the material selectable as refinery input regardless of its UEX
 *     classification.
 * @param isJobOrder marks the material as a job-order picker entry.
 * @param isIllegal warning flag (illegal cargo).
 * @param isVolatileQt warning flag (volatile under Quantum Travel).
 * @param isVolatileTime warning flag (decays over time).
 */
public record MaterialCreateDto(
    @NotBlank @Size(max = 255) String name,
    @NotNull String type,
    @NotNull String quantityType,
    @Size(max = 4000) String description,
    UUID refinedMaterialId,
    UUID categoryId,
    @JsonProperty("isManualRawMaterial") Boolean isManualRawMaterial,
    @JsonProperty("isJobOrder") Boolean isJobOrder,
    @JsonProperty("isIllegal") Boolean isIllegal,
    @JsonProperty("isVolatileQt") Boolean isVolatileQt,
    @JsonProperty("isVolatileTime") Boolean isVolatileTime) {}
