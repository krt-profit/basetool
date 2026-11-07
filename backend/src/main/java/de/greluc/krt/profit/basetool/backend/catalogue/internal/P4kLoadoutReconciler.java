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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLoadoutDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLoadoutPortDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kPortSource;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kPortTypeDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Stores the catalogue's ship loadouts as port trees (REQ-P4K-040): an APPLY replaces the tree of
 * every listed ship type, and a complete loadouts kind also removes the trees of ship types it no
 * longer lists; an empty one removes nothing. Item loadouts are not stored. The loadouts are
 * streamed one at a time and each tree leaves the persistence context once written, so the kind
 * never sits in memory as a whole.
 */
@Component
@RequiredArgsConstructor
class P4kLoadoutReconciler {

  /** The catalogue field reported for an unknown port {@code source}. */
  private static final String SOURCE = "source";

  /** The separator between an item type and its subtype in {@code types}. */
  private static final String SUB_TYPE_SEPARATOR = ".";

  private final ShipTypeLoadoutPortRepository portRepository;
  private final EntityManager entityManager;

  /**
   * Streams the loadouts of a catalogue.
   *
   * @see P4kLoadoutReconciler#reconcile
   */
  @FunctionalInterface
  interface LoadoutStream {

    /**
     * Hands every loadout of the catalogue to an action, one at a time.
     *
     * @param action receives each loadout
     */
    void forEach(@NotNull Consumer<P4kLoadoutDto> action);
  }

  /**
   * Stores the ship loadouts of a run; items and ships must be reconciled first. A preview reports
   * the loadouts and writes nothing.
   *
   * @param loadouts the catalogue's loadouts
   * @param catalogue the catalogue, for the ships' identifiers and the kind's completeness
   * @param context the run
   */
  void reconcile(
      @NotNull LoadoutStream loadouts,
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kImportContext context) {
    Run run =
        new Run(
            P4kRowIds.load(entityManager, P4kKind.SHIP, context),
            P4kRowIds.load(entityManager, P4kKind.ITEM, context),
            shipsByGuid(catalogue.ships()),
            context);
    loadouts.forEach(run::accept);
    if (context.isApply()
        && catalogue.head().isComplete(P4kCatalogue.LOADOUTS)
        && !run.written.isEmpty()) {
      portRepository.deleteTreesExcept(run.written);
    }
  }

  /**
   * Indexes the catalogue's ships by GUID, for the identifiers of a report row.
   *
   * @param ships the catalogue's ships
   * @return the ships by GUID
   */
  @NotNull
  private static Map<UUID, P4kShipDto> shipsByGuid(@NotNull List<P4kShipDto> ships) {
    Map<UUID, P4kShipDto> byGuid = new HashMap<>();
    for (P4kShipDto ship : ships) {
      UUID guid = ship.parsedGuid();
      if (guid != null) {
        byGuid.putIfAbsent(guid, ship);
      }
    }
    return byGuid;
  }

  /**
   * Returns the list a catalogue array binds to.
   *
   * @param values the array, or {@code null}
   * @param <T> the element type
   * @return the array, empty for {@code null}
   */
  @NotNull
  private static <T> List<T> listOf(@Nullable List<T> values) {
    return values == null ? List.of() : values;
  }

  /** The state of one run's pass over the loadouts. */
  @RequiredArgsConstructor
  private final class Run {

    /** The ship types by P4K GUID. */
    private final P4kRowIds ships;

    /** The items by P4K GUID. */
    private final P4kRowIds items;

    /** The catalogue's ships by GUID. */
    private final Map<UUID, P4kShipDto> catalogueShips;

    /** The run. */
    private final P4kImportContext context;

    /** The ship types whose tree the run wrote. */
    private final Set<UUID> written = new HashSet<>();

    /**
     * Stores one loadout when it belongs to a ship type the run knows.
     *
     * @param loadout the loadout
     */
    private void accept(@NotNull P4kLoadoutDto loadout) {
      UUID shipId = loadout.isShip() ? ships.id(loadout.entityGuid()) : null;
      if (shipId == null || !written.add(shipId)) {
        return;
      }
      P4kRecord ship = shipRecord(loadout.entityGuid());
      Tree tree = new Tree(shipId, ship);
      tree.add(listOf(loadout.ports()), null);
      List<String> issues = listOf(loadout.issues());
      if (!issues.isEmpty() || tree.unresolved > 0) {
        context.getReport().loadoutIssues(ship, issues, tree.unresolved);
      }
      context.getReport().loadoutWritten(tree.ports.size());
      if (context.isApply()) {
        portRepository.deleteTree(shipId);
        tree.ports.forEach(entityManager::persist);
        entityManager.flush();
        tree.ports.forEach(entityManager::detach);
      }
    }

    /**
     * Returns the catalogue ship of a GUID for a report row.
     *
     * @param guid the ship's GUID text
     * @return the ship, or a record of the GUID alone when the catalogue's ships lack it
     */
    @NotNull
    private P4kRecord shipRecord(@Nullable String guid) {
      UUID parsed = P4kGuids.parse(guid);
      P4kShipDto ship = parsed == null ? null : catalogueShips.get(parsed);
      return ship != null ? ship : new GuidRecord(guid);
    }

