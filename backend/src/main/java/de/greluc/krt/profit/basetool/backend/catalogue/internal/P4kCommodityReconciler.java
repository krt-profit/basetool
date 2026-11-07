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
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code commodities} with {@code material} (REQ-P4K-024…026, ADR-0339):
 * match by the commodity's own GUID, the SC Wiki UUID and a name exactly one commodity carries; the
 * P4K leads the name, the group, the density, the {@code subtypeGuid} join and the refined-from
 * link, fills the empty description and refreshes the name key. A commodity is created only when a
 * record of the run references its {@code ResourceType} and that reference joins it alone; such a
 * row is visible at once. On a row UEX does not know, a commodity with a {@code refinedSubtypeGuid}
 * becomes RAW and its resolved target REFINED.
 */
@Component
@RequiredArgsConstructor
public class P4kCommodityReconciler {

  private final MaterialRepository materialRepository;
  private final P4kReviewCaseRepository reviewCaseRepository;

  /**
   * Reconciles every commodity; the resource groups must be reconciled first.
   *
   * @param catalogue the catalogue, whose blueprints and commodities reference the commodities
   * @param context the run
   * @return which materials the run gives a resource group and which it creates without one
   */
  @NotNull
  public Outcome reconcile(@NotNull P4kCatalogue catalogue, @NotNull P4kImportContext context) {
    P4kCommodityJoin join = P4kCommodityJoin.of(catalogue);
    join.reviewUnresolved(context);
    P4kRowIndex<Material> materials =
        new P4kRowIndex<>(
            materialRepository.findAll(), Material::getScwikiUuid, _ -> null, Material::getName);
    context.indexCommodityRows(materials);
    P4kRowMatcher<Material> matcher =
        new P4kRowMatcher<>(
            P4kKind.COMMODITY,
            materials::byP4kUuid,
            materials::byWikiUuid,
            Material::getScwikiUuid,
            "scwiki_uuid",
            _ -> List.of(),
            materials::byName,
            row -> row.getSourceSystems() == MaterialSourceSystem.P4K);
    Map<P4kCommodityDto, Reconciled> rows = new LinkedHashMap<>();
    for (P4kCommodityDto commodity : catalogue.commodities()) {
      boolean creatable = join.isCreatable(commodity);
      P4kRowMatcher.Match<Material> match =
          matcher.match(commodity, null, commodity.nameEn(), creatable, context);
      if (match instanceof P4kRowMatcher.Matched<Material>(Material row, UUID guid)) {
        P4kRowWriter writer = new P4kRowWriter(context, P4kKind.COMMODITY, commodity);
        enrich(writer, row, commodity, guid, context);
        writer.lead(
            P4kReportColumns.NAME_DE,
            row.getNameDe(),
            P4kFields.germanName(commodity.name()),
            row::setNameDe);
        if (creatable && row.getSourceSystems() == MaterialSourceSystem.P4K) {
          writer.lead("is_visible", row.getIsVisible(), true, row::setIsVisible, String::valueOf);
        }
        context.stamp(P4kKind.COMMODITY, commodity, row, guid);
        materials.add(row);
        rows.put(commodity, new Reconciled(row, writer));
      } else if (match instanceof P4kRowMatcher.Unmatched<Material>(UUID guid)) {
        Material created = creatable ? create(commodity, guid, context) : null;
        if (created == null) {
          context.getReport().unmatched(P4kKind.COMMODITY, commodity);
        } else {
          rows.put(commodity, new Reconciled(created, null));
        }
      }
    }
    P4kResourceAssignments assignments =
        new P4kResourceAssignments(materialRepository, reviewCaseRepository);
    rows.forEach((commodity, source) -> {
      P4kCommodityDto targetRecord = join.single(commodity.refinedSubtypeGuid());
      Reconciled target = targetRecord == null ? null : rows.get(targetRecord);
      if (target != null) {
        linkRefined(source, target, context);
      } else if (targetRecord == null) {
        linkAssigned(source, commodity, assignments, context);
      }
    });
    rows.values().stream()
        .map(Reconciled::writer)
        .filter(Objects::nonNull)
        .forEach(writer -> context.getReport().matched(P4kKind.COMMODITY, writer.isChanged()));
    Set<UUID> grouped = new HashSet<>();
    List<Material> createdWithoutGroup = new ArrayList<>();
    rows.forEach((commodity, reconciled) -> {
      Material row = reconciled.row();
      if (context.resourceGroup(commodity.typeGuid()) == null) {
        if (reconciled.writer() == null) {
          createdWithoutGroup.add(row);
        }
      } else if (row.getId() != null) {
        grouped.add(row.getId());
      }
    });
    return new Outcome(Set.copyOf(grouped), List.copyOf(createdWithoutGroup));
  }

