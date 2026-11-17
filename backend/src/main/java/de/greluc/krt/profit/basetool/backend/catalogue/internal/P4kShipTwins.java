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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipPlayability;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Merges a second ship type of a game ship into the row of its P4K record (REQ-P4K-053). A row
 * without a P4K GUID is merged into the unretired row whose GUID is its Wiki UUID; else into the
 * single row holding a same-name record of its own record's variant group; else into the single row
 * whose name is its Wiki in-game name or, for a row only UEX wrote, its UEX full name. A row whose
 * GUID left a complete catalogue is merged into the row holding the record that succeeds it, and a
 * row whose record a {@code _GS} record of the same in-game name supersedes into the row holding
 * that record. The twins are found before the run decides which rows move to their base or
 * successor (REQ-P4K-051), since a twin's identity would block such a move, and are merged once it
 * has: a row that moves keeps its own Wiki identity.
 */
@Component
@RequiredArgsConstructor
class P4kShipTwins {

  private final ShipTypeMergeService merges;

  /**
   * Finds the twins among the stored ship types.
   *
   * @param stored the stored ship types
   * @param ships the catalogue's ships
   * @param shipsComplete whether the ships kind is complete, the only case a GUID can be departed
   * @param retiring the ship types this run retires (REQ-P4K-052); none of them takes a twin
   * @return the twins, each with the row it ends up merged into: a twin of a row that is itself a
   *     twin is merged into that row's survivor
   */
  @NotNull
  List<Twin> find(
      @NotNull List<ShipType> stored,
      @NotNull List<P4kShipDto> ships,
      boolean shipsComplete,
      @NotNull Set<UUID> retiring) {
    Catalogue catalogue = Catalogue.of(ships, shipsComplete);
    Holders holders = Holders.of(stored, retiring);
    Map<UUID, Twin> byRow = new LinkedHashMap<>();
    for (ShipType row : stored) {
      Twin twin =
          row.getP4kUuid() == null
              ? copyOf(row, catalogue, holders)
              : successorOf(row, catalogue, holders, retiring);
      if (twin != null) {
        byRow.put(Objects.requireNonNull(row.getId()), twin);
      }
    }
    List<Twin> twins = new ArrayList<>();
    byRow.values().forEach(twin -> twins.add(finalSurvivor(twin, byRow)));
    return List.copyOf(twins);
  }

  /**
   * Follows a twin's survivor while the survivor is itself a twin.
   *
   * @param twin the twin
   * @param byRow the twins by the id of the row merged away
   * @return the twin pointed at the row that stays at the end
   */
  @NotNull
  private static Twin finalSurvivor(@NotNull Twin twin, @NotNull Map<UUID, Twin> byRow) {
    Twin result = twin;
    Set<UUID> seen = new HashSet<>();
    Twin next = byRow.get(result.survivor().getId());
    while (next != null && seen.add(Objects.requireNonNull(next.row().getId()))) {
      result = new Twin(twin.row(), next.survivor(), next.record(), twin.reason());
      next = byRow.get(next.survivor().getId());
    }
    return result;
  }

  /**
   * Merges the twins of an APPLY, or reports those a preview would merge.
   *
   * @param twins the twins {@link #find} found
   * @param moves the GUID each ship type this run moves takes (REQ-P4K-051), by ship type
   * @param context the run
   * @return the twins merged, or in a preview those an APPLY would merge
   */
  @NotNull
  Set<UUID> merge(
      @NotNull List<Twin> twins,
      @NotNull Map<UUID, UUID> moves,
      @NotNull P4kImportContext context) {
    Set<UUID> merged = new HashSet<>();
    for (Twin twin : twins) {
      UUID twinId = Objects.requireNonNull(twin.row().getId());
      UUID survivorId = Objects.requireNonNull(twin.survivor().getId());
      String twinName = twin.row().getName();
      boolean done =
          !context.isApply()
              || merges.merge(
                  twinId,
                  survivorId,
                  moves.getOrDefault(survivorId, twin.survivor().getP4kUuid()),
                  twin.reason());
      if (done) {
        context
            .getReport()
            .merged(P4kKind.SHIP, twin.record(), twinName, twin.survivor().getName());
        merged.add(twinId);
      }
    }
    return Set.copyOf(merged);
  }

