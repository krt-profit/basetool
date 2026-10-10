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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * One reference to resolve, in the shape of the published {@code item-ref.schema.json}; the server
 * tries its fields in the order {@code bt}, {@code scRecord}, {@code scGuid}, {@code uexId}, {@code
 * locKey}, {@code name} (REQ-XCH-012).
 *
 * @param bt the Basetool's own key, or {@code null}
 * @param scRecord the DataForge record name, compared case-insensitively, or {@code null}
 * @param scGuid the game entity GUID, or {@code null}
 * @param uexId the UEX id, or {@code null}
 * @param locKey the name key from the game's {@code global.ini}, or {@code null}
 * @param name the display name as the game shows it, or {@code null}
 * @param nameLocale the language of {@code name}, or {@code null} for English
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExchangeItemRef(
    @Size(min = 1, max = 128) String bt,
    @Size(min = 1, max = 200) String scRecord,
    UUID scGuid,
    @Positive Integer uexId,
    @Size(min = 1, max = 200) @Pattern(regexp = "^[^@\\s]+$") String locKey,
    @Size(min = 1, max = 200) String name,
    @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") String nameLocale) {}
