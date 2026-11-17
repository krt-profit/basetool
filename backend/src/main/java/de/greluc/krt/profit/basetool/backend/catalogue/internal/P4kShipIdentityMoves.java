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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipPlayability;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * Finds the ship types whose P4K identity the catalogue itself moves (REQ-P4K-051): a ship type
 * stamped with a player edition whose base carries the same in-game name, and a ship type whose
 * GUID left a complete catalogue, which follows the single player record carrying one of its names.
 * A target another ship type already holds, or that two ship types would take, moves nothing.
 */
final class P4kShipIdentityMoves {

  /** Non-instantiable holder of the rule. */
  private P4kShipIdentityMoves() {}

  /**
   * A ship type and the base record it moves to.
   *
   * @param row the ship type
   * @param base the record it moves to
   * @param baseGuid the record's GUID
   * @param variant the variant record the ship type holds, or {@code null} for a departed GUID
   */
  record Move(
      @NotNull ShipType row,
      @NotNull P4kShipDto base,
      @NotNull UUID baseGuid,
      @Nullable P4kShipDto variant) {}

  /**
   * Finds the moves of a catalogue.
   *
   * @param byGuid the catalogue's ships by GUID
   * @param shipsComplete whether the ships kind is complete, the only case a GUID can be departed
   * @param ships the stored ship types
   * @param excluded the ship types a confirmed repair already moves
   * @return the moves, at most one per ship type and per target
   */
  @NotNull
  static List<Move> find(
      @NotNull Map<UUID, List<P4kShipDto>> byGuid,
      boolean shipsComplete,
      @NotNull List<ShipType> ships,
      @NotNull Set<UUID> excluded) {
    Set<UUID> held = new HashSet<>();
    for (ShipType ship : ships) {
      if (ship.getP4kUuid() != null) {
        held.add(ship.getP4kUuid());
      }
      if (ship.getExternalUuid() != null) {
        held.add(ship.getExternalUuid());
      }
    }
    Map<String, List<P4kShipDto>> playersByName = playersByName(byGuid);
    List<Move> moves = new ArrayList<>();
    for (ShipType ship : ships) {
      UUID guid = ship.getP4kUuid();
      if (guid == null || excluded.contains(ship.getId()) || ship.isHidden()) {
        continue;
      }
      List<P4kShipDto> records = byGuid.get(guid);
      Move move =
          records == null
              ? (shipsComplete && ship.getP4kHiddenAt() == null
                  ? successor(ship, playersByName, held)
                  : null)
              : baseOfVariant(ship, sole(records), byGuid, held);
      if (move != null) {
        moves.add(move);
      }
    }
    return distinctTargets(moves);
  }

  /**
   * The base a ship type stamped with a variant moves to: the variant's group base, a player ship
   * of the same in-game name that no ship type holds.
   *
   * @param ship the ship type
   * @param variant the record of the GUID it holds, or {@code null} when absent or repeated
   * @param byGuid the catalogue's ships by GUID
   * @param held the GUIDs ship types hold as P4K GUID or game UUID
   * @return the move, or {@code null}
   */
  @Nullable
  private static Move baseOfVariant(
      @NotNull ShipType ship,
      @Nullable P4kShipDto variant,
      @NotNull Map<UUID, List<P4kShipDto>> byGuid,
      @NotNull Set<UUID> held) {
    if (variant == null || ship.isP4kRetired() || !isPlayer(variant)) {
      return null;
    }
    UUID baseGuid = P4kGuids.parse(variant.variantOf());
    if (baseGuid == null || isHeldByAnother(baseGuid, ship, held)) {
      return null;
    }
    P4kShipDto base = sole(byGuid.get(baseGuid));
    if (base == null
        || !isPlayerBase(base)
        || !Objects.equals(P4kMatchKeys.fold(base.nameEn()), P4kMatchKeys.fold(variant.nameEn()))) {
      return null;
    }
    return new Move(ship, base, baseGuid, variant);
  }

