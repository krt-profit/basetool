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

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBlueprintCategoryDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Imports the catalogue's {@code blueprintCategories} into {@code blueprint_category} (REQ-P4K-027,
 * ADR-0340): the P4K GUID is the identity, the P4K leads the class name and the names, and every
 * listed category is created, as no other source lists one.
 */
@Component
@RequiredArgsConstructor
public class P4kBlueprintCategoryReconciler {

  private final BlueprintCategoryRepository repository;

  /**
   * Reconciles every category and indexes it for the blueprints of the run.
   *
   * @param categories the catalogue's blueprint categories
   * @param context the run
   */
  public void reconcile(
      @NotNull List<P4kBlueprintCategoryDto> categories, @NotNull P4kImportContext context) {
    for (P4kBlueprintCategoryDto category : categories) {
      UUID guid = category.parsedGuid();
      String className = category.className();
      if (guid == null || className == null || className.isBlank()) {
        context.getReport().unmatched(P4kKind.BLUEPRINT_CATEGORY, category);
        continue;
      }
      BlueprintCategory row = repository.findByP4kUuid(guid).orElse(null);
      if (row == null) {
        create(category, guid, className, context);
        continue;
      }
      P4kRowWriter writer = new P4kRowWriter(context, P4kKind.BLUEPRINT_CATEGORY, category);
      writer.lead(
          "class_name",
          row.getClassName(),
          category.className(),
          row::setClassName,
          Function.identity());
      writer.lead("name", row.getName(), category.nameEn(), row::setName, Function.identity());
      writer.lead(
          "name_de",
          row.getNameDe(),
          P4kFields.germanName(category.name()),
          row::setNameDe,
          Function.identity());
      context.stamp(P4kKind.BLUEPRINT_CATEGORY, category, row, guid);
      context.indexBlueprintCategory(guid, row);
      context.getReport().matched(P4kKind.BLUEPRINT_CATEGORY, writer.isChanged());
    }
  }

  /**
   * Creates a category, or in a preview counts it.
   *
   * @param category the record
   * @param guid the record's GUID
   * @param className the record's class name
   * @param context the run
   */
  private void create(
      @NotNull P4kBlueprintCategoryDto category,
      @NotNull UUID guid,
      @NotNull String className,
      @NotNull P4kImportContext context) {
    BlueprintCategory row = new BlueprintCategory();
    row.setClassName(className);
    row.setName(P4kLocalisedText.enOf(category.name()));
    row.setNameDe(P4kFields.germanName(category.name()));
    if (context.isApply()) {
      context.stamp(P4kKind.BLUEPRINT_CATEGORY, category, row, guid);
      repository.save(row);
    }
    context.indexBlueprintCategory(guid, row);
    context.getReport().added(P4kKind.BLUEPRINT_CATEGORY, category);
  }
}
