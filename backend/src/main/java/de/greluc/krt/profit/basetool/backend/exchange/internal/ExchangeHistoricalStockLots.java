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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Resolves the journal's physical lot identity through the current catalogue and locations. */
@Component
@RequiredArgsConstructor
final class ExchangeHistoricalStockLots {
  private static final Pattern LOT_KEY_SEPARATOR = Pattern.compile("\\|");

  private final MaterialRepository materials;
  private final GameItemRepository items;
  private final LocationRepository locations;

  /** Resolves all current catalogue/location references in bounded batches without row names. */
  Map<String, Optional<StockLot>> resolve(@NotNull Collection<String> lotKeys) {
    Map<String, Optional<Key>> parsed = new LinkedHashMap<>();
    lotKeys.forEach(key -> parsed.put(key, parse(key)));
    List<Key> valid = parsed.values().stream().flatMap(Optional::stream).toList();
    Map<UUID, Material> materialFacts =
        materials
            .findAllById(
                valid.stream()
                    .map(Key::material)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(Material::getId, Function.identity()));
    Map<UUID, GameItem> itemFacts =
        items
            .findAllById(
                valid.stream()
                    .map(Key::gameItem)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(GameItem::getId, Function.identity()));
    Map<UUID, Location> locationFacts =
        locations.findAllById(valid.stream().map(Key::location).distinct().toList()).stream()
            .collect(Collectors.toMap(Location::getId, Function.identity()));
    Map<String, Optional<StockLot>> result = new LinkedHashMap<>();
    parsed.forEach((lot, key) ->
        result.put(
            lot,
            key.flatMap(value -> {
              Location location = locationFacts.get(value.location());
              Material material = materialFacts.get(value.material());
              GameItem gameItem = itemFacts.get(value.gameItem());
              return location == null || (material == null && gameItem == null)
                  ? Optional.empty()
                  : Optional.of(
                      new StockLot(material, gameItem, location, value.quality(), value.stolen()));
            })));
    return Map.copyOf(result);
  }

  /** Parses only the stored physical identity without inventing missing catalogue references. */
  private static Optional<Key> parse(@NotNull String lotKey) {
    List<String> parts = LOT_KEY_SEPARATOR.splitAsStream(lotKey).toList();
    if (parts.size() != 4
        || !(parts.get(0).startsWith("m:") || parts.get(0).startsWith("i:"))
        || !parts.get(1).startsWith("l:")
        || !parts.get(2).startsWith("q:")
        || !parts.get(3).startsWith("s:")) {
      return Optional.empty();
    }
    try {
      UUID catalogueId = UUID.fromString(parts.get(0).substring(2));
      UUID location = UUID.fromString(parts.get(1).substring(2));
      int quality = Integer.parseInt(parts.get(2).substring(2));
      boolean stolen = "1".equals(parts.get(3).substring(2));
      if (parts.get(0).startsWith("m:")) {
        return Optional.of(new Key(catalogueId, null, location, quality, stolen));
      }
      if (parts.get(0).startsWith("i:")) {
        return Optional.of(new Key(null, catalogueId, location, null, stolen));
      }
      return Optional.empty();
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  /** Parsed material/item identity with its actual location, quality and stolen marker. */
  private record Key(
      @Nullable UUID material,
      @Nullable UUID gameItem,
      UUID location,
      @Nullable Integer quality,
      boolean stolen) {}
}
