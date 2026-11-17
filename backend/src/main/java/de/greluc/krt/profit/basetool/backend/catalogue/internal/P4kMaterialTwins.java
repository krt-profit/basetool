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
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Retires the identity-less twins of a material that nothing references (REQ-P4K-077, REQ-P4K-078).
 * An invisible admin material without a UEX, P4K or SC Wiki identity whose name is the P4K name of
 * the record another material stands for, where an alias of any source maps that name to that
 * material, is deleted, so the material takes the name when the run matches it. An invisible
 * Wiki-only material without a UEX id whose name an alias of another source maps to one live
 * material without a Wiki UUID hands its Wiki identity to that material, which keeps its name and
 * prices, and is deleted.
 */
@Component
@RequiredArgsConstructor
class P4kMaterialTwins {

  private final MaterialExternalAliasRepository aliasRepository;
  private final P4kMaterialReferences references;
  private final P4kMaterialRetirement retirement;

  /**
   * Retires the unreferenced twins of an APPLY, or reports those a preview would.
   *
   * @param stored the stored materials
   * @param rules the commodity rules, which name the record a material stands for
   * @param context the run
   * @return the twins deleted, or in a preview those an APPLY would delete
   */
  @NotNull
  Set<Material> settle(
      @NotNull List<Material> stored,
      @NotNull P4kCommodityRules rules,
      @NotNull P4kImportContext context) {
    Map<UUID, Material> byId = new HashMap<>();
    stored.forEach(row -> byId.put(Objects.requireNonNull(row.getId()), row));
    Map<String, List<MaterialExternalAlias>> aliases = new HashMap<>();
    for (MaterialExternalAlias alias : aliasRepository.findAll()) {
      aliases
          .computeIfAbsent(P4kMatchKeys.fold(alias.getExternalName()), _ -> new ArrayList<>())
          .add(alias);
    }
    Map<UUID, Twin> twins = new LinkedHashMap<>();
    for (Material row : stored) {
      List<MaterialExternalAlias> named =
          aliases.getOrDefault(P4kMatchKeys.fold(row.getName()), List.of());
      Twin twin = manualTwin(row, named, byId, rules);
      if (twin == null) {
        twin = wikiTwin(row, named, byId);
      }
      if (twin != null) {
        twins.put(Objects.requireNonNull(row.getId()), twin);
      }
    }
    dropSharedWikiSurvivors(twins);
    if (twins.isEmpty()) {
      return Set.of();
    }
    Set<UUID> used = references.byAnyone(twins.keySet());
    Set<Material> leaving = Collections.newSetFromMap(new IdentityHashMap<>());
    twins.forEach((id, twin) -> {
      if (!used.contains(id)) {
        retire(twin, context);
        leaving.add(twin.row());
      }
    });
    return leaving;
  }

  /**
   * Finds the material an admin twin duplicates: the one material an alias of the twin's name maps
   * to whose record carries the twin's name as its P4K name.
   *
   * @param row a stored material
   * @param named the aliases of its name
   * @param byId the stored materials by id
   * @param rules the commodity rules
   * @return the twin, or {@code null} when the material is no admin twin or names no single one
   */
  @Nullable
  private static Twin manualTwin(
      @NotNull Material row,
      @NotNull List<MaterialExternalAlias> named,
      @NotNull Map<UUID, Material> byId,
      @NotNull P4kCommodityRules rules) {
    if (row.getSourceSystems() != MaterialSourceSystem.MANUAL
        || !isInvisibleWithoutUexId(row)
        || !row.isWithoutGameSource()) {
      return null;
    }
    String name = P4kMatchKeys.fold(row.getName());
    List<Twin> found = new ArrayList<>();
    for (Material survivor : survivors(row, named, _ -> true, byId)) {
      P4kCommodityDto record = rules.recordOf(survivor);
      if (record != null && Objects.equals(name, P4kMatchKeys.fold(record.displayName()))) {
        found.add(new Twin(row, survivor, record, P4kLeftoverRule.MANUAL_TWIN));
      }
    }
    return found.size() == 1 ? found.getFirst() : null;
  }