  /**
   * What the commodities of a run leave for the group review (REQ-P4K-025).
   *
   * @param grouped the saved materials the run gives a resource group from their record
   * @param createdWithoutGroup the materials the run creates whose record names no group, unsaved
   *     in a preview
   */
  public record Outcome(
      @NotNull @Unmodifiable Set<UUID> grouped,
      @NotNull @Unmodifiable List<Material> createdWithoutGroup) {}

  /**
   * Writes the values the P4K leads or fills onto a matched material.
   *
   * @param writer the row's writer
   * @param row the material
   * @param commodity the record
   * @param guid the record's GUID
   * @param context the run
   */
  private void enrich(
      @NotNull P4kRowWriter writer,
      @NotNull Material row,
      @NotNull P4kCommodityDto commodity,
      @NotNull UUID guid,
      @NotNull P4kImportContext context) {
    String name = commodity.displayName();
    if (name != null && !name.equals(row.getName())) {
      if (nameHeldElsewhere(name, row, context)) {
        context
            .getReport()
            .review(P4kKind.COMMODITY, commodity, guid, P4kReviewReason.GUID_CONFLICT, row.getId());
      } else {
        writer.lead("name", row.getName(), name, row::setName, Function.identity());
      }
    }
    writer.lead(
        "category_id",
        row.getCategory(),
        context.resourceGroup(commodity.typeGuid()),
        row::setCategory,
        MaterialCategory::getName);
    writer.lead(
        "density_g_per_cc",
        row.getDensityGramPerCc(),
        commodity.densityGramsPerCubicCentimetre(),
        row::setDensityGramPerCc,
        String::valueOf);
    writer.lead(
        "p4k_subtype_uuid",
        row.getP4kSubtypeUuid(),
        P4kGuids.parse(commodity.subtypeGuid()),
        row::setP4kSubtypeUuid,
        UUID::toString);
    if (row.getIdCommodity() == null && commodity.refinedSubtypeGuid() != null) {
      writer.lead("type", row.getType(), MaterialType.RAW, row::setType, MaterialType::name);
    }
    writer.fillGameText(
        "description",
        row.getDescription(),
        P4kLocalisedText.enOf(commodity.description()),
        row::setDescription);
    writer.nameKey(row.getNameKey(), P4kLocalisedText.keyOf(commodity.name()), row::setNameKey);
  }

  /**
   * Whether another material already holds a name, ignoring case; the name is unique.
   *
   * @param name the name the P4K leads
   * @param row the material that would take it
   * @param context the run
   * @return {@code true} when writing it would collide
   */
  private static boolean nameHeldElsewhere(
      @NotNull String name, @NotNull Material row, @NotNull P4kImportContext context) {
    return context.commodityRows().byName(name).stream()
        .anyMatch(other -> !Objects.equals(other.getId(), row.getId()));
  }