  /**
   * Finds the row a ship type without a P4K GUID copies, by its Wiki UUID, its record's variant
   * group and name, its Wiki in-game name, or, for a row only UEX wrote, its UEX full name.
   *
   * @param row the ship type
   * @param catalogue the catalogue's ships
   * @param holders the rows holding a GUID
   * @return the twin, or {@code null} when the row copies no single row
   */
  @Nullable
  private static Twin copyOf(
      @NotNull ShipType row, @NotNull Catalogue catalogue, @NotNull Holders holders) {
    UUID wikiUuid = row.getExternalUuid();
    P4kShipDto own = wikiUuid == null ? null : catalogue.record(wikiUuid);
    ShipType byGuid = wikiUuid == null ? null : holders.byGuid().get(wikiUuid);
    if (byGuid != null && own != null) {
      return new Twin(row, byGuid, own, ShipTypeMergeReason.WIKI_UUID_IS_P4K_GUID);
    }
    if (own == null) {
      own = catalogue.byClass(row.getClassName());
    }
    if (own != null) {
      Twin sameGroup = sameGroup(row, own, catalogue, holders);
      if (sameGroup != null) {
        return sameGroup;
      }
    }
    Twin byGameName =
        named(
            row,
            row.getGameName(),
            catalogue,
            holders,
            ShipTypeMergeReason.WIKI_GAME_NAME_IS_P4K_NAME);
    if (byGameName != null || row.getSourceSystems() != GameItemSourceSystem.UEX_ONLY) {
      return byGameName;
    }
    return named(
        row, row.getNameFull(), catalogue, holders, ShipTypeMergeReason.UEX_FULL_NAME_IS_P4K_NAME);
  }

  /**
   * Finds the single row whose name is a given in-game name of a ship type.
   *
   * @param row the ship type
   * @param name its Wiki in-game name or UEX full name, or {@code null}
   * @param catalogue the catalogue's ships
   * @param holders the rows holding a GUID
   * @param reason the evidence the name gives
   * @return the twin, or {@code null} when no single row carries the name
   */
  @Nullable
  private static Twin named(
      @NotNull ShipType row,
      @Nullable String name,
      @NotNull Catalogue catalogue,
      @NotNull Holders holders,
      @NotNull ShipTypeMergeReason reason) {
    List<ShipType> named = holders.named(name);
    ShipType survivor = named.size() == 1 ? named.getFirst() : null;
    P4kShipDto record = survivor == null ? null : settledRecord(survivor, catalogue, holders);
    return survivor == null || record == null ? null : new Twin(row, survivor, record, reason);
  }

  /**
   * Returns the record a row holds once the run is through: the record of its GUID, or, for a GUID
   * that left a complete catalogue, the successor no other row holds, which the row moves to
   * (REQ-P4K-051).
   *
   * @param row the row
   * @param catalogue the catalogue's ships
   * @param holders the rows holding a GUID
   * @return the record, or {@code null} when the row's GUID left and no free successor exists
   */
  @Nullable
  private static P4kShipDto settledRecord(
      @NotNull ShipType row, @NotNull Catalogue catalogue, @NotNull Holders holders) {
    P4kShipDto record = catalogue.record(row.getP4kUuid());
    if (record != null || !catalogue.complete()) {
      return record;
    }
    P4kShipDto successor = P4kShipIdentityMoves.successorRecord(row, catalogue.players());
    UUID successorGuid = successor == null ? null : successor.parsedGuid();
    return successorGuid == null || holders.byGuid().containsKey(successorGuid) ? null : successor;
  }

  /**
   * Finds the single row holding a record of the same variant group and in-game name as a ship
   * type's own record.
   *
   * @param row the ship type
   * @param own the record its Wiki UUID or class name names
   * @param catalogue the catalogue's ships
   * @param holders the rows holding a GUID
   * @return the twin, or {@code null} when no single row does
   */
  @Nullable
  private static Twin sameGroup(
      @NotNull ShipType row,
      @NotNull P4kShipDto own,
      @NotNull Catalogue catalogue,
      @NotNull Holders holders) {
    UUID group = Catalogue.groupOf(own);
    String name = P4kMatchKeys.fold(own.nameEn());
    if (group == null || name == null || name.isEmpty()) {
      return null;
    }
    List<Twin> found = new ArrayList<>();
    for (Map.Entry<UUID, ShipType> holder : holders.byGuid().entrySet()) {
      P4kShipDto held = catalogue.record(holder.getKey());
      if (held != null
          && group.equals(Catalogue.groupOf(held))
          && name.equals(P4kMatchKeys.fold(held.nameEn()))) {
        found.add(
            new Twin(
                row, holder.getValue(), held, ShipTypeMergeReason.SAME_VARIANT_GROUP_AND_NAME));
      }
    }
    return found.size() == 1 ? found.getFirst() : null;
  }

