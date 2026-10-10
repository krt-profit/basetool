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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A client's blueprints to stage for review in the browser, the {@code basetool.blueprints}
 * envelope the ingest gateway relays after checking it against {@code blueprint-draft.schema.json}
 * (REQ-XCH-019).
 *
 * @param format the envelope's format, always {@code basetool.blueprints}
 * @param formatVersion the envelope's format version, any {@code 1.x}, or {@code null} when absent
 * @param items the blueprints, at most {@value #MAX_ITEMS}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExchangeBlueprintDraftDto(
    @NotNull @Pattern(regexp = "^basetool\\.blueprints$") String format,
    @Nullable @Pattern(regexp = SUPPORTED_FORMAT_VERSION) String formatVersion,
    @NotNull @Size(max = MAX_ITEMS) List<@NotNull @Valid Item> items) {

  /** The format versions the Basetool reads: major {@code 1}, any minor (REQ-XCH-019). */
  public static final String SUPPORTED_FORMAT_VERSION = "^1\\.[0-9]+$";

  /** The most blueprints one draft may carry. */
  public static final int MAX_ITEMS = 2000;

  /**
   * One blueprint of the draft.
   *
   * @param ref the product
   * @param acquiredAt when the member acquired it, or {@code null}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Item(@NotNull @Valid ExchangeItemRef ref, @Nullable Instant acquiredAt) {}
}