    /** The ports of one ship's tree while it is built. */
    @RequiredArgsConstructor
    private final class Tree {

      /** The ship type. */
      private final UUID shipId;

      /** The ship, for the report rows. */
      private final P4kRecord ship;

      /** The ports in insertion order: every parent before its children. */
      private final List<ShipTypeLoadoutPort> ports = new ArrayList<>();

      /** The unknown {@code source} values already reported for the ship. */
      private final Set<String> unknownSources = new HashSet<>();

      /** The ports whose attachment could not be resolved. */
      private int unresolved;

      /**
       * Adds sibling ports and, depth first, their nested ports.
       *
       * @param siblings the ports
       * @param parentId the parent port, or {@code null} for the top level
       */
      private void add(@NotNull List<P4kLoadoutPortDto> siblings, @Nullable UUID parentId) {
        int position = 0;
        for (P4kLoadoutPortDto dto : siblings) {
          ShipTypeLoadoutPort port = port(dto, parentId, position++);
          ports.add(port);
          add(listOf(dto.ports()), port.getId());
        }
      }

      /**
       * Builds one port with its id, so its nested ports can reference it.
       *
       * @param dto the catalogue port
       * @param parentId the parent port, or {@code null}
       * @param position the place among its siblings
       * @return the unsaved port
       */
      @NotNull
      private ShipTypeLoadoutPort port(
          @NotNull P4kLoadoutPortDto dto, @Nullable UUID parentId, int position) {
        if (dto.unresolved() != null) {
          unresolved++;
        }
        ShipTypeLoadoutPort port = new ShipTypeLoadoutPort();
        port.setId(UUID.randomUUID());
        port.setShipTypeId(shipId);
        port.setParentId(parentId);
        port.setPosition(position);
        port.setName(dto.name() == null ? "" : dto.name());
        port.setMinSize(dto.minSize());
        port.setMaxSize(dto.maxSize());
        port.setTypes(types(listOf(dto.types())));
        port.setFlags(List.copyOf(listOf(dto.flags())));
        port.setRequiredTags(List.copyOf(listOf(dto.requiredTags())));
        port.setDisplayNameKey(StringNormalization.blankToNull(dto.displayNameKey()));
        port.setSource(source(dto.source()));
        UUID itemGuid = P4kGuids.parse(dto.itemGuid());
        port.setItemGuid(itemGuid);
        port.setGameItemId(itemGuid == null ? null : items.id(dto.itemGuid()));
        return port;
      }

      /**
       * Reads a port's {@code source}, reporting a value the import does not know once per ship.
       *
       * @param raw the wire value, or {@code null}
       * @return the stored source, or {@code null} for {@code null} and an unknown value
       */
      @Nullable
      private ShipLoadoutPortSource source(@Nullable String raw) {
        P4kPortSource source = P4kClosedSet.parse(P4kPortSource.class, raw);
        if (raw != null && source == null && unknownSources.add(raw)) {
          context.getReport().unknownValue(P4kKind.SHIP, ship, SOURCE, raw);
        }
        if (source == null) {
          return null;
        }
        return switch (source) {
          case LOADOUT -> ShipLoadoutPortSource.LOADOUT;
          case OVERRIDE -> ShipLoadoutPortSource.OVERRIDE;
          case PORT_DEFAULT -> ShipLoadoutPortSource.PORT_DEFAULT;
          case UNSET -> ShipLoadoutPortSource.UNSET;
          case VEHICLE -> ShipLoadoutPortSource.VEHICLE;
        };
      }
    }
  }

  /**
   * Flattens a port's item types into {@code Type} for a type without subtypes and {@code
   * Type.SubType} per subtype.
   *
   * @param types the catalogue's types
   * @return the stored types, in stored order
   */
  @NotNull
  private static List<String> types(@NotNull List<P4kPortTypeDto> types) {
    List<String> flat = new ArrayList<>();
    for (P4kPortTypeDto type : types) {
      if (type.type() == null) {
        continue;
      }
      List<String> subTypes = listOf(type.subTypes());
      if (subTypes.isEmpty()) {
        flat.add(type.type());
      } else {
        subTypes.forEach(sub -> flat.add(type.type() + SUB_TYPE_SEPARATOR + sub));
      }
    }
    return List.copyOf(flat);
  }

  /**
   * A ship the catalogue's {@code ships} lack, known to a report row by its GUID alone.
   *
   * @param guid the GUID text
   */
  private record GuidRecord(@Nullable String guid) implements P4kRecord {

    /**
     * Returns no class name.
     *
     * @return {@code null}
     */
    @Nullable
    @Override
    public String className() {
      return null;
    }

    /**
     * Returns no record path.
     *
     * @return {@code null}
     */
    @Nullable
    @Override
    public String recordPath() {
      return null;
    }

    /**
     * Returns no name.
     *
     * @return {@code null}
     */
    @Nullable
    @Override
    public P4kLocalisedText name() {
      return null;
    }
  }
}