  /**
   * Finds the row a ship type holding a GUID is merged into: for a GUID that left a complete
   * catalogue, the row holding the single player base that carries one of its names; for a listed
   * GUID a {@code _GS} record supersedes, the row holding that record.
   *
   * @param row the ship type
   * @param catalogue the catalogue's ships
   * @param holders the rows holding a GUID
   * @param retiring the ship types this run retires
   * @return the twin, or {@code null} when no other row holds the successor
   */
  @Nullable
  private static Twin successorOf(
      @NotNull ShipType row,
      @NotNull Catalogue catalogue,
      @NotNull Holders holders,
      @NotNull Set<UUID> retiring) {
    UUID guid = row.getP4kUuid();
    if (guid == null
        || row.isHidden()
        || row.getP4kHiddenAt() != null
        || retiring.contains(row.getId())) {
      return null;
    }
    if (catalogue.lists(guid)) {
      return into(
          row,
          catalogue.superseded().get(guid),
          holders.byGuid(),
          ShipTypeMergeReason.SUPERSEDED_BY_GS_RECORD);
    }
    if (!catalogue.complete()) {
      return null;
    }
    P4kShipDto base = P4kShipIdentityMoves.successorRecord(row, catalogue.players());
    if (base != null) {
      return into(row, base, holders.byGuid(), ShipTypeMergeReason.DEPARTED_FOR_HELD_SUCCESSOR);
    }
    P4kShipDto named = catalogue.namedPlayer(row);
    if (named != null) {
      return into(row, named, holders.byGuid(), ShipTypeMergeReason.DEPARTED_FOR_HELD_SUCCESSOR);
    }
    return editionOf(row, catalogue, holders);
  }

  /**
   * Finds the row a vanished edition of a ship is merged into: its class name extends the class
   * name of a player base, or of that base's {@code _GS} record, by a suffix, and one of its names
   * begins with that base's in-game name. The row holding the base, or moving to it in this run,
   * takes it.
   *
   * @param row the ship type whose GUID left a complete catalogue
   * @param catalogue the catalogue's ships
   * @param holders the rows holding a GUID
   * @return the twin, or {@code null} when the row is no edition of a ship that has a row
   */
  @Nullable
  private static Twin editionOf(
      @NotNull ShipType row, @NotNull Catalogue catalogue, @NotNull Holders holders) {
    P4kShipDto base = catalogue.editionBase(row.getClassName());
    String baseName = base == null ? null : P4kMatchKeys.fold(base.nameEn());
    UUID baseGuid = base == null ? null : base.parsedGuid();
    if (base == null
        || baseName == null
        || baseName.isEmpty()
        || baseGuid == null
        || namesOf(row).stream().noneMatch(name -> name.startsWith(baseName + " "))) {
      return null;
    }
    Map<UUID, ShipType> holding = new HashMap<>(holders.byGuid());
    if (!holding.containsKey(baseGuid)) {
      ShipType heir = heirOf(base, row, catalogue, holders);
      if (heir != null) {
        holding.put(baseGuid, heir);
      }
    }
    return into(row, base, holding, ShipTypeMergeReason.EDITION_OF_BASE);
  }

  /**
   * Finds the single other row whose GUID left the catalogue and that moves to a base no row holds
   * yet (REQ-P4K-051).
   *
   * @param base the base
   * @param row the row asking
   * @param catalogue the catalogue's ships
   * @param holders the rows holding a GUID
   * @return the row moving to the base, or {@code null} when none or several do
   */
  @Nullable
  private static ShipType heirOf(
      @NotNull P4kShipDto base,
      @NotNull ShipType row,
      @NotNull Catalogue catalogue,
      @NotNull Holders holders) {
    UUID baseGuid = base.parsedGuid();
    List<ShipType> heirs = new ArrayList<>();
    for (Map.Entry<UUID, ShipType> holder : holders.byGuid().entrySet()) {
      ShipType other = holder.getValue();
      P4kShipDto successor =
          catalogue.lists(holder.getKey())
              ? null
              : P4kShipIdentityMoves.successorRecord(other, catalogue.players());
      if (!Objects.equals(other.getId(), row.getId())
          && successor != null
          && Objects.equals(successor.parsedGuid(), baseGuid)) {
        heirs.add(other);
      }
    }
    return heirs.size() == 1 ? heirs.getFirst() : null;
  }

