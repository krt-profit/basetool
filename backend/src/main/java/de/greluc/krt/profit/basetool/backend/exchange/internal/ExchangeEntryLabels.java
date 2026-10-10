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

import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Names journaled exchange entries for the member — a blueprint, a material or item, a ship type —
 * with one lookup per catalogue however many entries there are (REQ-XCH-022, REQ-XCH-032).
 */
@Component
@RequiredArgsConstructor
public class ExchangeEntryLabels {

  /** The length of a lot key's catalogue prefix and id, {@code m:<uuid>} or {@code i:<uuid>}. */
  private static final int LOT_HEAD = 38;

  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final ShipTypeRepository shipTypeRepository;
  private final ShipRepository shipRepository;
  private final ObjectMapper objectMapper;

  /**
   * Names each entry by the state after the write, or before it when the write removed the entry.
   *
   * @param entries the journal entries
   * @return the name by entry id; an entry whose name is no longer known is left out
   */
  public @NotNull Map<UUID, String> label(@NotNull Collection<ExchangeJournalEntry> entries) {
    Map<UUID, UUID> materialOf = new HashMap<>();
    Map<UUID, UUID> itemOf = new HashMap<>();
    Map<UUID, UUID> shipTypeOf = new HashMap<>();
    Map<UUID, UUID> shipOf = new HashMap<>();
    Map<UUID, String> labels = new HashMap<>();
    for (ExchangeJournalEntry entry : entries) {
      JsonNode after = parse(entry.getAfterState());
      JsonNode state = after != null ? after : parse(entry.getBeforeState());
      switch (entry.getResource()) {
        case BLUEPRINT -> {
          String name = text(state, "productName");
          if (name != null) {
            labels.put(entry.getId(), name);
          }
        }
        case STOCK -> {
          UUID id = lotCatalogueId(entry.getEntityKey());
          if (id != null) {
            (entry.getEntityKey().startsWith("m:") ? materialOf : itemOf).put(entry.getId(), id);
          }
        }
        case SHIP -> {
          UUID type = uuid(text(state, "shipType"));
          if (type != null) {
            shipTypeOf.put(entry.getId(), type);
          } else {
            UUID ship = uuid(entry.getEntityKey());
            if (ship != null) {
              shipOf.put(entry.getId(), ship);
            }
          }
        }
        case null -> throw new NullPointerException("resource");
      }
    }
    Map<UUID, String> materials = new HashMap<>();
    materialRepository
        .findAllById(new HashSet<>(materialOf.values()))
        .forEach(m -> materials.put(m.getId(), m.getName()));
    Map<UUID, String> items = new HashMap<>();
    gameItemRepository
        .findAllById(new HashSet<>(itemOf.values()))
        .forEach(i -> items.put(i.getId(), i.getName()));
    Map<UUID, UUID> typeOfShip = new HashMap<>();
    for (Ship ship : shipRepository.findAllById(new HashSet<>(shipOf.values()))) {
      typeOfShip.put(ship.getId(), ship.getShipType().getId());
    }
    Set<UUID> typeIds = new HashSet<>(shipTypeOf.values());
    typeIds.addAll(typeOfShip.values());
    Map<UUID, String> types = new HashMap<>();
    for (ShipType type : shipTypeRepository.findAllById(typeIds)) {
      types.put(type.getId(), type.getName());
    }
    put(labels, materialOf, materials);
    put(labels, itemOf, items);
    put(labels, shipTypeOf, types);
    shipOf.forEach(
        (entry, ship) -> {
          String name = types.get(typeOfShip.get(ship));
          if (name != null) {
            labels.put(entry, name);
          }
        });
    return labels;
  }

  /**
   * Adds the names found for the entries that point at a catalogue entry.
   *
   * @param labels the labels by entry id
   * @param target the catalogue id by entry id
   * @param names the names by catalogue id
   */
  private static void put(
      @NotNull Map<UUID, String> labels,
      @NotNull Map<UUID, UUID> target,
      @NotNull Map<UUID, String> names) {
    target.forEach(
        (entry, id) -> {
          String name = names.get(id);
          if (name != null) {
            labels.put(entry, name);
          }
        });
  }

  /**
   * Reads the material's or item's id from a lot key.
   *
   * @param lotKey the lot key
   * @return the id, or {@code null} when the key is not a lot key
   */
  private static @Nullable UUID lotCatalogueId(@NotNull String lotKey) {
    if (lotKey.length() < LOT_HEAD || !(lotKey.startsWith("m:") || lotKey.startsWith("i:"))) {
      return null;
    }
    return uuid(lotKey.substring(2, LOT_HEAD));
  }

  /**
   * Reads a UUID.
   *
   * @param raw the text, or {@code null}
   * @return the UUID, or {@code null} when the text is none
   */
  private static @Nullable UUID uuid(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  /**
   * Reads a journaled state.
   *
   * @param json the state, or {@code null}
   * @return the state, or {@code null} for none
   */
  private @Nullable JsonNode parse(@Nullable String json) {
    return json == null ? null : objectMapper.readTree(json);
  }

  /**
   * Reads a text field of a state.
   *
   * @param state the state, or {@code null}
   * @param field the field
   * @return its text, or {@code null} when absent or null
   */
  private static @Nullable String text(@Nullable JsonNode state, @NotNull String field) {
    if (state == null) {
      return null;
    }
    JsonNode value = state.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }
}
