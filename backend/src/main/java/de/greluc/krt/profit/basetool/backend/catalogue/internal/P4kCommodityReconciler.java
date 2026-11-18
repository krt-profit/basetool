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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code commodities} with {@code material} (REQ-P4K-024…026, ADR-0339,
 * ADR-0396): match by the commodity's own GUID, its SC Wiki key ({@link P4kCommodityRules}) and a
 * name exactly one commodity carries, the packagings of one commodity counting once; the P4K leads
 * the name, the group, the density, the {@code subtypeGuid} join and the refined-from link, fills
 * the empty description and refreshes the name key. A commodity is created only when a record of
 * the run references its {@code ResourceType} and that reference joins it alone; such a row is
 * visible at once. On a row UEX does not know, a commodity with a {@code refinedSubtypeGuid}
 * becomes RAW and its resolved target REFINED. A group or refined-from link an admin overrides
 * ({@link MaterialP4kOverride}) is kept, and a differing P4K value is reported as {@code
 * P4K_DISAGREEMENT}. A material no record reconciles takes the group of its {@code ResourceType}
 * ({@link P4kMaterialTypeGroups}), and an APPLY settles the commodity cases it no longer raises.
 * Before the match the leftover materials nothing references are retired ({@link
 * P4kSeededCommodityRetirement}, {@link P4kMaterialTwins}).
 */
@Component
@RequiredArgsConstructor
public class P4kCommodityReconciler {

  /** The {@code external_sync_report} aggregate of a commodity disagreement. */
  private static final String REPORT_AGGREGATE = "commodity";

  /** The reasons of the commodity cases a run settles itself (REQ-P4K-016). */
  private static final List<P4kReviewReason> SETTLED_REASONS =
      List.of(
          P4kReviewReason.GUID_CONFLICT,
          P4kReviewReason.SEEDED_NOT_CREATABLE,
          P4kReviewReason.RESOURCE_JOIN_NONE,
          P4kReviewReason.RESOURCE_JOIN_SEVERAL);

  private final MaterialRepository materialRepository;
  private final P4kReviewCaseRepository reviewCaseRepository;
  private final SyncReportService syncReportService;
  private final P4kRunResolvedCases resolvedCases;
  private final P4kSeededCommodityRetirement seededRetirement;
  private final P4kMaterialTwins twins;

  /**
   * Retires the leftover materials, reconciles every commodity, groups the other materials by their
   * {@code ResourceType} and, on an APPLY, settles the commodity cases the run no longer raises;
   * the resource groups must be reconciled first.
   *
   * @param catalogue the catalogue, whose blueprints and commodities reference the commodities
   * @param types the catalogue's resource types
   * @param context the run
   * @return which materials the run gives a resource group, which the game files know and which it
   *     creates without a group
   */
  @NotNull
  public Outcome reconcile(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kResourceTypes types,
      @NotNull P4kImportContext context) {
    P4kCommodityJoin join = P4kCommodityJoin.of(catalogue, types);
    join.reviewUnresolved(context);
    context.indexRepeated(P4kKind.COMMODITY, join.commodities());
    List<Material> stored = retireLeftovers(materialRepository.findAll(), catalogue, join, context);
    P4kRowIndex<Material> materials =
        new P4kRowIndex<>(stored, Material::getScwikiUuid, _ -> null, Material::getName);
    context.indexCommodityRows(materials);
    Map<P4kCommodityDto, Reconciled> rows =
        match(
            catalogue, join, P4kCommodityRules.of(catalogue.commodities(), join, stored), context);
    link(rows, join, context);
    rows.values().stream()
        .map(Reconciled::writer)
        .filter(Objects::nonNull)
        .forEach(writer -> context.getReport().matched(P4kKind.COMMODITY, writer.isChanged()));
    Outcome outcome = outcome(catalogue, types, stored, rows, context);
    if (context.isApply()) {
      resolvedCases.settleRun(
          P4kKind.COMMODITY,
          SETTLED_REASONS,
          catalogue.commodities(),
          catalogue.head().isComplete(P4kKind.COMMODITY),
          context);
    }
    return outcome;
  }

  /**
   * Retires the leftover materials before the match: the seeded commodities no rule would create
   * (REQ-P4K-076), then the admin and Wiki-only twins (REQ-P4K-077, REQ-P4K-078).
   *
   * @param loaded the stored materials
   * @param catalogue the catalogue
   * @param join the run's join
   * @param context the run
   * @return the materials the run matches: the stored ones without those whose identity a twin took
   *     or that a twin replaces
   */
  @NotNull
  private List<Material> retireLeftovers(
      @NotNull List<Material> loaded,
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kCommodityJoin join,
      @NotNull P4kImportContext context) {
    P4kCommodityRules rules = P4kCommodityRules.of(catalogue.commodities(), join, loaded);
    Set<Material> leaving = Collections.newSetFromMap(new IdentityHashMap<>());
    leaving.addAll(seededRetirement.retire(loaded, join, rules, context));
    leaving.addAll(twins.settle(without(loaded, leaving), rules, context));
    return without(loaded, leaving);
  }

