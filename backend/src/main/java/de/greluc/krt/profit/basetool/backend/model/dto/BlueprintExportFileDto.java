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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Root of an uploaded external blueprint export; only the {@code blueprints} array is read and
 * every other top-level field is ignored.
 *
 * @param blueprints the acquired-blueprint entries; {@code null} if the key is absent
 * @param additionalSourceFolders extra game-channel folders the Blueprint Extractor scanned, or
 *     {@code null}; provenance only, not consumed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlueprintExportFileDto(
    @JsonProperty("blueprints") List<BlueprintExportEntryDto> blueprints,
    @JsonProperty("additionalSourceFolders") List<String> additionalSourceFolders) {}
