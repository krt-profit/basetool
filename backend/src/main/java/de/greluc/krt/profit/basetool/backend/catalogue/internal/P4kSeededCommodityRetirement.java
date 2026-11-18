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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
 * Retires the commodities an earlier seeding created that no rule would create any more and no
 * member row references (REQ-P4K-076): invisible seeded materials without a UEX id. When the Wiki
 * key of its record (REQ-P4K-070) finds another live material without a P4K GUID, that twin takes
 * the seeded row's P4K identity and the run matches it as usual; the seeded row is deleted, or,
 * while a catalogue row references it, hidden under its name followed by its GUID. Without a twin
 * the seeded row is hidden as an admin's hide would. A referenced one stays a {@code
 * SEEDED_NOT_CREATABLE} case.
 */
@Component
@RequiredArgsConstructor
class P4kSeededCommodityRetirement {

  /** The width of {@code material.name}. */
  private static final int NAME_WIDTH = 255;

  private final P4kMaterialReferences references;
  private final P4kMaterialRetirement retirement;

  /**
   * Retires the unreferenced seeded commodities of an APPLY, or reports those a preview would.
   *
   * @param stored the stored materials
   * @param join the run's join
   * @param rules the commodity rules over the stored materials
   * @param context the run
   * @return the seeded materials that leave the run's match because their twin took their identity
   */
  @NotNull
  Set<Material> retire(
      @NotNull List<Material> stored,
      @NotNull P4kCommodityJoin join,
      @NotNull P4kCommodityRules rules,
      @NotNull P4kImportContext context) {
    Map<UUID, Material> byWikiUuid = new HashMap<>();
    stored.forEach(row -> {
      if (row.getScwikiUuid() != null) {
        byWikiUuid.put(row.getScwikiUuid(), row);
      }
    });
    Map<UUID, Candidate> candidates = new LinkedHashMap<>();
    for (Material row : stored) {
      P4kCommodityDto record = seededRecord(row, join, rules);
      if (record != null) {
        candidates.put(
            Objects.requireNonNull(row.getId()),
            new Candidate(row, record, twin(row, record, join, byWikiUuid)));
      }
    }
    if (candidates.isEmpty()) {
      return Set.of();
    }
    Set<UUID> used = references.byMembers(candidates.keySet());
    candidates.keySet().removeAll(used);
    Set<UUID> twinned = new HashSet<>();
    candidates.forEach((id, candidate) -> {
      if (candidate.twin() != null) {
        twinned.add(id);
      }
    });
    final Set<UUID> catalogued = references.byCatalogue(twinned);
    Set<String> names = new HashSet<>();
    stored.forEach(row -> names.add(P4kMatchKeys.fold(row.getName())));
    Set<Material> leaving = Collections.newSetFromMap(new IdentityHashMap<>());
    candidates.forEach((id, candidate) -> {
      if (retire(candidate, catalogued.contains(id), names, context)) {
        leaving.add(candidate.row());
      }
    });
    return leaving;
  }

  /**
   * Retires one unreferenced seeded commodity: hidden without a twin, else deleted once its twin
   * takes its identity, or hidden under a freed name while a catalogue row references it.
   *
   * @param candidate the seeded commodity, its record and its twin
   * @param catalogued whether a catalogue row references it
   * @param names the folded names the stored materials carry
   * @param context the run
   * @return {@code true} when its twin takes its identity, so it leaves the run's match
   */
  private boolean retire(
      @NotNull Candidate candidate,
      boolean catalogued,
      @NotNull Set<String> names,
      @NotNull P4kImportContext context) {
    Material row = candidate.row();
    Material twin = candidate.twin();
    if (twin == null) {
      context.retire(P4kKind.COMMODITY, Objects.requireNonNull(row.getId()));
      retirement.hide(row, candidate.record(), P4kLeftoverRule.SEEDED_NOT_CREATABLE, context);
      return false;
    }
    if (!catalogued) {
      retirement.delete(
          row, twin, candidate.record(), P4kLeftoverRule.SEEDED_NOT_CREATABLE, context);
      return true;
    }
    String freedName = freeName(row, Objects.requireNonNull(row.getP4kUuid()), names);
    if (freedName == null) {
      return false;
    }
    retirement.handOver(row, freedName, twin, candidate.record(), context);
    return true;
  }

  /**
   * Returns the record of a seeded commodity no rule would create: an invisible material the
   * seeding created, without a UEX id, that holds the GUID of a listed record leading its
   * commodity.
   *
   * @param row a stored material
   * @param join the run's join
   * @param rules the commodity rules
   * @return the record, or {@code null} when the material is no such commodity
   */
  @Nullable
  private static P4kCommodityDto seededRecord(
      @NotNull Material row, @NotNull P4kCommodityJoin join, @NotNull P4kCommodityRules rules) {
    UUID guid = row.getP4kUuid();
    if (guid == null
        || row.getSourceSystems() != MaterialSourceSystem.P4K
        || !Boolean.FALSE.equals(row.getIsVisible())
        || row.getIdCommodity() != null
        || row.isP4kRetired()) {
      return null;
    }
    P4kCommodityDto record = rules.recordOf(row);
    return record != null
            && guid.equals(record.parsedGuid())
            && join.leads(record)
            && !join.isCreatable(record)
        ? record
        : null;
  }

  /**
   * Finds the twin a record's Wiki key names: another live material without a P4K GUID.
   *
   * @param row the seeded material
   * @param record its record
   * @param join the run's join
   * @param byWikiUuid the stored materials by SC Wiki UUID
   * @return the twin, or {@code null} when the record has no Wiki key of its own or it names none
   */
  @Nullable
  private static Material twin(
      @NotNull Material row,
      @NotNull P4kCommodityDto record,
      @NotNull P4kCommodityJoin join,
      @NotNull Map<UUID, Material> byWikiUuid) {
    UUID key = P4kCommodityRules.subtypeKey(join, record);
    Material twin = key == null ? null : byWikiUuid.get(key);
    return twin != null
            && !Objects.equals(twin.getId(), row.getId())
            && twin.getP4kUuid() == null
            && !twin.isP4kRetired()
        ? twin
        : null;
  }

  /**
   * Picks the name a seeded row that stays takes, so its twin can take the record's name: its own
   * name followed by its GUID in parentheses, which no other material carries as long as the GUID
   * is unique.
   *
   * @param row the seeded material
   * @param guid the GUID it holds
   * @param names the folded names the stored materials carry, updated with the picked one
   * @return the name, or {@code null} when it exceeds the column or a material carries it
   */
  @Nullable
  private static String freeName(
      @NotNull Material row, @NotNull UUID guid, @NotNull Set<String> names) {
    String freed = row.getName() + " (" + guid + ")";
    return freed.length() <= NAME_WIDTH && names.add(P4kMatchKeys.fold(freed)) ? freed : null;
  }

  /**
   * A seeded commodity, its record and its twin.
   *
   * @param row the seeded material
   * @param record the record of its GUID
   * @param twin the material the record's Wiki key names, or {@code null}
   */
  private record Candidate(
      @NotNull Material row, @NotNull P4kCommodityDto record, @Nullable Material twin) {}
}
