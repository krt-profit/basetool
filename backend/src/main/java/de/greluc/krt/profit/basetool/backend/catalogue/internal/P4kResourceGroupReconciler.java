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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kResourceGroupDto;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * parent. A group is created when a commodity record of the run names it as {@code typeGuid}, or
 * when it is an ancestor of such a group; every other unmatched group is only reported. The parent
 * links never form a cycle: a link that would make a group its own ancestor is left out.
 */
@Component
@RequiredArgsConstructor
public class P4kResourceGroupReconciler {

  private final MaterialCategoryRepository repository;

  /**
   * Reconciles every resource group and indexes the matched and created ones for the commodities.
   *
   * @param catalogue the catalogue, whose commodities decide which groups are created
   * @param context the run
   */
  public void reconcile(@NotNull P4kCatalogue catalogue, @NotNull P4kImportContext context) {
    Set<UUID> referenced = referenced(catalogue);
    P4kRowMatcher<MaterialCategory> matcher =
        new P4kRowMatcher<>(
            P4kKind.RESOURCE_GROUP,
            repository::findByP4kUuid,
            _ -> Optional.empty(),
            _ -> null,
            "p4k_uuid",
            repository::findByClassNameIgnoreCase,
            repository::findByNameIgnoreCase,
            _ -> false);
    Map<P4kResourceGroupDto, MaterialCategory> rows = new LinkedHashMap<>();
    for (P4kResourceGroupDto group : catalogue.resourceGroups()) {
      UUID parsed = group.parsedGuid();
      boolean creatable = parsed != null && referenced.contains(parsed);
      P4kRowMatcher.Match<MaterialCategory> match =
          matcher.match(group, group.className(), group.nameEn(), creatable, context);
      if (match
          instanceof P4kRowMatcher.Matched<MaterialCategory>(MaterialCategory row, UUID guid)) {
        P4kRowWriter writer = new P4kRowWriter(context, P4kKind.RESOURCE_GROUP, group);
        writeNames(writer, row, group);
        context.stamp(P4kKind.RESOURCE_GROUP, group, row, guid);
        context.indexResourceGroup(guid, row);
        rows.put(group, row);
        context.getReport().matched(P4kKind.RESOURCE_GROUP, writer.isChanged());
      } else if (match instanceof P4kRowMatcher.Unmatched<MaterialCategory>(UUID guid)) {
        MaterialCategory created = creatable ? create(group, guid, context) : null;
        if (created == null) {
          context.getReport().unmatched(P4kKind.RESOURCE_GROUP, group);
        } else {
          rows.put(group, created);
        }
      }
    }
    rows.forEach((group, row) -> linkParent(group, row, rows.size(), context));
  }

  /**
   * Writes the names the P4K leads onto a matched group.
   *
   * @param writer the row's writer
   * @param row the group
   * @param group the record
   */
  private static void writeNames(
      @NotNull P4kRowWriter writer,
      @NotNull MaterialCategory row,
      @NotNull P4kResourceGroupDto group) {
    writer.lead("name", row.getName(), group.displayName(), row::setName, Function.identity());
    writer.lead(
        "name_de",
        row.getNameDe(),
        P4kLocalisedText.deOf(group.name()),
        row::setNameDe,
        Function.identity());
    writer.lead(
        "class_name",
        row.getClassName(),
        group.className(),
        row::setClassName,
        Function.identity());
  }

  /**
   * Creates a referenced group, or in a preview counts it.
   *
   * @param group the record
   * @param guid the record's GUID, {@code null} when it has none
   * @param context the run
   * @return the created group (unsaved in a preview), or {@code null} when it has no GUID or name
   */
  @Nullable
  private MaterialCategory create(
      @NotNull P4kResourceGroupDto group, @Nullable UUID guid, @NotNull P4kImportContext context) {
    String name = group.displayName();
    if (guid == null || name == null) {
      return null;
    }
    MaterialCategory row = new MaterialCategory();
    row.setName(name);
    row.setNameDe(P4kLocalisedText.deOf(group.name()));
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
            "parent_id",
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
   * Collects the groups the run creates when unmatched: every commodity's {@code typeGuid} and the
   * ancestors of those groups in the catalogue.
   *
   * @param catalogue the catalogue
   * @return the GUIDs of the referenced groups
   */
  @NotNull
  private static Set<UUID> referenced(@NotNull P4kCatalogue catalogue) {
    Map<UUID, UUID> parentOf = new HashMap<>();
    for (P4kResourceGroupDto group : catalogue.resourceGroups()) {
      UUID guid = group.parsedGuid();
      UUID parent = P4kGuids.parse(group.parentGuid());
      if (guid != null && parent != null) {
        parentOf.putIfAbsent(guid, parent);
      }
    }
    Set<UUID> referenced = new HashSet<>();
    for (P4kCommodityDto commodity : catalogue.commodities()) {
      UUID step = P4kGuids.parse(commodity.typeGuid());
      while (step != null && referenced.add(step)) {
        step = parentOf.get(step);
      }
    }
    return referenced;
  }
}
