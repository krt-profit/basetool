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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One entry of {@code blueprints} in catalogue version 2, bound to the fields the import writes or
 * matches on; every other field is skipped.
 *
 * @param guid the Record GUID
 * @param className the Record name
 * @param recordPath the record path without {@code .xml}
 * @param key the blueprint key, e.g. {@code BP_CRAFT_cds_armor_heavy_arms_01_01_01}
 * @param name the localised name
 * @param producedItemGuid the GUID of the item the blueprint produces, or {@code null}
 * @param craftTimeSeconds the craft time of the default tier in seconds, or {@code null}
 * @param ingredients the cost lines of the default tier, {@code null} when the file has none
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kBlueprintDto(
    @Nullable String guid,
    @Nullable String className,
    @Nullable String recordPath,
    @Nullable String key,
    @Nullable P4kLocalisedText name,
    @Nullable String producedItemGuid,
    @Nullable Integer craftTimeSeconds,
    @Nullable List<Ingredient> ingredients)
    implements P4kRecord {

  /**
   * One cost line of a blueprint, bound to the commodity join (REQ-P4K-026).
   *
   * @param resourceGuid the {@code ResourceType} of a resource line, joined to a commodity's {@code
   *     subtypeGuid}; {@code null} on an item line
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Ingredient(@Nullable String resourceGuid) {}
}
