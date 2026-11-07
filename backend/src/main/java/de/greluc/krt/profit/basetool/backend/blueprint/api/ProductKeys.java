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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintNameNormalizer;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Derives the key of a blueprint product (REQ-INV-070, ADR-0343): the lower-case text of the
 * produced item's DataForge GUID, or the normalised output name as the fallback key of a product
 * the P4K import has not given a GUID.
 *
 * <p>Every place that groups recipes into products derives the key here, so the personal
 * blueprints, the default set, the aliases, the material exchange and the exchange feed hold one
 * key.
 */
@Component
@RequiredArgsConstructor
public class ProductKeys {

  /** The canonical lower-case text form of a UUID; a name key never has this shape. */
  private static final Pattern GUID_KEY =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final BlueprintNameNormalizer normalizer;

  /**
   * Returns the key of the product a recipe produces.
   *
   * @param producedItemGuid the recipe's produced-item GUID, or {@code null}
   * @param outputName the recipe's output name, or {@code null}
   * @return the GUID key, else the name key; empty when the recipe has neither
   */
  @NotNull
  public String of(@Nullable UUID producedItemGuid, @Nullable String outputName) {
    return producedItemGuid != null ? guidKey(producedItemGuid) : nameKey(outputName);
  }

  /**
   * Returns the name key of a product name, the key a product without a GUID carries and what a
   * Game.log or SCMDB name is compared by.
   *
   * @param name the raw name, or {@code null}
   * @return the normalised name; empty for a {@code null} or blank name
   */
  @NotNull
  public String nameKey(@Nullable String name) {
    return normalizer.normalize(name);
  }

  /**
   * Returns the key of a product keyed by its produced item's GUID.
   *
   * @param producedItemGuid the produced item's DataForge GUID
   * @return the GUID's lower-case text
   */
  @NotNull
  public static String guidKey(@NotNull UUID producedItemGuid) {
    return producedItemGuid.toString();
  }

  /**
   * Reads the produced-item GUID a key holds.
   *
   * @param productKey a product key, or {@code null}
   * @return the GUID, or {@code null} for a name key or no key
   */
  @Nullable
  public static UUID guidOf(@Nullable String productKey) {
    return productKey != null && GUID_KEY.matcher(productKey).matches()
        ? UUID.fromString(productKey)
        : null;
  }
}
