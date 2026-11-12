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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemOrderRecipeFacts;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RetiredBlueprintException;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RetiredGameItemException;
import de.greluc.krt.profit.basetool.backend.joborder.api.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemPlan;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prepares, validates and applies one immutable batch of explicitly selected ITEM recipes. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderItemPlans {
  private final BlueprintRepository blueprints;
  private final MaterialRepository materials;
  private final QualityTierService quality;
  private final GameItemRepository gameItems;
  private final QualityTierRepository tiers;
  private final JobOrderItemPlanReads keptReads;

  /**
   * Freezes the requested recipes and retained item facts before resource planning.
   *
   * @param lines the requested item lines
   * @param keptItems the current retained lines
   * @return one immutable replacement plan
   */
  @NotNull
  public JobOrderItemPlan prepare(
      @NotNull List<CreateJobOrderItemLineDto> lines, @NotNull Collection<JobOrderItem> keptItems) {
    List<CreateJobOrderItemLineDto> inputs =
        lines.stream().map(JobOrderItemPlan::copyPayload).toList();
    Set<UUID> blueprintIds =
        inputs.stream().map(CreateJobOrderItemLineDto::blueprintId).collect(Collectors.toSet());
    Set<UUID> itemIds =
        inputs.stream().map(CreateJobOrderItemLineDto::gameItemId).collect(Collectors.toSet());
    Set<UUID> keptIds =
        keptItems.stream()
            .map(JobOrderItem::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (!keptIds.isEmpty()) {
      keptReads.initializeMaterials(keptIds);
    }
    JobOrderItemPlan.Facts facts = read(blueprintIds, itemIds, keptIds);
    assertManagedFacts(keptItems, facts);
    Map<UUID, Set<UUID>> keptTiers = new LinkedHashMap<>();
    facts
        .keptMaterials()
        .forEach(material ->
            keptTiers
                .computeIfAbsent(material.itemId(), _ -> new LinkedHashSet<>())
                .add(material.qualityTierId()));
    return derive(
        inputs,
        facts,
        line -> line.id() == null ? Set.of() : keptTiers.getOrDefault(line.id(), Set.of()));
  }

  /**
   * Prepares one item-line replacement using its retained quality choices.
   *
   * @param item the current line
   * @param line its requested replacement
   * @return the immutable replacement plan
   */
  @NotNull
  public JobOrderItemPlan prepareSingle(
      @NotNull JobOrderItem item, @NotNull CreateJobOrderItemLineDto line) {
    CreateJobOrderItemLineDto input = JobOrderItemPlan.copyPayload(line);
    JobOrderItemPlan.Facts facts =
        read(Set.of(input.blueprintId()), Set.of(input.gameItemId()), Set.of());
    Set<UUID> keptTiers =
        item.getMaterials().stream()
            .map(JobOrderItemMaterial::getQualityTier)
            .filter(Objects::nonNull)
            .map(QualityTier::getId)
            .collect(Collectors.toSet());
    return derive(List.of(input), facts, _ -> keptTiers);
  }

  /**
   * Rechecks the captured catalogue and retained-line facts before applying a plan.
   *
   * @param plan the original immutable selection
   * @throws BusinessConflictException when any captured fact changed
   */
  public void validate(@NotNull JobOrderItemPlan plan) {
    JobOrderItemPlan.Facts facts = plan.facts();
    if (!facts.equals(read(facts.blueprintIds(), facts.requestedItemIds(), facts.keptItemIds()))) {
      throw changed();
    }
  }

  /**
   * Loads the bounded recipe and retained-line facts for an action or preview.
   *
   * @param blueprintIds the selected recipes
   * @param itemIds the ordered game items
   * @param keptIds the current retained line identities
   * @return the immutable current derivation facts
   */
  @NotNull
  public JobOrderItemPlan.Facts read(
      @NotNull Set<UUID> blueprintIds, @NotNull Set<UUID> itemIds, @NotNull Set<UUID> keptIds) {
    List<ItemOrderRecipeFacts.OrderedItem> orderedItems = new ArrayList<>();
    if (!itemIds.isEmpty()) {
      orderedItems.addAll(blueprints.findItemOrderDirectItems(itemIds));
      orderedItems.addAll(blueprints.findItemOrderRetainedItems(itemIds));
      orderedItems.sort(Comparator.comparing(ItemOrderRecipeFacts.OrderedItem::requestedId));
    }
    return new JobOrderItemPlan.Facts(
        blueprintIds,
        itemIds,
        keptIds,
        blueprintIds.isEmpty() ? List.of() : blueprints.findItemOrderRecipes(blueprintIds),
        blueprintIds.isEmpty() ? List.of() : blueprints.findItemOrderGroups(blueprintIds),
        blueprintIds.isEmpty() ? List.of() : blueprints.findItemOrderIngredients(blueprintIds),
        blueprintIds.isEmpty() ? List.of() : blueprints.findItemOrderOutputs(blueprintIds),
        blueprintIds.isEmpty() ? List.of() : materials.findItemOrderBridges(blueprintIds),
        orderedItems,
        quality.selection(),
        keptIds.isEmpty() ? List.of() : keptReads.findLineFacts(keptIds),
        keptIds.isEmpty() ? List.of() : keptReads.findMaterialFacts(keptIds));
  }

  /**
   * Resolves the canonical game-item references for selected subassemblies.
   *
   * @param ids the selected game-item identities
   * @return canonical references keyed by each requested identity
   */
  @NotNull
  public Map<UUID, GameItemReferenceDto> gameItemReferences(@NotNull Set<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, UUID> redirects = gameItems.findLocalIdRedirects(ids);
    Map<UUID, GameItemReferenceDto> canonical =
        gameItems.findAllById(ids).stream()
            .collect(Collectors.toMap(GameItem::getId, GameItemReferenceDto::of));
    Map<UUID, GameItemReferenceDto> result = new LinkedHashMap<>();
    for (UUID id : ids) {
      result.put(id, require(canonical, redirects.getOrDefault(id, id)));
    }
    return Map.copyOf(result);
  }

  @NotNull
  private JobOrderItemPlan derive(
      @NotNull List<CreateJobOrderItemLineDto> lines,
      @NotNull JobOrderItemPlan.Facts facts,
      @NotNull Function<CreateJobOrderItemLineDto, Set<UUID>> keptTiers) {
    Map<UUID, ItemOrderRecipeFacts.Recipe> recipes =
        facts.recipes().stream()
            .collect(Collectors.toMap(ItemOrderRecipeFacts.Recipe::id, recipe -> recipe));
    Map<UUID, ItemOrderRecipeFacts.OrderedItem> gameItems = new LinkedHashMap<>();
    facts.orderedItems().forEach(item -> gameItems.putIfAbsent(item.requestedId(), item));
    JobOrderRecipeSelection selection = new JobOrderRecipeSelection(facts);
    List<JobOrderItemPlan.Line> result = new ArrayList<>();
    for (CreateJobOrderItemLineDto line : lines) {
      ItemOrderRecipeFacts.OrderedItem gameItem = gameItems.get(line.gameItemId());
      if (gameItem == null) {
        throw new NotFoundException("Game item not found");
      }
      if (gameItem.retired()) {
        throw new RetiredGameItemException(gameItem.canonicalId());
      }
      ItemOrderRecipeFacts.Recipe recipe = recipes.get(line.blueprintId());
      if (recipe == null) {
        throw new NotFoundException("Blueprint not found: " + line.blueprintId());
      }
      if (recipe.retired()) {
        throw new RetiredBlueprintException(recipe.id());
      }
      if (!gameItem.canonicalId().equals(recipe.outputItemId())) {
        throw new BadRequestException(
            "Blueprint " + line.blueprintId() + " does not produce game item " + line.gameItemId());
      }
      Map<UUID, String> choices = new LinkedHashMap<>();
      if (line.materials() != null) {
        line.materials().forEach(choice -> choices.put(choice.materialId(), choice.quality()));
      }
      List<JobOrderItemPlan.MaterialLine> required = new ArrayList<>();
      for (ItemOrderRecipeFacts.Ingredient ingredient : selection.counted(recipe)) {
        var material = selection.material(ingredient);
        if (material == null) {
          continue;
        }
        String choice = choices.get(material.id());
        var tier =
            choice == null || choice.isBlank()
                ? facts.quality().defaultForIngredient(ingredient.minQuality())
                : quality.resolveForRequirement(
                    facts.quality(), choice, null, keptTiers.apply(line));
        required.add(
            new JobOrderItemPlan.MaterialLine(
                material.id(),
                JobOrderRecipeSelection.requiredQuantity(ingredient, line.amount(), material),
                tier.id()));
      }
      result.add(new JobOrderItemPlan.Line(line, gameItem.canonicalId(), required));
    }
    return new JobOrderItemPlan(result, facts);
  }

  /**
   * Applies the captured replacement lines to the corresponding managed targets.
   *
   * @param targets the managed item lines in payload order
   * @param lines the captured replacements in the same order
   * @throws IllegalArgumentException when the two ordered lists differ in size
   */
  public void apply(
      @NotNull List<JobOrderItem> targets, @NotNull List<JobOrderItemPlan.Line> lines) {
    if (targets.size() != lines.size()) {
      throw new IllegalArgumentException(
          "The target item lines must match the prepared payload order");
    }
    Set<UUID> materialIds =
        lines.stream()
            .flatMap(line -> line.materials().stream())
            .map(JobOrderItemPlan.MaterialLine::materialId)
            .collect(Collectors.toSet());
    Set<UUID> tierIds =
        lines.stream()
            .flatMap(line -> line.materials().stream())
            .map(JobOrderItemPlan.MaterialLine::qualityTierId)
            .collect(Collectors.toSet());
    Set<UUID> itemIds =
        lines.stream().map(JobOrderItemPlan.Line::gameItemId).collect(Collectors.toSet());
    Set<UUID> blueprintIds =
        lines.stream().map(line -> line.payload().blueprintId()).collect(Collectors.toSet());
    Map<UUID, Material> materialRefs =
        materialIds.isEmpty()
            ? Map.of()
            : materials.findItemOrderReferences(materialIds).stream()
                .collect(Collectors.toMap(Material::getId, material -> material));
    Map<UUID, GameItem> itemRefs =
        itemIds.isEmpty()
            ? Map.of()
            : gameItems.findAllById(itemIds).stream()
                .collect(Collectors.toMap(GameItem::getId, item -> item));
    Map<UUID, Blueprint> blueprintRefs =
        blueprintIds.isEmpty()
            ? Map.of()
            : blueprints.findAllById(blueprintIds).stream()
                .collect(Collectors.toMap(Blueprint::getId, blueprint -> blueprint));
    Map<UUID, QualityTier> tierRefs =
        tierIds.isEmpty()
            ? Map.of()
            : tiers.findAllById(tierIds).stream()
                .collect(Collectors.toMap(QualityTier::getId, tier -> tier));
    for (int index = 0; index < lines.size(); index++) {
      JobOrderItem target = targets.get(index);
      JobOrderItemPlan.Line line = lines.get(index);
      target.setGameItem(require(itemRefs, line.gameItemId()));
      target.setBlueprint(require(blueprintRefs, line.payload().blueprintId()));
      target.setAmount(line.payload().amount());
      target.getMaterials().clear();
      for (JobOrderItemPlan.MaterialLine material : line.materials()) {
        target.addMaterial(
            JobOrderItemMaterial.builder()
                .material(require(materialRefs, material.materialId()))
                .requiredQuantity(material.requiredQuantity())
                .qualityTier(require(tierRefs, material.qualityTierId()))
                .build());
      }
    }
  }

  private static void assertManagedFacts(
      @NotNull Collection<JobOrderItem> keptItems, @NotNull JobOrderItemPlan.Facts facts) {
    List<JobOrderItemPlan.KeptLine> currentLines = new ArrayList<>();
    List<JobOrderItemPlan.KeptMaterial> currentMaterials = new ArrayList<>();
    for (JobOrderItem item : keptItems) {
      if (item.getId() == null) {
        continue;
      }
      currentLines.add(
          new JobOrderItemPlan.KeptLine(
              item.getId(),
              item.getVersion(),
              item.getAmount(),
              item.getManufacturedAmount(),
              item.getDeliveredAmount(),
              item.getGameItem().getId(),
              item.getBlueprint().getId(),
              item.getParentItem() == null ? null : item.getParentItem().getId()));
      for (JobOrderItemMaterial material : item.getMaterials()) {
        currentMaterials.add(
            new JobOrderItemPlan.KeptMaterial(
                material.getId(),
                item.getId(),
                material.getMaterial().getId(),
                material.getQualityTier().getId(),
                material.getRequiredQuantity(),
                material.getVersion()));
      }
    }
    if (!Set.copyOf(currentLines).equals(Set.copyOf(facts.keptLines()))
        || !Set.copyOf(currentMaterials).equals(Set.copyOf(facts.keptMaterials()))) {
      throw changed();
    }
  }

  @NotNull
  private static <T> T require(@NotNull Map<UUID, T> references, @NotNull UUID id) {
    T value = references.get(id);
    if (value == null) {
      throw changed();
    }
    return value;
  }

  @NotNull
  private static BusinessConflictException changed() {
    return new BusinessConflictException("error.inventory.concurrent_scope_changed");
  }
}