  /**
   * Builds the twin of a row and the row holding a record.
   *
   * @param row the twin
   * @param record the record the survivor holds, or {@code null}
   * @param holding the rows holding a GUID, by GUID
   * @param reason the evidence
   * @return the twin, or {@code null} when no other row holds the record
   */
  @Nullable
  private static Twin into(
      @NotNull ShipType row,
      @Nullable P4kShipDto record,
      @NotNull Map<UUID, ShipType> holding,
      @NotNull ShipTypeMergeReason reason) {
    UUID guid = record == null ? null : record.parsedGuid();
    ShipType survivor = guid == null ? null : holding.get(guid);
    return record == null || survivor == null || Objects.equals(survivor.getId(), row.getId())
        ? null
        : new Twin(row, survivor, record, reason);
  }

  /**
   * Returns the folded names of a ship type: its name, UEX full name and Wiki in-game name.
   *
   * @param row the ship type
   * @return the distinct non-empty folded names
   */
  @NotNull
  private static Set<String> namesOf(@NotNull ShipType row) {
    Set<String> names = new HashSet<>();
    for (String name :
        List.of(
            nullToEmpty(row.getName()),
            nullToEmpty(row.getNameFull()),
            nullToEmpty(row.getGameName()))) {
      String folded = P4kMatchKeys.fold(name);
      if (folded != null && !folded.isEmpty()) {
        names.add(folded);
      }
    }
    return names;
  }

  /**
   * Replaces a missing name by an empty one.
   *
   * @param name the name, or {@code null}
   * @return the name, or an empty string
   */
  @NotNull
  private static String nullToEmpty(@Nullable String name) {
    return name == null ? "" : name;
  }

  /**
   * A twin, the row it is merged into and why.
   *
   * @param row the twin
   * @param survivor the row it is merged into
   * @param record the catalogue record the survivor holds
   * @param reason the evidence that both stand for the same game ship
   */
  record Twin(
      @NotNull ShipType row,
      @NotNull ShipType survivor,
      @NotNull P4kShipDto record,
      @NotNull ShipTypeMergeReason reason) {}

