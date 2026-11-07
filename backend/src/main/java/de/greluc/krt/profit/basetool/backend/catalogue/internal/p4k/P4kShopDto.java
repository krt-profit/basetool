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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One entry of {@code shops} in catalogue version 2 (contract, "Shops"), bound to the fields the
 * import writes; every other field is skipped. Its identity is {@code key}, so the GUID the shared
 * P4K machinery keys it by is a name-based UUID of the key. Its names are names of containers and
 * entities, no game text.
 *
 * @param key the identity: the comma-separated CryGUID chain down to the shop entity
 * @param entityGuid the CryGUID of the shop entity, shared by every placement of a shop module
 * @param system the star system whose registry holds the shop, e.g. {@code stanton}
 * @param entityName the name of the shop entity, e.g. {@code SCShop_CommEx_TDD_NewBabbage}
 * @param registryName the registry's shop name, or {@code null}
 * @param location the location container the starmap link goes by, or {@code null}
 * @param starmapObjectGuid the GUID of the starmap object at the location, or {@code null}
 * @param kind the kind, a closed set read through {@link P4kShopKind}, or {@code null}
 * @param brand the brand folder, or {@code null}
 * @param commodityExchange whether the shop is a commodity exchange or terminal
 * @param inventory the stem of the inventory file, {@code null} while the item list is unknown
 * @param itemGuids the item GUIDs of the inventory file, {@code null} exactly when unknown
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kShopDto(
    @Nullable String key,
    @Nullable String entityGuid,
    @Nullable String system,
    @Nullable String entityName,
    @Nullable String registryName,
    @Nullable String location,
    @Nullable String starmapObjectGuid,
    @Nullable String kind,
    @Nullable String brand,
    @Nullable Boolean commodityExchange,
    @Nullable String inventory,
    @Nullable List<String> itemGuids)
    implements P4kRecord {

  /** Prefixes the key a shop's GUID is derived from. */
  private static final String GUID_PREFIX = "p4k-shop:";

  /**
   * Derives the GUID the P4K machinery keys a shop by.
   *
   * @param key the shop's key
   * @return a name-based UUID of the lower-case key
   */
  @Contract(pure = true)
  @NotNull
  public static UUID guidOf(@NotNull String key) {
    return UUID.nameUUIDFromBytes(
        (GUID_PREFIX + key.strip().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Returns the GUID derived from the key.
   *
   * @return the GUID text, or {@code null} without a key
   */
  @Override
  @Nullable
  public String guid() {
    return key == null || key.isBlank() ? null : guidOf(key).toString();
  }

  /**
   * Returns the entity name, which stands in for a class name.
   *
   * @return the entity name, or {@code null}
   */
  @Override
  @Nullable
  public String className() {
    return entityName;
  }

  /**
   * Returns no record path: a shop is a registry entry, no Record.
   *
   * @return {@code null}
   */
  @Override
  @Nullable
  public String recordPath() {
    return null;
  }

  /**
   * Returns no localised name: a shop carries none.
   *
   * @return {@code null}
   */
  @Override
  @Nullable
  public P4kLocalisedText name() {
    return null;
  }

  /**
   * Returns the registry name, else the entity name, which a report row names the shop by.
   *
   * @return the name, or {@code null}
   */
  @Override
  @Nullable
  public String reportName() {
    return registryName == null || registryName.isBlank() ? entityName : registryName;
  }

  /**
   * Parses {@link #entityGuid()}.
   *
   * @return the GUID, or {@code null} when absent or not a UUID
   */
  @Nullable
  public UUID parsedEntityGuid() {
    return P4kGuids.parse(entityGuid);
  }

  /**
   * Whether the shop's item list is known: the catalogue writes {@code null} for an unknown list,
   * which never means "sells nothing".
   *
   * @return {@code true} when {@link #itemGuids()} is a list, an empty one included
   */
  public boolean hasItemList() {
    return itemGuids != null;
  }
}
