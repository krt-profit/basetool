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

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.util.EnumMap;
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

/**
 * Links the nine UEX location tables to the starmap tree on APPLY (REQ-P4K-035, REQ-P4K-060,
 * ADR-0345, ADR-0395): each row the import owns links as {@link StarmapLinkRun} decides, parents
 * first. A row left unlinked counts as unmatched and opens a review case unless the case could
 * settle nothing; a row an admin linked or unlinked by hand is left alone. A run closes the link
 * cases it no longer finds.
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
  private final LocationRepository locationRepository;
  private final P4kRunResolvedCases runResolvedCases;

  /**
   * Links every UEX location row, parents first.
   *
   * @param tree the starmap tree after the run's reconciliation
   * @param context the run
   */
  public void link(@NotNull P4kStarmapTree tree, @NotNull P4kImportContext context) {
    Map<UUID, UUID> guidById = new HashMap<>();
    tree.all().stream()
        .filter(o -> o.getId() != null)
        .forEach(o -> guidById.put(o.getId(), o.getP4kUuid()));
    Map<StarmapPlaceKind, List<StarmapPlaceRow>> rows = new EnumMap<>(StarmapPlaceKind.class);
    for (StarmapPlaceKind kind : StarmapPlaceKind.values()) {
      rows.put(kind, placeRows.rows(kind));
    }
    StarmapLinkRun run =
        new StarmapLinkRun(
            new StarmapCandidates(tree),
            new StarmapCaseRules(
                tree,
                rows.getOrDefault(StarmapPlaceKind.TERMINAL, List.of()),
                storagePlaces(guidById)),
            rows.getOrDefault(StarmapPlaceKind.PLANET, List.of()));
    Set<P4kRunResolvedCases.Found> found = new HashSet<>();
    for (StarmapPlaceKind kind : StarmapPlaceKind.values()) {
      Map<UUID, UUID> writes = new LinkedHashMap<>();
      for (StarmapPlaceRow row : rows.getOrDefault(kind, List.of())) {
        UUID link =
            row.manual()
                ? guidById.get(row.starmapObjectId())
                : linkRow(row, run, tree, guidById, found, writes, context);
        run.record(row, link);
      }
      if (context.isApply()) {
        placeRows.autoLink(kind, writes);
      }
    }
    if (context.isApply()) {
      runResolvedCases.settle(KIND, REASONS, found, context.getNow());
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
   * @param run the run's decisions
   * @param tree the starmap tree
   * @param guidById the GUIDs of the stored objects by id
   * @param found the cases this run found, extended
   * @param writes the links of the row's table the APPLY writes, extended by the row's
   * @param context the run
   * @return the GUID the row links to after the run, or {@code null}
   */
  @Nullable
  private UUID linkRow(
      @NotNull StarmapPlaceRow row,
      @NotNull StarmapLinkRun run,
      @NotNull P4kStarmapTree tree,
      @NotNull Map<UUID, UUID> guidById,
      @NotNull Set<P4kRunResolvedCases.Found> found,
      @NotNull Map<UUID, UUID> writes,
      @NotNull P4kImportContext context) {
    StarmapLinkRun.Decision decision = run.decide(row);
    UUID target = decision.target();
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
        writes.put(row.id(), object == null ? null : object.getId());
      }
    }
    if (target == null) {
      context.getReport().unmatched(KIND, record);
      P4kReviewReason reason = decision.caseReason();
      if (reason != null) {
        found.add(new P4kRunResolvedCases.Found(record.caseGuid(), reason));
        context.getReport().review(KIND, record, record.caseGuid(), reason, null);
      }
    }
    return target;
  }

  /**
   * Returns the GUIDs of the starmap objects a storage place sits on.
   *
   * @param guidById the GUIDs of the stored objects by id
   * @return the GUIDs of those objects the tree holds
   */
  @NotNull
  private Set<UUID> storagePlaces(@NotNull Map<UUID, UUID> guidById) {
    Set<UUID> guids = new HashSet<>();
    for (UUID objectId : locationRepository.findStarmapObjectIds()) {
      UUID guid = guidById.get(objectId);
      if (guid != null) {
        guids.add(guid);
      }
    }
    return guids;
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
