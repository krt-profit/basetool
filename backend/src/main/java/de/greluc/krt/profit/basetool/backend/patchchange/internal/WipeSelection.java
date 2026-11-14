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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeShare;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeExceptionMode;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeExceptionTarget;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeGroup;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Which stock rows a patch change's wipe options select (REQ-PATCH-010, REQ-PATCH-011): a row of an
 * exception's entry follows the exception, kept or wiped; every other row follows its group, wiped
 * when the group is selected. Every stock group and every exception is tallied, selected or not, so
 * the preview can show what each would touch.
 */
final class WipeSelection {

  /** The rows of each stock group that no exception names. */
  private final Map<WipeGroup, Bucket> groups;

  /** The rows of each exception's entry. */
  private final Map<WipeException, Bucket> exceptions;

  /**
   * The rows the options wipe, by the share the Lager's summary event counts them under: the groups
   * in declaration order, the wiping exceptions last. Built once and never changed.
   */
  private final @Unmodifiable Map<StockWipeShare, Set<UUID>> wiped;

  /** The members who hold a wiped row. */
  private final Set<UUID> wipedHolders;

  /**
   * Wraps the classified rows.
   *
   * @param groups the rows by stock group
   * @param exceptions the rows by exception
   * @param wiped the wiped rows by share, copied in the shares' order
   * @param wipedHolders the holders of the wiped rows
   */
  private WipeSelection(
      @NotNull Map<WipeGroup, Bucket> groups,
      @NotNull Map<WipeException, Bucket> exceptions,
      @NotNull Map<StockWipeShare, Set<UUID>> wiped,
      @NotNull Set<UUID> wipedHolders) {
    this.groups = groups;
    this.exceptions = exceptions;
    this.wiped = ordered(wiped);
    this.wipedHolders = wipedHolders;
  }

  /**
   * Copies the wiped rows into a map no one else holds, in the shares' order, each set copied too.
   *
   * @param wiped the wiped rows by share
   * @return the unmodifiable copy
   */
  private static @NotNull @Unmodifiable Map<StockWipeShare, Set<UUID>> ordered(
      @NotNull Map<StockWipeShare, Set<UUID>> wiped) {
    Map<StockWipeShare, Set<UUID>> copy = new LinkedHashMap<>();
    Stream.<StockWipeShare>concat(
            Arrays.stream(WipeGroup.values()), Stream.of(WipeExceptionShare.WIPE_EXCEPTIONS))
        .filter(wiped::containsKey)
        .forEach(share -> copy.put(share, Set.copyOf(wiped.get(share))));
    return Collections.unmodifiableMap(copy);
  }

  /**
   * Classifies the rows created before the live time by the options.
   *
   * @param rows the rows with their catalogue facts
   * @param options the wipe groups and exceptions
   * @return the selection
   */
  static @NotNull WipeSelection of(
      @NotNull Collection<StockWipeRow> rows, @NotNull PatchChangeOptions options) {
    Map<CatalogueEntry, WipeException> exceptionByEntry = new HashMap<>();
    options
        .wipeExceptions()
        .forEach(exception ->
            exceptionByEntry.put(
                new CatalogueEntry(exception.target(), exception.id()), exception));
    Map<WipeGroup, Bucket> groups = new EnumMap<>(WipeGroup.class);
    Map<WipeException, Bucket> exceptions = new HashMap<>();
    Map<StockWipeShare, Set<UUID>> wiped = new HashMap<>();
    Set<UUID> wipedHolders = new HashSet<>();
    for (StockWipeRow row : rows) {
      WipeException exception = exceptionByEntry.get(CatalogueEntry.of(row));
      boolean wipes;
      StockWipeShare share;
      if (exception != null) {
        exceptions.computeIfAbsent(exception, _ -> new Bucket()).add(row);
        wipes = exception.mode() == WipeExceptionMode.WIPE;
        share = WipeExceptionShare.WIPE_EXCEPTIONS;
      } else {
        WipeGroup group = WipeGroupTypes.groupOf(row);
        groups.computeIfAbsent(group, _ -> new Bucket()).add(row);
        wipes = options.wipeGroups().contains(group);
        share = group;
      }
      if (wipes) {
        wiped.computeIfAbsent(share, _ -> new HashSet<>()).add(row.id());
        wipedHolders.add(row.holderId());
      }
    }
    return new WipeSelection(groups, exceptions, wiped, wipedHolders);
  }

  /**
   * Returns every row the options wipe.
   *
   * @return the row ids
   */
  @NotNull
  @Unmodifiable
  Set<UUID> wipedRowIds() {
    return wiped.values().stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Returns the wiped rows by the share the Lager's summary event counts them under: their stock
   * group, or {@link WipeExceptionShare#WIPE_EXCEPTIONS}; the groups in declaration order, the
   * exceptions last.
   *
   * @return the wiped rows by share
   */
  @NotNull
  @Unmodifiable
  Map<StockWipeShare, Set<UUID>> wipedRowsByShare() {
    return wiped;
  }

  /**
   * Returns how many members hold a wiped row.
   *
   * @return the number of distinct holders
   */
  int wipedMembers() {
    return wipedHolders.size();
  }

  /**
   * Tallies a stock group's rows that no exception names, whether the group is selected or not.
   *
   * @param group the stock group
   * @return its rows and their holders
   */
  @NotNull
  Tally groupTally(@NotNull WipeGroup group) {
    return Bucket.tally(groups.get(group));
  }

  /**
   * Tallies the rows of an exception's entry.
   *
   * @param exception the exception
   * @return its entry's rows and their holders
   */
  @NotNull
  Tally exceptionTally(@NotNull WipeException exception) {
    return Bucket.tally(exceptions.get(exception));
  }

  /**
   * A number of rows and of the members who hold them.
   *
   * @param rows the number of rows
   * @param members the number of distinct holders
   */
  record Tally(long rows, long members) {}

  /**
   * The catalogue entry a row stocks, as an exception names it.
   *
   * @param target the catalogue
   * @param id the entry's id
   */
  private record CatalogueEntry(@NotNull WipeExceptionTarget target, @NotNull UUID id) {

    /**
     * Returns the entry of a row.
     *
     * @param row the row; exactly one of its material and game item is set
     * @return the entry
     */
    static @NotNull CatalogueEntry of(@NotNull StockWipeRow row) {
      return row.materialId() != null
          ? new CatalogueEntry(WipeExceptionTarget.MATERIAL, row.materialId())
          : new CatalogueEntry(
              WipeExceptionTarget.GAME_ITEM, Objects.requireNonNull(row.gameItemId()));
    }
  }

  /** The rows of one group or exception and their holders. */
  private static final class Bucket {

    /** The number of rows. */
    private long rows;

    /** The distinct holders. */
    private final Set<UUID> holders = new HashSet<>();

    /**
     * Adds a row.
     *
     * @param row the row
     */
    void add(@NotNull StockWipeRow row) {
      rows++;
      holders.add(row.holderId());
    }

    /**
     * Tallies a bucket.
     *
     * @param bucket the bucket, or {@code null} when nothing fell into it
     * @return its tally, zero for none
     */
    static @NotNull Tally tally(@Nullable Bucket bucket) {
      return bucket == null ? new Tally(0, 0) : new Tally(bucket.rows, bucket.holders.size());
    }
  }
}
