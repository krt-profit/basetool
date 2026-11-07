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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Links the nine UEX location tables to the starmap tree on APPLY (REQ-P4K-035, ADR-0345): a row
 * links to the one object whose normalised English name equals its own, whose type belongs to the
 * row's type families and which lies below the row's nearest linked parent. Several candidates or
 * none leave the row unlinked, count it as unmatched and open a review case; a row an admin linked
 * or unlinked by hand is left alone. A run closes the link cases it no longer finds.
 */
@Component
@RequiredArgsConstructor
public class P4kStarmapLinker {

  /** The kind the link cases and report rows belong to. */
  private static final P4kKind KIND = P4kKind.STARMAP_OBJECT;

  /** The reasons this linker owns. */
  private static final List<P4kReviewReason> REASONS =
      List.of(P4kReviewReason.STARMAP_LINK_AMBIGUOUS, P4kReviewReason.STARMAP_LINK_NOT_FOUND);

  /** Prefixes the row a case GUID is derived from. */
  private static final String CASE_GUID_PREFIX = "starmap-link:";

  private final StarmapPlaceRows placeRows;
  private final P4kRunResolvedCases runResolvedCases;

  /**
   * Links every UEX location row, parents first.
   *
   * @param tree the starmap tree after the run's reconciliation
   * @param context the run
   */
  public void link(@NotNull P4kStarmapTree tree, @NotNull P4kImportContext context) {
    Map<String, List<StarmapObject>> byName = indexByName(tree);
    Map<UUID, UUID> guidById = new HashMap<>();
    tree.all().stream()
        .filter(o -> o.getId() != null)
        .forEach(o -> guidById.put(o.getId(), o.getP4kUuid()));
    Map<StarmapPlaceKind, Map<String, UUID>> linked = new EnumMap<>(StarmapPlaceKind.class);
    Set<P4kRunResolvedCases.Found> found = new HashSet<>();
    for (StarmapPlaceKind kind : StarmapPlaceKind.values()) {
      Map<String, UUID> linkedOfKind = new HashMap<>();
      linked.put(kind, linkedOfKind);
      for (StarmapPlaceRow row : placeRows.rows(kind)) {
        UUID link =
            row.manual()
                ? guidById.get(row.starmapObjectId())
                : linkRow(row, tree, byName, linked, guidById, found, context);
        if (link != null && row.name() != null) {
          linkedOfKind.put(StarmapNames.key(row.starSystem(), row.name()), link);
        }
      }
    }
    if (context.isApply()) {
      runResolvedCases.closeUnfound(KIND, REASONS, found, context.getNow());
    }
  }

  /**
   * Derives the stable GUID of a row's link case.
   *
   * @param kind the table
   * @param rowId the row
   * @return a name-based UUID of the row
   */
  @NotNull
  static UUID caseGuid(@NotNull StarmapPlaceKind kind, @NotNull UUID rowId) {
    return P4kRunResolvedCases.caseGuid(CASE_GUID_PREFIX + kind.name() + ":" + rowId);
  }

  /**
   * Links one row the import owns, reporting a changed link, an unmatched row and its case.
   *
   * @param row the row
   * @param tree the starmap tree
   * @param byName the linkable objects by normalised English name
   * @param linked the GUIDs the rows of earlier kinds link to, by system and name
   * @param guidById the GUIDs of the stored objects by id
   * @param found the cases this run found, extended
   * @param context the run
   * @return the GUID the row links to after the run, or {@code null}
   */
  @Nullable
  private UUID linkRow(
      @NotNull StarmapPlaceRow row,
      @NotNull P4kStarmapTree tree,
      @NotNull Map<String, List<StarmapObject>> byName,
      @NotNull Map<StarmapPlaceKind, Map<String, UUID>> linked,
      @NotNull Map<UUID, UUID> guidById,
      @NotNull Set<P4kRunResolvedCases.Found> found,
      @NotNull P4kImportContext context) {
    UUID ancestor = row.nearestLinkedParent(linked);
    String name = StarmapNames.normalise(row.name());
    List<UUID> candidates =
        name == null
            ? List.of()
            : byName.getOrDefault(name, List.of()).stream()
                .filter(o -> row.kind().accepts(o.getType()))
                .map(StarmapObject::getP4kUuid)
                .filter(guid -> ancestor == null || tree.isBelow(guid, ancestor))
                .toList();
    UUID target = candidates.size() == 1 ? candidates.getFirst() : null;
    UUID current = guidById.get(row.starmapObjectId());
    PlaceRecord record = new PlaceRecord(row);
    if (!Objects.equals(current, target)) {
      context
          .getReport()
          .changed(
              KIND,
              record,
              row.kind().getTable() + ".starmap_object_id",
              current == null ? null : current.toString(),
              target == null ? null : target.toString());
      if (context.isApply()) {
        StarmapObject object = tree.object(target);
        placeRows.autoLink(row.kind(), row.id(), object == null ? null : object.getId());
      }
    }
    if (target == null) {
      P4kReviewReason reason =
          candidates.isEmpty()
              ? P4kReviewReason.STARMAP_LINK_NOT_FOUND
              : P4kReviewReason.STARMAP_LINK_AMBIGUOUS;
      P4kRunResolvedCases.Found finding = new P4kRunResolvedCases.Found(record.caseGuid(), reason);
      found.add(finding);
      context.getReport().unmatched(KIND, record);
      context.getReport().review(KIND, record, record.caseGuid(), reason, null);
      if (context.isApply()) {
        runResolvedCases.reopenIfRunResolved(KIND, finding);
      }
    }
    return target;
  }

  /**
   * Indexes the objects a row can link to by normalised English name; a stamped or hidden object
   * links nothing new.
   *
   * @param tree the starmap tree
   * @return the objects by name
   */
  @NotNull
  private static Map<String, List<StarmapObject>> indexByName(@NotNull P4kStarmapTree tree) {
    Map<String, List<StarmapObject>> byName = new HashMap<>();
    for (StarmapObject object : tree.all()) {
      String name = StarmapNames.normalise(object.getName());
      if (name != null && !object.isP4kRetired()) {
        byName.computeIfAbsent(name, _ -> new ArrayList<>()).add(object);
      }
    }
    return byName;
  }

  /**
   * A UEX location row as its report rows and review case name it: its table as class name, its UEX
   * name as name, which is no game text.
   *
   * @param row the row
   */
  private record PlaceRecord(@NotNull StarmapPlaceRow row) implements P4kRecord {

    /**
     * Returns the case GUID of the row.
     *
     * @return the GUID
     */
    @NotNull
    UUID caseGuid() {
      return P4kStarmapLinker.caseGuid(row.kind(), row.id());
    }

    @Override
    public @NotNull String guid() {
      return caseGuid().toString();
    }

    @Override
    public @NotNull String className() {
      return row.kind().getTable();
    }

    @Override
    public @Nullable String recordPath() {
      return null;
    }

    @Override
    public @NotNull P4kLocalisedText name() {
      return new P4kLocalisedText(null, row.name(), null);
    }
  }
}