  /**
   * Leaves the given materials out of a list.
   *
   * @param rows the materials
   * @param left the materials to leave out
   * @return the other materials, in list order
   */
  @NotNull
  @Unmodifiable
  private static List<Material> without(@NotNull List<Material> rows, @NotNull Set<Material> left) {
    return rows.stream().filter(row -> !left.contains(row)).toList();
  }

  /**
   * Matches every leading commodity record, enriches and stamps its row and creates a referenced
   * one; a packaging another record leads is covered (REQ-P4K-071).
   *
   * @param catalogue the catalogue
   * @param join the run's join
   * @param rules the commodity rules of the match
   * @param context the run
   * @return the matched and created materials per record, in catalogue order
   */
  @NotNull
  private Map<P4kCommodityDto, Reconciled> match(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kCommodityJoin join,
      @NotNull P4kCommodityRules rules,
      @NotNull P4kImportContext context) {
    P4kRowIndex<Material> materials = context.commodityRows();
    P4kRowMatcher<Material> matcher =
        new P4kRowMatcher<>(
            P4kKind.COMMODITY,
            materials::byP4kUuid,
            materials::byWikiUuid,
            Material::getScwikiUuid,
            "scwiki_uuid",
            _ -> List.of(),
            materials::byName,
            row -> row.getSourceSystems() == MaterialSourceSystem.P4K,
            rules);
    Map<P4kCommodityDto, Reconciled> rows = new LinkedHashMap<>();
    for (P4kCommodityDto commodity : catalogue.commodities()) {
      if (!join.leads(commodity)) {
        context.getReport().covered(P4kKind.COMMODITY, commodity);
        continue;
      }
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
    return rows;
  }

  /**
   * Links every reconciled material to the material its {@code refinedSubtypeGuid} joins, or to the
   * material an admin assigned to that resource.
   *
   * @param rows the reconciled materials per record
   * @param join the run's join
   * @param context the run
   */
  private void link(
      @NotNull Map<P4kCommodityDto, Reconciled> rows,
      @NotNull P4kCommodityJoin join,
      @NotNull P4kImportContext context) {
    P4kResourceAssignments assignments =
        new P4kResourceAssignments(materialRepository, reviewCaseRepository);
    rows.forEach((commodity, source) -> {
      P4kCommodityDto targetRecord = join.single(commodity.refinedSubtypeGuid());
      Reconciled target = targetRecord == null ? null : rows.get(targetRecord);
      if (target != null) {
        linkRefined(source, commodity, target, context);
      } else if (targetRecord == null) {
        linkAssigned(source, commodity, assignments, context);
      }
    });
  }

  /**
   * Collects what the group review needs, after the materials no record reconciled took the group
   * of their {@code ResourceType} (REQ-P4K-073).
   *
   * @param catalogue the catalogue
   * @param types the catalogue's resource types
   * @param stored the stored materials
   * @param rows the reconciled materials per record
   * @param context the run
   * @return the outcome
   */
  @NotNull
  private static Outcome outcome(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kResourceTypes types,
      @NotNull List<Material> stored,
      @NotNull Map<P4kCommodityDto, Reconciled> rows,
      @NotNull P4kImportContext context) {
    Set<UUID> grouped = new HashSet<>();
    Set<UUID> known = new HashSet<>();
    List<Material> createdWithoutGroup = new ArrayList<>();
    Set<Material> reconciled = Collections.newSetFromMap(new IdentityHashMap<>());
    rows.forEach((commodity, row) -> {
      Material material = row.row();
      reconciled.add(material);
      if (material.getId() != null) {
        known.add(material.getId());
      }
      if (context.resourceGroup(commodity.typeGuid()) == null) {
        if (row.writer() == null) {
          createdWithoutGroup.add(material);
        }
      } else if (material.getId() != null && !material.isResourceGroupManual()) {
        grouped.add(material.getId());
      }
    });
    List<Material> others = stored.stream().filter(row -> !reconciled.contains(row)).toList();
    grouped.addAll(P4kMaterialTypeGroups.assign(others, types, context));
    Set<UUID> listed =
        catalogue.commodities().stream()
            .map(P4kCommodityDto::parsedGuid)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    others.stream()
        .filter(row -> row.getId() != null && P4kMaterialTypeGroups.isKnown(row, listed, types))
        .forEach(row -> known.add(row.getId()));
    return new Outcome(Set.copyOf(grouped), Set.copyOf(known), List.copyOf(createdWithoutGroup));
  }

  /**
   * What the commodities of a run leave for the group review (REQ-P4K-025, REQ-P4K-074) and the
   * hide of the materials no game source knows (REQ-P4K-075).
   *
   * @param grouped the saved materials the run gives a resource group from their record or their
   *     {@code ResourceType}; a material whose group an admin overrides is left out, its stored
   *     group decides
   * @param known the saved materials the game files know: those a record of the run reconciled,
   *     those holding a listed commodity's GUID and those standing for a {@code ResourceType}
   * @param createdWithoutGroup the materials the run creates whose record names no group, unsaved
   *     in a preview
   */
  public record Outcome(
      @NotNull @Unmodifiable Set<UUID> grouped,
      @NotNull @Unmodifiable Set<UUID> known,
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
    MaterialCategory group = context.resourceGroup(commodity.typeGuid());
    if (!overridden(
        MaterialP4kOverride.RESOURCE_GROUP,
        row,
        commodity,
        row.getCategory(),
        group,
        MaterialCategory::getName,
        context)) {
      writer.lead(
          MaterialP4kOverride.RESOURCE_GROUP.getColumn(),
          row.getCategory(),
          group,
          row::setCategory,
          MaterialCategory::getName);
    }
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
  private void linkAssigned(
      @NotNull Reconciled source,
      @NotNull P4kCommodityDto commodity,
      @NotNull P4kResourceAssignments assignments,
      @NotNull P4kImportContext context) {
    UUID refinedSubtype = P4kGuids.parse(commodity.refinedSubtypeGuid());
    Material assigned = refinedSubtype == null ? null : assignments.material(refinedSubtype);
    if (assigned != null) {
      linkRefinedMaterial(source, commodity, assigned, context);
    }
  }

  /**
   * Links a material to the commodity it refines into and, where UEX does not know the target,
   * marks the target REFINED.
   *
   * @param source the refining material
   * @param commodity the source's record
   * @param target the material its {@code refinedSubtypeGuid} joins
   * @param context the run
   */
  private void linkRefined(
      @NotNull Reconciled source,
      @NotNull P4kCommodityDto commodity,
      @NotNull Reconciled target,
      @NotNull P4kImportContext context) {
    linkRefinedMaterial(source, commodity, target.row(), context);
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
   * Writes the material a source refines into, unless an admin overrides the link.
   *
   * @param source the refining material
   * @param commodity the source's record
   * @param refined the material the P4K names
   * @param context the run
   */
  private void linkRefinedMaterial(
      @NotNull Reconciled source,
      @NotNull P4kCommodityDto commodity,
      @NotNull Material refined,
      @NotNull P4kImportContext context) {
    Material row = source.row();
    if (!overridden(
        MaterialP4kOverride.REFINED_MATERIAL,
        row,
        commodity,
        row.getRefinedMaterial(),
        refined,
        Material::getName,
        context)) {
      source.write(
          MaterialP4kOverride.REFINED_MATERIAL.getColumn(),
          row.getRefinedMaterial(),
          refined,
          row::setRefinedMaterial,
          Material::getName,
          context);
    }
  }

  /**
   * Whether an admin overrides a led field of a material; an APPLY then reports a P4K value that
   * would replace the stored one as {@code P4K_DISAGREEMENT} and writes nothing.
   *
   * @param field the field
   * @param row the material
   * @param commodity the record
   * @param current the material's value
   * @param incoming the catalogue's value
   * @param label renders a value for the report
   * @param context the run
   * @param <V> the value type
   * @return {@code true} when the caller must not write the field
   */
  private <V> boolean overridden(
      @NotNull MaterialP4kOverride field,
      @NotNull Material row,
      @NotNull P4kCommodityDto commodity,
      @Nullable V current,
      @Nullable V incoming,
      @NotNull Function<V, String> label,
      @NotNull P4kImportContext context) {
    if (!field.isSet(row)) {
      return false;
    }
    UUID runId = context.getRunId();
    if (incoming != null
        && P4kFields.leads(current, incoming)
        && context.isApply()
        && runId != null) {
      syncReportService.logP4kEvent(
          runId,
          SyncEventType.P4K_DISAGREEMENT,
          REPORT_AGGREGATE,
          commodity.parsedGuid(),
          row.getName(),
          P4kFields.disagreement(
              field.getColumn(),
              current == null ? null : label.apply(current),
              label.apply(incoming)));
    }
    return true;
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