  /**
   * Creates a referenced commodity, visible at once, or in a preview counts it (REQ-P4K-026).
   *
   * @param commodity the record
   * @param guid the record's GUID, {@code null} when it has none
   * @param context the run
   * @return the created material (unsaved in a preview), or {@code null} when it has no GUID or
   *     name, or its name is taken
   */
  @Nullable
  private Material create(
      @NotNull P4kCommodityDto commodity, @Nullable UUID guid, @NotNull P4kImportContext context) {
    String name = commodity.displayName();
    if (guid == null || name == null || !context.commodityRows().byName(name).isEmpty()) {
      return null;
    }
    Material row = new Material();
    row.setName(name);
    row.setNameDe(P4kFields.germanName(commodity.name()));
    row.setType(commodity.refinedSubtypeGuid() == null ? MaterialType.NO_REFINE : MaterialType.RAW);
    row.setSourceSystems(MaterialSourceSystem.P4K);
    row.setIsVisible(true);
    row.setCategory(context.resourceGroup(commodity.typeGuid()));
    Double density = commodity.densityGramsPerCubicCentimetre();
    if (density != null) {
      row.setDensityGramPerCc(density);
    }
    row.setP4kSubtypeUuid(P4kGuids.parse(commodity.subtypeGuid()));
    row.setDescription(P4kLocalisedText.enOf(commodity.description()));
    row.setNameKey(P4kFields.storedNameKey(P4kLocalisedText.keyOf(commodity.name())));
    if (context.isApply()) {
      context.stamp(P4kKind.COMMODITY, commodity, row, guid);
      context.commodityRows().add(materialRepository.save(row));
    }
    context.getReport().added(P4kKind.COMMODITY, commodity);
    return row;
  }

  /**
   * Links a material to the material an admin assigned to its {@code refinedSubtypeGuid}, which
   * joins no commodity of the catalogue (REQ-P4K-024); the assigned material's type stays the
   * admin's.
   *
   * @param source the refining material
   * @param commodity the source's record
   * @param assignments the admins' resource assignments
   * @param context the run
   */
  private static void linkAssigned(
      @NotNull Reconciled source,
      @NotNull P4kCommodityDto commodity,
      @NotNull P4kResourceAssignments assignments,
      @NotNull P4kImportContext context) {
    UUID refinedSubtype = P4kGuids.parse(commodity.refinedSubtypeGuid());
    Material assigned = refinedSubtype == null ? null : assignments.material(refinedSubtype);
    if (assigned == null) {
      return;
    }
    Material row = source.row();
    source.write(
        "refined_material_id",
        row.getRefinedMaterial(),
        assigned,
        row::setRefinedMaterial,
        Material::getName,
        context);
  }

  /**
   * Links a material to the commodity it refines into and, where UEX does not know the target,
   * marks the target REFINED.
   *
   * @param source the refining material
   * @param target the material its {@code refinedSubtypeGuid} joins
   * @param context the run
   */
  private static void linkRefined(
      @NotNull Reconciled source, @NotNull Reconciled target, @NotNull P4kImportContext context) {
    Material row = source.row();
    source.write(
        "refined_material_id",
        row.getRefinedMaterial(),
        target.row(),
        row::setRefinedMaterial,
        Material::getName,
        context);
    Material refined = target.row();
    if (refined.getIdCommodity() == null) {
      target.write(
          "type",
          refined.getType(),
          MaterialType.REFINED,
          refined::setType,
          MaterialType::name,
          context);
    }
  }

  /**
   * A material the run matched or created.
   *
   * @param row the material
   * @param writer the writer of a matched row; {@code null} for a row the run created
   */
  private record Reconciled(@NotNull Material row, @Nullable P4kRowWriter writer) {

    /**
     * Writes a value the P4K leads: through the writer on a matched row, and directly on a row the
     * run created, whose creation the report already lists.
     *
     * @param column the column written
     * @param current the row's value
     * @param incoming the catalogue's value
     * @param setter writes the value
     * @param label renders a value for the report
     * @param context the run
     * @param <V> the value type
     */
    <V> void write(
        @NotNull String column,
        @Nullable V current,
        @NotNull V incoming,
        @NotNull Consumer<V> setter,
        @NotNull Function<V, String> label,
        @NotNull P4kImportContext context) {
      if (writer != null) {
        writer.lead(column, current, incoming, setter, label);
      } else if (context.isApply()) {
        setter.accept(incoming);
      }
    }
  }
}
