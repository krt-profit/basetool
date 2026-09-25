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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One blueprint entry of an uploaded external blueprint export (SCMDB log-watcher, Basetool
 * Blueprint Extractor or scmdb.net, REQ-INV-014). Unknown fields are ignored.
 *
 * @param productName the crafted product's name; also bound from the scmdb.net {@code name} key
 * @param ts SCMDB acquisition timestamp as fractional Unix epoch seconds, or {@code null}
 * @param receivedAt Blueprint Extractor acquisition timestamp as an ISO-8601 instant string, or
 *     {@code null}
 * @param tag scmdb.net blueprint key, matched case-insensitively against {@code scwiki_key}
 *     (REQ-INV-019), or {@code null}
 * @param completed scmdb.net unlock flag; {@code false} entries are skipped, {@code null} and
 *     {@code true} count as owned
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlueprintExportEntryDto(
    @JsonProperty("productName") @JsonAlias({"name"}) String productName,
    @JsonProperty("ts") Double ts,
    @JsonProperty("receivedAt") String receivedAt,
    @JsonProperty("tag") String tag,
    @JsonProperty("completed") Boolean completed) {}