  /**
   * The record a ship type whose GUID left the catalogue follows: the single player base whose
   * in-game name is one of the ship type's names and that no ship type holds.
   *
   * @param ship the ship type
   * @param playersByName the catalogue's player bases by folded in-game name
   * @param held the GUIDs ship types hold as P4K GUID or game UUID
   * @return the move, or {@code null}
   */
  @Nullable
  private static Move successor(
      @NotNull ShipType ship,
      @NotNull Map<String, List<P4kShipDto>> playersByName,
      @NotNull Set<UUID> held) {
    P4kShipDto base = successorRecord(ship, playersByName);
    if (base == null) {
      return null;
    }
    UUID baseGuid = Objects.requireNonNull(base.parsedGuid());
    return held.contains(baseGuid) ? null : new Move(ship, base, baseGuid, null);
  }

  /**
   * Returns the single player base whose in-game name is one of a ship type's names, whoever holds
   * it.
   *
   * @param ship the ship type
   * @param playersByName the catalogue's player bases by folded in-game name
   * @return the record, or {@code null} when none or several carry its names
   */
  @Nullable
  static P4kShipDto successorRecord(
      @NotNull ShipType ship, @NotNull Map<String, List<P4kShipDto>> playersByName) {
    Set<UUID> seen = new HashSet<>();
    List<P4kShipDto> candidates = new ArrayList<>();
    Stream.of(ship.getName(), ship.getNameFull(), ship.getGameName())
        .map(P4kMatchKeys::fold)
        .filter(Objects::nonNull)
        .distinct()
        .flatMap(name -> playersByName.getOrDefault(name, List.of()).stream())
        .filter(record -> seen.add(record.parsedGuid()))
        .forEach(candidates::add);
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }

  /**
   * Indexes the catalogue's player bases by their folded in-game name.
   *
   * @param byGuid the catalogue's ships by GUID
   * @return the bases by name
   */
  @NotNull
  static Map<String, List<P4kShipDto>> playersByName(@NotNull Map<UUID, List<P4kShipDto>> byGuid) {
    Map<String, List<P4kShipDto>> byName = new HashMap<>();
    for (List<P4kShipDto> records : byGuid.values()) {
      P4kShipDto record = sole(records);
      String name = record == null ? null : P4kMatchKeys.fold(record.nameEn());
      if (record != null && name != null && !name.isEmpty() && isPlayerBase(record)) {
        byName.computeIfAbsent(name, _ -> new ArrayList<>()).add(record);
      }
    }
    return byName;
  }

  /**
   * Tells whether a record is a player ship that heads its variant group or stands alone.
   *
   * @param record the record
   * @return whether it is a player base
   */
  private static boolean isPlayerBase(@NotNull P4kShipDto record) {
    return !StringUtils.hasText(record.variantOf())
        && StringUtils.hasText(record.className())
        && isPlayer(record);
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
   * Tells whether a ship type other than the moving one holds a GUID.
   *
   * @param guid the GUID
   * @param ship the moving ship type
   * @param held the GUIDs ship types hold as P4K GUID or game UUID
   * @return whether another ship type holds it
   */
  private static boolean isHeldByAnother(
      @NotNull UUID guid, @NotNull ShipType ship, @NotNull Set<UUID> held) {
    return held.contains(guid) && !guid.equals(ship.getExternalUuid());
  }

  /**
   * Drops every move whose target another move also takes.
   *
   * @param moves the moves
   * @return the moves with a target of their own
   */
  @NotNull
  private static List<Move> distinctTargets(@NotNull List<Move> moves) {
    Map<UUID, Long> targets = new HashMap<>();
    moves.forEach(move -> targets.merge(move.baseGuid(), 1L, Long::sum));
    return moves.stream().filter(move -> targets.getOrDefault(move.baseGuid(), 0L) == 1L).toList();
  }

  /**
   * Returns the single record of a GUID.
   *
   * @param records the records of the GUID, or {@code null}
   * @return the record, or {@code null} when absent or repeated
   */
  @Nullable
  static P4kShipDto sole(@Nullable List<P4kShipDto> records) {
    return records != null && records.size() == 1 ? records.getFirst() : null;
  }
}
