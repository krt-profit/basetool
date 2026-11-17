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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.hangar.api.ImportShipMatch;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipTypeMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Resolves the entries of one hangar import to ship types (REQ-HANGAR-019).
 *
 * <p>A Hangar Extension CSV entry tries its name without the manufacturer word first, then the full
 * name with its slug. A ship that several catalogue rows hold takes the row the member's hangar
 * already uses, else its canonical row. A name that may mean several ships takes the member's
 * choice when the choice names one of its options, else it is reported with the options; a name
 * that finds no ship is reported as unmatched.
 */
@Slf4j
final class ImportEntryResolver {

  private ImportEntryResolver() {}

  /**
   * Resolves every entry of an upload.
   *
   * @param entries the parsed upload entries
   * @param index the ship-type lookup index of the run
   * @param heldShipTypeIds the ship types of the member's importable hangar ships
   * @param choices the member's choices for ambiguous names
   * @return the entries per resolved ship type, the unmatched and the ambiguous names
   */
  @NotNull
  static Resolution resolve(
      @NotNull List<FleetExportParser.FleetImportEntry> entries,
      @NotNull ShipTypeMatcher.ShipTypeIndex index,
      @NotNull Set<UUID> heldShipTypeIds,
      @NotNull List<HangarImportChoiceDto> choices) {
    Map<String, UUID> chosenByName = new HashMap<>();
    choices.forEach(choice -> chosenByName.putIfAbsent(key(choice.name()), choice.shipTypeId()));
    Map<UUID, ShipType> shipTypes = new LinkedHashMap<>();
    Map<UUID, List<FleetExportParser.FleetImportEntry>> entriesByShipType = new LinkedHashMap<>();
    Map<String, String> unmatched = new LinkedHashMap<>();
    Map<String, Ambiguity> ambiguous = new LinkedHashMap<>();
    for (FleetExportParser.FleetImportEntry entry : entries) {
      String name = entry.name().trim();
      ImportShipMatch match = match(index, entry, name);
      ImportShipMatch.Candidate ship = match.single();
      if (ship == null) {
        ship = chosen(match, chosenByName.get(key(name)));
      }
      if (ship != null) {
        ShipType shipType = preferredRow(ship, heldShipTypeIds);
        shipTypes.putIfAbsent(shipType.getId(), shipType);
        entriesByShipType.computeIfAbsent(shipType.getId(), _ -> new ArrayList<>()).add(entry);
      } else if (match.isEmpty()) {
        log.debug("Hangar import: no ShipType match for '{}'", entry.name());
        unmatched.putIfAbsent(key(name), entry.name());
      } else {
        ambiguous.computeIfAbsent(
                key(name), _ -> new Ambiguity(entry.name(), options(match, heldShipTypeIds)))
            .count++;
      }
    }
    return new Resolution(
        shipTypes,
        entriesByShipType,
        List.copyOf(unmatched.values()),
        ambiguous.values().stream().map(Ambiguity::toDto).toList());
  }

  /**
   * Finds the ships an entry may mean: its name without the manufacturer word when the format
   * supplies one, then its full name and slug.
   *
   * @param index the lookup index
   * @param entry the upload entry
   * @param name the entry's trimmed name
   * @return the first single ship, else the first non-empty result
   */
  @NotNull
  private static ImportShipMatch match(
      @NotNull ShipTypeMatcher.ShipTypeIndex index,
      @NotNull FleetExportParser.FleetImportEntry entry,
      @NotNull String name) {
    String unprefixedName = entry.unprefixedName();
    ImportShipMatch unprefixed =
        unprefixedName == null
            ? ImportShipMatch.NONE
            : ShipTypeMatcher.resolveImport(index, unprefixedName, null);
    if (unprefixed.single() != null) {
      return unprefixed;
    }
    ImportShipMatch full = ShipTypeMatcher.resolveImport(index, name, entry.slug());
    return full.single() != null || unprefixed.isEmpty() ? full : unprefixed;
  }

  /**
   * Returns the ship the member chose among a name's options.
   *
   * @param match the name's ships
   * @param shipTypeId the chosen ship type, or {@code null} without a choice
   * @return the ship holding the chosen row, or {@code null} without a valid choice
   */
  @Nullable
  private static ImportShipMatch.Candidate chosen(
      @NotNull ImportShipMatch match, @Nullable UUID shipTypeId) {
    if (shipTypeId == null) {
      return null;
    }
    return match.ships().stream()
        .filter(candidate -> candidate.row(shipTypeId) != null)
        .findFirst()
        .orElse(null);
  }

  /**
   * Returns the row a ship's entries go to: one the hangar already holds, else the canonical row.
   *
   * @param ship the ship
   * @param heldShipTypeIds the ship types of the member's importable hangar ships
   * @return the row
   */
  @NotNull
  private static ShipType preferredRow(
      @NotNull ImportShipMatch.Candidate ship, @NotNull Set<UUID> heldShipTypeIds) {
    return ship.rows().stream()
        .filter(row -> heldShipTypeIds.contains(row.getId()))
        .findFirst()
        .orElse(ship.canonical());
  }

  /**
   * Lists a name's ships as options, each by the row a choice of it imports to.
   *
   * @param match the name's ships
   * @param heldShipTypeIds the ship types of the member's importable hangar ships
   * @return the options, in the match's order
   */
  @NotNull
  @Unmodifiable
  private static List<ShipTypeOptionDto> options(
      @NotNull ImportShipMatch match, @NotNull Set<UUID> heldShipTypeIds) {
    return match.ships().stream()
        .map(ship -> preferredRow(ship, heldShipTypeIds))
        .map(row -> new ShipTypeOptionDto(row.getId(), row.getName()))
        .toList();
  }

  /**
   * Folds an upload name to the key its entries and choices share.
   *
   * @param name the upload name
   * @return the trimmed, lower-cased name
   */
  @NotNull
  private static String key(@NotNull String name) {
    return name.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * What one import resolved.
   *
   * @param shipTypes the resolved ship types by id, in upload order
   * @param entriesByShipType the entries of each resolved ship type, in upload order
   * @param unmatched the distinct names that found no ship, as the file first spells them
   * @param ambiguous the distinct names that may mean several ships and carried no valid choice
   */
  record Resolution(
      @NotNull Map<UUID, ShipType> shipTypes,
      @NotNull Map<UUID, List<FleetExportParser.FleetImportEntry>> entriesByShipType,
      @NotNull @Unmodifiable List<String> unmatched,
      @NotNull @Unmodifiable List<AmbiguousShipDto> ambiguous) {}

  /** One ambiguous name while the entries are counted. */
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  private static final class Ambiguity {

    /** The name as the file first spells it. */
    @NotNull private final String name;

    /** The ships the name may mean. */
    @NotNull private final List<ShipTypeOptionDto> options;

    /** How many entries carry the name. */
    private int count;

    /**
     * Freezes the count.
     *
     * @return the reported ambiguous name
     */
    @NotNull
    private AmbiguousShipDto toDto() {
      return new AmbiguousShipDto(name, count, options);
    }
  }
}