  /**
   * Finds the material a Wiki-only twin duplicates: the one live material without a Wiki UUID an
   * alias of another source than the SC Wiki maps the twin's name to.
   *
   * @param row a stored material
   * @param named the aliases of its name
   * @param byId the stored materials by id
   * @return the twin, or {@code null} when the material is no Wiki-only twin or names no single one
   */
  @Nullable
  private static Twin wikiTwin(
      @NotNull Material row,
      @NotNull List<MaterialExternalAlias> named,
      @NotNull Map<UUID, Material> byId) {
    if (row.getSourceSystems() != MaterialSourceSystem.WIKI_ONLY
        || !isInvisibleWithoutUexId(row)
        || row.getP4kUuid() != null
        || row.getScwikiUuid() == null) {
      return null;
    }
    Set<Material> survivors =
        survivors(
            row,
            named,
            alias -> alias.getSourceSystem() != MaterialExternalAliasSource.SCWIKI,
            byId);
    if (survivors.size() != 1) {
      return null;
    }
    Material survivor = survivors.iterator().next();
    return survivor.getScwikiUuid() == null && !survivor.isP4kRetired()
        ? new Twin(row, survivor, new P4kMaterialRecord(survivor), P4kLeftoverRule.WIKI_TWIN)
        : null;
  }

  /**
   * Whether a material is invisible and carries no UEX id.
   *
   * @param row the material
   * @return {@code true} when both hold
   */
  private static boolean isInvisibleWithoutUexId(@NotNull Material row) {
    return Boolean.FALSE.equals(row.getIsVisible()) && row.getIdCommodity() == null;
  }

  /**
   * Collects the other stored materials the given aliases of a material's name map to.
   *
   * @param row the material
   * @param named the aliases of its name
   * @param source which aliases count
   * @param byId the stored materials by id
   * @return the materials, in alias order
   */
  @NotNull
  private static Set<Material> survivors(
      @NotNull Material row,
      @NotNull List<MaterialExternalAlias> named,
      @NotNull Predicate<MaterialExternalAlias> source,
      @NotNull Map<UUID, Material> byId) {
    return named.stream()
        .filter(source)
        .map(alias -> byId.get(alias.getMaterial().getId()))
        .filter(Objects::nonNull)
        .filter(survivor -> !Objects.equals(survivor.getId(), row.getId()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Leaves out the Wiki-only twins whose material another Wiki-only twin names too, since only one
   * Wiki identity can move to it.
   *
   * @param twins the twins by id, updated
   */
  private static void dropSharedWikiSurvivors(@NotNull Map<UUID, Twin> twins) {
    Map<UUID, Long> perSurvivor =
        twins.values().stream()
            .filter(twin -> twin.rule() == P4kLeftoverRule.WIKI_TWIN)
            .collect(
                Collectors.groupingBy(
                    twin -> Objects.requireNonNull(twin.survivor().getId()),
                    Collectors.counting()));
    twins
        .values()
        .removeIf(twin ->
            twin.rule() == P4kLeftoverRule.WIKI_TWIN
                && perSurvivor.getOrDefault(twin.survivor().getId(), 0L) > 1);
  }

  /**
   * Deletes a twin; a Wiki-only twin first hands its Wiki identity to the material it duplicates,
   * reported {@code CHANGED scwiki_uuid}, which on an APPLY the material takes once the deletion is
   * flushed.
   *
   * @param twin the twin
   * @param context the run
   */
  private void retire(@NotNull Twin twin, @NotNull P4kImportContext context) {
    final Material row = twin.row();
    final UUID wikiUuid = row.getScwikiUuid();
    final String wikiKey = row.getScwikiKey();
    final String wikiSlug = row.getScwikiSlug();
    retirement.delete(row, twin.survivor(), twin.record(), twin.rule(), context);
    if (twin.rule() != P4kLeftoverRule.WIKI_TWIN) {
      return;
    }
    Material survivor = twin.survivor();
    context
        .getReport()
        .changed(
            P4kKind.COMMODITY,
            twin.record(),
            "scwiki_uuid",
            null,
            Objects.toString(wikiUuid, null));
    if (context.isApply()) {
      survivor.setScwikiUuid(wikiUuid);
      survivor.setScwikiKey(wikiKey);
      survivor.setScwikiSlug(wikiSlug);
      if (survivor.getSourceSystems() == MaterialSourceSystem.UEX_ONLY) {
        survivor.setSourceSystems(MaterialSourceSystem.BOTH);
      }
    }
  }

  /**
   * A twin, the material it duplicates and the rule that retires it.
   *
   * @param row the twin
   * @param survivor the material that stays
   * @param record the record the report rows name
   * @param rule the rule
   */
  private record Twin(
      @NotNull Material row,
      @NotNull Material survivor,
      @NotNull P4kRecord record,
      @NotNull P4kLeftoverRule rule) {}
}
