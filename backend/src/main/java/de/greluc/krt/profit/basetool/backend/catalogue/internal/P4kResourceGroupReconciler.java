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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kResourceGroupDto;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code resourceGroups} with {@code material_category}, the commodity
 * categories (REQ-P4K-025, ADR-0339): match by P4K GUID, then a class name and a name that each
 * identify one row and one record; the P4K leads the name, the German name, the class name and the
 * parent, a name another group shares told apart by the class name ({@link P4kResourceGroupNames}).
 * A group is created when a commodity record of the run names it as {@code typeGuid}, when the
 * {@code ResourceType} of a material without a group names it (REQ-P4K-073), or when it is an
 * ancestor of such a group; every other unmatched group is only reported. A stored group holding
 * the GUID of another listed group is no class-name or name candidate (REQ-P4K-072). The parent
 * links never form a cycle: a link that would make a group its own ancestor is left out. An APPLY
 * settles the group cases it no longer raises (REQ-P4K-016).
 */
@Component
@RequiredArgsConstructor
public class P4kResourceGroupReconciler {

  /** The reasons of the resource-group cases a run settles itself (REQ-P4K-016). */
  private static final List<P4kReviewReason> SETTLED_REASONS =
      List.of(P4kReviewReason.GUID_CONFLICT);

  private final MaterialCategoryRepository repository;
  private final MaterialRepository materialRepository;
  private final P4kRunResolvedCases resolvedCases;

  /**
   * Reconciles every resource group, indexes the matched and created ones for the commodities and,
   * on an APPLY, settles the group cases the run no longer raises.
   *
   * @param catalogue the catalogue, whose commodities decide which groups are created
   * @param types the catalogue's resource types, whose groups the materials without one need
   * @param context the run
   */
  public void reconcile(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kResourceTypes types,
      @NotNull P4kImportContext context) {
    Set<UUID> referenced =
        referenced(catalogue, P4kMaterialTypeGroups.needed(materialRepository.findAll(), types));
    P4kRowIndex<MaterialCategory> stored =
        new P4kRowIndex<>(
            repository.findAll(),
            _ -> null,
            MaterialCategory::getClassName,
            MaterialCategory::getName);
    P4kRowMatcher<MaterialCategory> matcher =
        new P4kRowMatcher<>(
            P4kKind.RESOURCE_GROUP,
            stored::byP4kUuid,
            _ -> Optional.empty(),
            _ -> null,
            "p4k_uuid",
            stored::byClassName,
            stored::byName,
            _ -> false,
            P4kListedGuidRules.of(catalogue.resourceGroups()));
    P4kResourceGroupNames names = P4kResourceGroupNames.of(catalogue.resourceGroups());
    Map<P4kResourceGroupDto, MaterialCategory> rows = new LinkedHashMap<>();
    for (P4kResourceGroupDto group : catalogue.resourceGroups()) {
      UUID parsed = group.parsedGuid();
      boolean creatable = parsed != null && referenced.contains(parsed);
      matcher.reconcile(
          group,
          group.className(),
          group.nameEn(),
          creatable,
          context,
          stored,
          (row, guid) -> {
            P4kRowWriter writer = new P4kRowWriter(context, P4kKind.RESOURCE_GROUP, group);
            writeNames(writer, row, group, names);
            context.indexResourceGroup(guid, row);
            rows.put(group, row);
            return writer.isChanged();
          },
          guid -> {
            MaterialCategory created = creatable ? create(group, names, guid, context) : null;
            if (created == null) {
              context.getReport().unmatched(P4kKind.RESOURCE_GROUP, group);
            } else {
              if (context.isApply()) {
                stored.add(created);
              }
              rows.put(group, created);
            }
          });
    }
    rows.forEach((group, row) -> linkParent(group, row, rows.size(), context));
    if (context.isApply()) {
      resolvedCases.settleRun(
          P4kKind.RESOURCE_GROUP,
          SETTLED_REASONS,
          catalogue.resourceGroups(),
          catalogue.head().isComplete(P4kKind.RESOURCE_GROUP),
          context);
    }
  }

