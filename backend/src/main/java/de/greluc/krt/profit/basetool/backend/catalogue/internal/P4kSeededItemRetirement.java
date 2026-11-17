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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemPlayability;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Retires the items an earlier seeding created whose record the seeding rule would now only count —
 * not playable and neither sold in a shop nor produced by a blueprint — and that no member row
 * references (REQ-P4K-056): each is hidden as an admin's hide would. A referenced one stays a
 * {@code SEEDED_NOT_CREATABLE} case.
 */
@Component
@RequiredArgsConstructor
class P4kSeededItemRetirement {

  private final List<GameItemReferenceParticipant> participants;

  /**
   * Retires the unreferenced seeded items of an APPLY, or reports those a preview would.
   *
   * @param stored the stored items
   * @param items the catalogue's items
   * @param obtainable the GUIDs of the items a shop sells or a blueprint produces
   * @param context the run
   */
  void retire(
      @NotNull List<GameItem> stored,
      @NotNull List<P4kItemDto> items,
      @NotNull Set<UUID> obtainable,
      @NotNull P4kImportContext context) {
    Map<UUID, P4kItemDto> records = new HashMap<>();
    items.forEach(item -> {
      UUID guid = item.parsedGuid();
      if (guid != null) {
        records.putIfAbsent(guid, item);
      }
    });
    Map<UUID, Candidate> candidates = new HashMap<>();
    for (GameItem row : stored) {
      UUID guid = row.getP4kUuid();
      P4kItemDto record = guid == null ? null : records.get(guid);
      if (record != null
          && row.getSourceSystems() == GameItemSourceSystem.P4K
          && !row.isP4kRetired()
          && P4kSeeding.forItem(
                  P4kClosedSet.parse(P4kItemPlayability.class, record.playability()),
                  obtainable.contains(guid))
              == P4kSeeding.COUNT_ONLY) {
        candidates.put(Objects.requireNonNull(row.getId()), new Candidate(row, record));
      }
    }
    if (candidates.isEmpty()) {
      return;
    }
    Set<UUID> referenced = new HashSet<>();
    participants.forEach(
        participant -> referenced.addAll(participant.referencedGameItems(candidates.keySet())));
    candidates.forEach((id, candidate) -> {
      if (!referenced.contains(id)) {
        context.retire(P4kKind.ITEM, id);
        hide(candidate, context);
      }
    });
  }

  /**
   * Hides a retired item as an admin's hide would (REQ-P4K-016).
   *
   * @param candidate the item and its record
   * @param context the run
   */
  private static void hide(@NotNull Candidate candidate, @NotNull P4kImportContext context) {
    context
        .getReport()
        .changed(
            P4kKind.ITEM, candidate.record(), "p4k_hidden_at", null, context.getNow().toString());
    if (context.isApply()) {
      candidate.row().setP4kHiddenAt(context.getNow());
    }
  }

  /**
   * A seeded item and the record it holds.
   *
   * @param row the item
   * @param record the catalogue record of its GUID
   */
  private record Candidate(@NotNull GameItem row, @NotNull P4kItemDto record) {}
}