  /**
   * The catalogue's ships, indexed for the twin rules.
   *
   * @param byGuid the records by GUID
   * @param byClass the records by folded class name
   * @param players the player bases by folded in-game name, empty for a partial ships kind
   * @param allPlayers the player records, variants included, by folded in-game name
   * @param superseded the {@code _GS} records by the GUID of the record they supersede
   * @param complete whether the ships kind is complete
   */
  private record Catalogue(
      @NotNull Map<UUID, List<P4kShipDto>> byGuid,
      @NotNull Map<String, List<P4kShipDto>> byClass,
      @NotNull Map<String, List<P4kShipDto>> players,
      @NotNull Map<String, List<P4kShipDto>> allPlayers,
      @NotNull Map<UUID, P4kShipDto> superseded,
      boolean complete) {

    /**
     * Indexes the catalogue's ships.
     *
     * @param ships the catalogue's ships
     * @param complete whether the ships kind is complete
     * @return the index
     */
    @NotNull
    static Catalogue of(@NotNull List<P4kShipDto> ships, boolean complete) {
      Map<UUID, List<P4kShipDto>> byGuid = new HashMap<>();
      Map<String, List<P4kShipDto>> byClass = new HashMap<>();
      Map<String, List<P4kShipDto>> allPlayers = new HashMap<>();
      for (P4kShipDto ship : ships) {
        UUID guid = ship.parsedGuid();
        if (guid != null) {
          byGuid.computeIfAbsent(guid, _ -> new ArrayList<>()).add(ship);
        }
        String className = P4kMatchKeys.fold(ship.className());
        if (className != null && !className.isEmpty()) {
          byClass.computeIfAbsent(className, _ -> new ArrayList<>()).add(ship);
        }
        String name = P4kMatchKeys.fold(ship.nameEn());
        if (name != null && !name.isEmpty() && isPlayer(ship)) {
          allPlayers.computeIfAbsent(name, _ -> new ArrayList<>()).add(ship);
        }
      }
      Map<String, List<P4kShipDto>> players =
          complete ? P4kShipIdentityMoves.playersByName(byGuid) : Map.of();
      return new Catalogue(
          byGuid, byClass, players, allPlayers, P4kShipGoldStandard.supersededBy(ships), complete);
    }

    /**
     * Returns the single player record, a variant or not, whose in-game name is one of a ship
     * type's names.
     *
     * @param row the ship type
     * @return the record, or {@code null} when none or several carry its names
     */
    @Nullable
    P4kShipDto namedPlayer(@NotNull ShipType row) {
      Map<UUID, P4kShipDto> found = new HashMap<>();
      for (String name : namesOf(row)) {
        for (P4kShipDto record : allPlayers.getOrDefault(name, List.of())) {
          UUID guid = record.parsedGuid();
          if (guid != null) {
            found.putIfAbsent(guid, record);
          }
        }
      }
      return found.size() == 1 ? found.values().iterator().next() : null;
    }

    /**
     * Returns the player base whose class name, or whose {@code _GS} record's class name, an
     * edition's class name extends by a suffix; the longest such class name wins.
     *
     * @param className the edition's class name, or {@code null}
     * @return the base, its {@code _GS} record when it has one, or {@code null}
     */
    @Nullable
    P4kShipDto editionBase(@Nullable String className) {
      String key = P4kMatchKeys.fold(className);
      if (key == null) {
        return null;
      }
      for (int cut = key.lastIndexOf('_'); cut > 0; cut = key.lastIndexOf('_', cut - 1)) {
        String prefix = key.substring(0, cut);
        P4kShipDto base = playerBase(prefix + "_GS");
        if (base == null) {
          base = playerBase(prefix);
        }
        if (base != null) {
          return base;
        }
      }
      return null;
    }

    /**
     * Returns the single player base of a folded class name.
     *
     * @param key the folded class name
     * @return the record, or {@code null} when absent, repeated, a variant or no player ship
     */
    @Nullable
    private P4kShipDto playerBase(@NotNull String key) {
      P4kShipDto record = P4kShipIdentityMoves.sole(byClass.get(key));
      return record != null && !StringUtils.hasText(record.variantOf()) && isPlayer(record)
          ? record
          : null;
    }

    /**
     * Tells whether a player can own a record's ship.
     *
     * @param record the record
     * @return whether its playability is {@code player}
     */
    private static boolean isPlayer(@NotNull P4kShipDto record) {
      return P4kClosedSet.parse(P4kShipPlayability.class, record.playability())
          == P4kShipPlayability.PLAYER;
    }

    /**
     * Returns the single record of a GUID.
     *
     * @param guid the GUID, or {@code null}
     * @return the record, or {@code null} when absent or repeated
     */
    @Nullable
    P4kShipDto record(@Nullable UUID guid) {
      return guid == null ? null : P4kShipIdentityMoves.sole(byGuid.get(guid));
    }

    /**
     * Returns the single record of a class name.
     *
     * @param className the class name, or {@code null}
     * @return the record, or {@code null} when absent or repeated
     */
    @Nullable
    P4kShipDto byClass(@Nullable String className) {
      String key = P4kMatchKeys.fold(className);
      return key == null ? null : P4kShipIdentityMoves.sole(byClass.get(key));
    }

    /**
     * Tells whether the catalogue lists a GUID.
     *
     * @param guid the GUID
     * @return whether a record carries it
     */
    boolean lists(@NotNull UUID guid) {
      return byGuid.containsKey(guid);
    }

    /**
     * Returns the base GUID of the variant group a record belongs to.
     *
     * @param record the record
     * @return its {@code variantOf}, else its own GUID
     */
    @Nullable
    static UUID groupOf(@NotNull P4kShipDto record) {
      UUID base = P4kGuids.parse(record.variantOf());
      return base == null ? record.parsedGuid() : base;
    }
  }

  /**
   * The rows that can take a twin: they hold a GUID and are neither retired nor retiring.
   *
   * @param byGuid the rows by GUID
   * @param byName the rows by folded name
   */
  private record Holders(
      @NotNull Map<UUID, ShipType> byGuid, @NotNull Map<String, List<ShipType>> byName) {

    /**
     * Indexes the rows that can take a twin.
     *
     * @param stored the stored ship types
     * @param retiring the ship types this run retires
     * @return the index
     */
    @NotNull
    static Holders of(@NotNull List<ShipType> stored, @NotNull Set<UUID> retiring) {
      Map<UUID, ShipType> byGuid = new HashMap<>();
      Map<String, List<ShipType>> byName = new HashMap<>();
      for (ShipType row : stored) {
        UUID guid = row.getP4kUuid();
        if (guid != null && !row.isP4kRetired() && !retiring.contains(row.getId())) {
          byGuid.put(guid, row);
          String name = P4kMatchKeys.fold(row.getName());
          if (name != null && !name.isEmpty()) {
            byName.computeIfAbsent(name, _ -> new ArrayList<>()).add(row);
          }
        }
      }
      return new Holders(byGuid, byName);
    }

    /**
     * Returns the rows carrying a name.
     *
     * @param name the name, or {@code null}
     * @return the rows, empty when none or no name
     */
    @NotNull
    List<ShipType> named(@Nullable String name) {
      String key = P4kMatchKeys.fold(name);
      return key == null || key.isEmpty() ? List.of() : byName.getOrDefault(key, List.of());
    }
  }
}