  /**
   * Writes the names the P4K leads onto a matched group, a shared name told apart by the class
   * name.
   *
   * @param writer the row's writer
   * @param row the group
   * @param group the record
   * @param names the written names of the catalogue's groups
   */
  private static void writeNames(
      @NotNull P4kRowWriter writer,
      @NotNull MaterialCategory row,
      @NotNull P4kResourceGroupDto group,
      @NotNull P4kResourceGroupNames names) {
    writer.lead("name", row.getName(), names.english(group), row::setName, Function.identity());
    writer.lead(
        P4kReportColumns.NAME_DE,
        row.getNameDe(),
        names.german(group),
        row::setNameDe,
        Function.identity());
    writer.lead(
        P4kReportColumns.CLASS_NAME,
        row.getClassName(),
        group.className(),
        row::setClassName,
        Function.identity());
  }

  /**
   * Creates a referenced group, or in a preview counts it.
   *
   * @param group the record
   * @param names the written names of the catalogue's groups
   * @param guid the record's GUID, {@code null} when it has none
   * @param context the run
   * @return the created group (unsaved in a preview), or {@code null} when it has no GUID or name
   */
  @Nullable
  private MaterialCategory create(
      @NotNull P4kResourceGroupDto group,
      @NotNull P4kResourceGroupNames names,
      @Nullable UUID guid,
      @NotNull P4kImportContext context) {
    String name = names.english(group);
    if (guid == null || name == null) {
      return null;
    }
    MaterialCategory row = new MaterialCategory();
    row.setName(name);
    row.setNameDe(names.german(group));
    row.setClassName(group.className());
    if (context.isApply()) {
      context.stamp(P4kKind.RESOURCE_GROUP, group, row, guid);
      repository.save(row);
    }
    context.indexResourceGroup(guid, row);
    context.getReport().added(P4kKind.RESOURCE_GROUP, group);
    return row;
  }

  /**
   * Sets the parent the P4K names, unless the link would make the group its own ancestor; a
   * top-level group loses a parent it held.
   *
   * @param group the record
   * @param row the group's row
   * @param maxDepth an upper bound of the ancestor chain, the number of groups of the run
   * @param context the run
   */
  private static void linkParent(
      @NotNull P4kResourceGroupDto group,
      @NotNull MaterialCategory row,
      int maxDepth,
      @NotNull P4kImportContext context) {
    MaterialCategory parent = context.resourceGroup(group.parentGuid());
    if (parent != null && isAncestorOrSelf(row, parent, maxDepth)) {
      return;
    }
    MaterialCategory current = row.getParent();
    if (Objects.equals(parent, current)) {
      return;
    }
    if (context.isApply()) {
      row.setParent(parent);
    }
    context
        .getReport()
        .changed(
            P4kKind.RESOURCE_GROUP,
            group,
            P4kReportColumns.PARENT_ID,
            current == null ? null : current.getName(),
            parent == null ? null : parent.getName());
  }

  /**
   * Whether a group is the candidate itself or one of the candidate's ancestors.
   *
   * @param group the group
   * @param candidate the would-be parent
   * @param maxDepth the most links to follow
   * @return {@code true} when linking would form a cycle
   */
  private static boolean isAncestorOrSelf(
      @NotNull MaterialCategory group, @NotNull MaterialCategory candidate, int maxDepth) {
    MaterialCategory step = candidate;
    for (int depth = 0; step != null && depth <= maxDepth; depth++) {
      if (group.equals(step)) {
        return true;
      }
      step = step.getParent();
    }
    return false;
  }

  /**
   * Collects the groups the run creates when unmatched: every commodity's {@code typeGuid}, the
   * groups the materials without one need, and the ancestors of those groups in the catalogue.
   *
   * @param catalogue the catalogue
   * @param needed the groups the materials without one need
   * @return the GUIDs of the referenced groups
   */
  @NotNull
  private static Set<UUID> referenced(@NotNull P4kCatalogue catalogue, @NotNull Set<UUID> needed) {
    Map<UUID, UUID> parentOf = new HashMap<>();
    for (P4kResourceGroupDto group : catalogue.resourceGroups()) {
      UUID guid = group.parsedGuid();
      UUID parent = P4kGuids.parse(group.parentGuid());
      if (guid != null && parent != null) {
        parentOf.putIfAbsent(guid, parent);
      }
    }
    Set<UUID> starts = new HashSet<>(needed);
    for (P4kCommodityDto commodity : catalogue.commodities()) {
      UUID group = P4kGuids.parse(commodity.typeGuid());
      if (group != null) {
        starts.add(group);
      }
    }
    Set<UUID> referenced = new HashSet<>();
    for (UUID start : starts) {
      UUID step = start;
      while (step != null && referenced.add(step)) {
        step = parentOf.get(step);
      }
    }
    return referenced;
  }
}
