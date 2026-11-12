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
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemOrderRecipeFacts;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierSelection;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityTypeRounding;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RetiredBlueprintException;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RetiredGameItemException;
import de.greluc.krt.profit.basetool.backend.joborder.api.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.DerivedMaterialDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.ItemDerivationDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemMaterialDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemPlan;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.api.SubAssemblySuggestionDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives, snapshots, aggregates and maps the material side of {@code ITEM} job orders from the
 * catalogue recipe graph ({@link Blueprint} + {@link BlueprintIngredient}).
 *
 * <p>At create time a blueprint's RESOURCE ingredients are scaled by the amount, given the
 * requester's quality choice and snapshotted onto the order. A craftable ITEM ingredient becomes an
 * adoptable sub-assembly; a non-craftable one that exists in the {@code material} catalogue is
 * bridged to that material.
 */
@Service("jobOrderItemService")
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderItemServiceImpl implements JobOrderItemService {

  private final BlueprintRepository blueprintRepository;
  private final MaterialRepository materialRepository;
  private final MaterialMapper materialMapper;
  private final QualityTierMapper qualityTierMapper;
  private final JobOrderItemPlans plans;
  private final MessageSource messageSource;

  /**
   * Builds one new, detached ordered-item line with its derived material snapshot; the caller
   * attaches it to the {@link JobOrder}. To re-derive an existing line use {@link
   * #applyItemLine(JobOrderItem, CreateJobOrderItemLineDto)}.
   *
   * @param line the line payload (game item, blueprint, amount, quality choices)
   * @return a populated {@link JobOrderItem} with its {@link JobOrderItemMaterial} children
   * @throws NotFoundException when the game item or blueprint id is unknown
   * @throws BadRequestException when the chosen blueprint does not produce the ordered game item
   * @throws RetiredGameItemException when the ordered game item is retired or unobtainable
   * @throws RetiredBlueprintException when the P4K retired the chosen blueprint
   */
  @NotNull
  @Override
  public JobOrderItem buildItemLine(@NotNull CreateJobOrderItemLineDto line) {
    JobOrderItem item = JobOrderItem.builder().build();
    applyItemLine(item, line);
    return item;
  }

  /**
   * Builds one detached line from already validated immutable requirements.
   *
   * @param prepared the exact frozen line to build
   * @return the detached item with its prepared material snapshot
   */
  @NotNull
  @Override
  public JobOrderItem buildItemLine(@NotNull JobOrderItemPlan.Line prepared) {
    JobOrderItem item = JobOrderItem.builder().build();
    applyItemLine(item, prepared);
    return item;
  }

  /**
   * Re-derives {@code item} from the payload in place, replacing its {@link JobOrderItemMaterial}
   * snapshot while keeping its identity and its manufactured and delivered amounts
   * (REQ-ORDERS-032).
   *
   * <p>The caller must keep {@code deliveredAmount <= manufacturedAmount <= amount} intact.
   *
   * @param item the line to re-derive, managed or freshly built
   * @param line the payload with game item, blueprint, amount and quality choices
   * @throws NotFoundException when the game item or blueprint id is unknown
   * @throws BadRequestException when the chosen blueprint does not produce the ordered game item
   * @throws RetiredGameItemException when the ordered game item is retired or unobtainable, also on
   *     a line that keeps it
   * @throws RetiredBlueprintException when the P4K retired the chosen blueprint, also on a line
   *     that keeps it
   */
  @Override
  public void applyItemLine(JobOrderItem item, @NotNull CreateJobOrderItemLineDto line) {
    JobOrderItemPlan prepared = plans.prepareSingle(item, line);
    plans.apply(List.of(item), prepared.lines());
  }

  /**
   * Applies one already validated prepared line while preserving its identity and booked counters.
   *
   * @param item the existing or detached line
   * @param prepared its exact frozen requirements
   */
  @Override
  public void applyItemLine(@NotNull JobOrderItem item, @NotNull JobOrderItemPlan.Line prepared) {
    plans.apply(List.of(item), List.of(prepared));
  }

  /**
   * Prepares the exact selected requirements before an action takes its complete resource guards.
   *
   * @param lines the desired item lines in payload order
   * @param keptItems the current order's existing lines
   * @return immutable requirements and the current scalar facts they depend on
   * @throws IllegalArgumentException when a selected quantity cannot be represented exactly
   */
  @NotNull
  @Override
  public JobOrderItemPlan prepareItemLines(
      @NotNull List<CreateJobOrderItemLineDto> lines, @NotNull Collection<JobOrderItem> keptItems) {
    return plans.prepare(lines, keptItems);
  }

  /**
   * Refuses a prepared action whose source facts changed while it waited for resource guards.
   *
   * @param prepared the original immutable preparation
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException when a current
   *     recipe, quality, identity or kept-line fact differs
   */
  @Override
  public void validateItemPlan(@NotNull JobOrderItemPlan prepared) {
    plans.validate(prepared);
  }

  /**
   * Applies the original prepared requirements without choosing another recipe after a lock wait.
   *
   * @param targets existing or detached item lines in the prepared payload order
   * @param prepared the immutable preparation already validated under the action's resource guards
   */
  @Override
  public void applyItemLines(
      @NotNull List<JobOrderItem> targets, @NotNull JobOrderItemPlan prepared) {
    plans.apply(targets, prepared.lines());
  }

  /**
   * Maps an item order's ordered lines to DTOs, sorted by item name then id for a stable table.
   *
   * @param order the (item) job order whose {@code items} to project
   * @return the ordered-item line DTOs; empty for a material order
   */
  @NotNull
  @Override
  public List<JobOrderItemDto> toItemDtos(@NotNull JobOrder order) {
    return order.getItems().stream()
        .sorted(
            Comparator.<JobOrderItem, String>comparing(
                    i -> i.getGameItem() != null ? i.getGameItem().getName() : "",
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing(i -> i.getId().toString()))
        .map(this::toItemDto)
        .toList();
  }

  /**
   * Aggregates the outstanding material demand of all ordered lines into one row per {@code
   * (material, quality tier)}, counting only each line's not-yet-manufactured share. Sorted SCU
   * first, then by name, then by descending tier floor.
   *
   * @param order the job order to aggregate
   * @return the outstanding-material rows; empty for a material order
   */
  @NotNull
  @Override
  public List<AggregatedMaterialDto> aggregateMaterials(@NotNull JobOrder order) {
    record Key(UUID materialId, UUID tierId) {}

    Map<Key, Double> sums = new LinkedHashMap<>();
    Map<UUID, Material> materials = new LinkedHashMap<>();
    Map<UUID, QualityTier> tiers = new LinkedHashMap<>();
    for (JobOrderItem item : order.getItems()) {
      int lineAmount = item.getAmount() != null ? item.getAmount() : 0;
      int manufactured = item.getManufacturedAmount() != null ? item.getManufacturedAmount() : 0;
      int remaining = Math.max(0, lineAmount - manufactured);
      for (JobOrderItemMaterial req : item.getMaterials()) {
        Material material = req.getMaterial();
        QualityTier tier = req.getQualityTier();
        Key key = new Key(material.getId(), tier.getId());
        double reqTotal = req.getRequiredQuantity() == null ? 0.0 : req.getRequiredQuantity();
        double outstanding = lineAmount > 0 ? reqTotal * remaining / lineAmount : 0.0;
        sums.merge(key, outstanding, Double::sum);
        materials.putIfAbsent(material.getId(), material);
        tiers.putIfAbsent(tier.getId(), tier);
      }
    }
    return sums.entrySet().stream()
        .map(e -> {
          Material material = Objects.requireNonNull(materials.get(e.getKey().materialId()));
          QualityTier tier = Objects.requireNonNull(tiers.get(e.getKey().tierId()));
          return new AggregatedMaterialDto(
              materialMapper.toDto(material),
              tier.getCode(),
              qualityTierMapper.toDto(tier),
              QuantityTypeRounding.roundForQuantityType(e.getValue(), material),
              null,
              List.of(),
              null);
        })
        .sorted(
            Comparator.<AggregatedMaterialDto, Integer>comparing(a ->
                    a.material() != null && "SCU".equalsIgnoreCase(a.material().quantityType())
                        ? 0
                        : 1)
                .thenComparing(
                    a ->
                        a.material() != null && a.material().name() != null
                            ? a.material().name()
                            : "",
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(a -> -a.qualityTier().minQuality()))
        .toList();
  }

  /**
   * Collects the distinct material ids an order requires: the snapshotted item materials of an
   * {@code ITEM} order, or the material lines of a {@code MATERIAL} order.
   *
   * <p>This is the authoritative set of materials that may be linked to the order. Must run inside
   * a transaction.
   *
   * @param order the order whose required materials to collect.
   * @return the required material ids in insertion order; never {@code null}, possibly empty.
   */
  @NotNull
  @Override
  public Set<UUID> requiredMaterialIds(@NotNull JobOrder order) {
    Set<UUID> ids = new LinkedHashSet<>();
    if (order.getType() == JobOrderType.ITEM) {
      for (JobOrderItem item : order.getItems()) {
        for (JobOrderItemMaterial req : item.getMaterials()) {
          if (req.getMaterial() != null) {
            ids.add(req.getMaterial().getId());
          }
        }
      }
    } else {
      for (JobOrderMaterial mat : order.getMaterials()) {
        if (mat.getMaterial() != null) {
          ids.add(mat.getMaterial().getId());
        }
      }
    }
    return ids;
  }

  /**
   * Collects the distinct game-item ids an {@code ITEM} order requests; empty for a {@code
   * MATERIAL} order.
   *
   * <p>This is the authoritative set of game items whose stock may be linked to the order
   * (REQ-INV-031). Must run inside a transaction.
   *
   * @param order the order whose requested game items to collect.
   * @return the requested game-item ids in insertion order; never {@code null}.
   */
  @NotNull
  @Override
  public Set<UUID> requiredGameItemIds(@NotNull JobOrder order) {
    Set<UUID> ids = new LinkedHashSet<>();
    if (order.getType() == JobOrderType.ITEM) {
      for (JobOrderItem item : order.getItems()) {
        if (item.getGameItem() != null) {
          ids.add(item.getGameItem().getId());
        }
      }
    }
    return ids;
  }

  @NotNull
  private JobOrderItemDto toItemDto(@NotNull JobOrderItem item) {
    List<JobOrderItemMaterialDto> materials =
        item.getMaterials().stream()
            .sorted(
                Comparator.comparing(
                    m -> m.getMaterial() != null ? m.getMaterial().getName() : "",
                    String.CASE_INSENSITIVE_ORDER))
            .map(m ->
                new JobOrderItemMaterialDto(
                    m.getId(),
                    materialMapper.toDto(m.getMaterial()),
                    m.getRequiredQuantity(),
                    m.getQualityTier().getCode(),
                    qualityTierMapper.toDto(m.getQualityTier()),
                    m.getVersion()))
            .toList();
    GameItem gameItem = item.getGameItem();
    Blueprint blueprint = item.getBlueprint();
    return new JobOrderItemDto(
        item.getId(),
        gameItem == null ? null : GameItemReferenceDto.of(gameItem),
        blueprint == null ? null : blueprintRef(blueprint),
        item.getAmount(),
        item.getManufacturedAmount(),
        item.getDeliveredAmount(),
        item.getParentItem() == null ? null : item.getParentItem().getId(),
        materials,
        isBlueprintStale(item),
        item.getVersion());
  }

  /**
   * Whether this line's blueprint no longer produces the ordered game item, e.g. after a Wiki sync
   * re-pointed it (REQ-ORDERS-033).
   *
   * @param item the ordered-item line to check
   * @return {@code true} when the blueprint's output item is absent or differs from the line's game
   *     item
   */
  private static boolean isBlueprintStale(@NotNull JobOrderItem item) {
    Blueprint blueprint = item.getBlueprint();
    GameItem gameItem = item.getGameItem();
    if (blueprint == null || gameItem == null) {
      return false;
    }
    GameItem output = blueprint.getOutputItem();
    return output == null || !gameItem.getId().equals(output.getId());
  }

  /**
   * Builds the preview row of one derived material with its default quality tier.
   *
   * @param material the derived material
   * @param quantity the scaled quantity
   * @param tier the default quality selected from the action's frozen catalogue
   * @return the preview row
   */
  @NotNull
  private DerivedMaterialDto derived(
      @NotNull Material material, double quantity, @NotNull QualityTierSelection.Tier tier) {
    QualityTierDto qualityTier =
        new QualityTierDto(
            tier.id(),
            tier.code(),
            tier.minQuality(),
            tier.labelDe(),
            tier.labelEn(),
            tier.sortOrder(),
            tier.active(),
            tier.version());
    return new DerivedMaterialDto(
        materialMapper.toDto(material), quantity, tier.code(), qualityTier);
  }

  /**
   * Resolves the available blueprint references for a game item, by output name. Exposed for the
   * create UI's blueprint picker (shown when an item has more than one recipe).
   *
   * @param gameItemId the ordered item
   * @return blueprint references producing that item; empty when none exist
   */
  @NotNull
  @Override
  public List<BlueprintReferenceDto> blueprintsForItem(@NotNull UUID gameItemId) {
    return blueprintRepository.findByOutputItemId(gameItemId).stream()
        .map(this::blueprintRef)
        .sorted(
            Comparator.comparing(
                (BlueprintReferenceDto b) -> b.outputName() != null ? b.outputName() : "",
                String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * Previews the material derivation for a chosen blueprint at a given amount: the resolved
   * material requirements (scaled, with their default quality), the adoptable sub-assembly
   * suggestions, and the names of unresolved ingredient lines (for the create-form warning banner).
   *
   * @param blueprintId the chosen blueprint
   * @param amount the whole-unit amount to scale by (clamped to at least 1)
   * @return the derivation preview
   * @throws NotFoundException when the blueprint id is unknown
   * @throws IllegalArgumentException when a selected quantity or unit count is unrepresentable
   */
  @NotNull
  @Override
  public ItemDerivationDto deriveForPreview(@NotNull UUID blueprintId, int amount) {
    JobOrderItemPlan.Facts facts = plans.read(Set.of(blueprintId), Set.of(), Set.of());
    ItemOrderRecipeFacts.Recipe recipe =
        Entities.require(
            facts.recipes().stream().findFirst(), () -> "Blueprint not found: " + blueprintId);
    JobOrderRecipeSelection selection = new JobOrderRecipeSelection(facts);
    List<ItemOrderRecipeFacts.Ingredient> counted = selection.counted(recipe);
    Map<UUID, JobOrderRecipeSelection.ResolvedMaterial> resolved = new LinkedHashMap<>();
    Set<UUID> materialIds = new LinkedHashSet<>();
    Set<UUID> gameItemIds = new LinkedHashSet<>();
    for (ItemOrderRecipeFacts.Ingredient ingredient : counted) {
      var material = selection.material(ingredient);
      if (material != null) {
        resolved.put(ingredient.id(), material);
        materialIds.add(material.id());
      } else if (ingredient.kind() == BlueprintIngredientKind.ITEM
          && ingredient.gameItemId() != null) {
        gameItemIds.add(ingredient.gameItemId());
      }
    }
    Map<UUID, Material> materialRefs = new LinkedHashMap<>();
    if (!materialIds.isEmpty()) {
      materialRepository
          .findItemOrderReferences(materialIds)
          .forEach(material -> materialRefs.put(material.getId(), material));
    }
    Map<UUID, GameItemReferenceDto> gameItemRefs = plans.gameItemReferences(gameItemIds);
    int scaledBy = Math.max(1, amount);
    List<DerivedMaterialDto> materials = new ArrayList<>();
    List<SubAssemblySuggestionDto> subAssemblies = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();
    for (ItemOrderRecipeFacts.Ingredient ingredient : counted) {
      var material = resolved.get(ingredient.id());
      if (material != null) {
        materials.add(
            derived(
                Objects.requireNonNull(materialRefs.get(material.id())),
                JobOrderRecipeSelection.requiredQuantity(ingredient, scaledBy, material),
                facts.quality().defaultForIngredient(ingredient.minQuality())));
      } else if (ingredient.kind() == BlueprintIngredientKind.RESOURCE
          || ingredient.gameItemId() == null) {
        unresolved.add(unresolvedLabel(ingredient));
      } else {
        List<BlueprintReferenceDto> outputs =
            facts.outputs().stream()
                .filter(output -> output.ingredientId().equals(ingredient.id()))
                .map(output ->
                    new BlueprintReferenceDto(
                        output.blueprintId(), output.outputName(), output.scwikiKey()))
                .sorted(
                    Comparator.comparing(
                        reference -> reference.outputName() == null ? "" : reference.outputName(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        subAssemblies.add(
            new SubAssemblySuggestionDto(
                Objects.requireNonNull(gameItemRefs.get(ingredient.gameItemId())),
                JobOrderRecipeSelection.requiredUnits(ingredient, scaledBy),
                outputs));
      }
    }
    return new ItemDerivationDto(
        new BlueprintReferenceDto(recipe.id(), recipe.outputName(), recipe.scwikiKey()),
        scaledBy,
        materials,
        subAssemblies,
        unresolved);
  }

  @NotNull
  private BlueprintReferenceDto blueprintRef(@NotNull Blueprint blueprint) {
    return new BlueprintReferenceDto(
        blueprint.getId(), blueprint.getOutputName(), blueprint.getScwikiKey());
  }

  private String unresolvedLabel(@NotNull ItemOrderRecipeFacts.Ingredient ingredient) {
    return ingredient.wikiName() != null
        ? ingredient.wikiName()
        : messageSource.getMessage(
            "orders.item.unresolved_ingredient", null, LocaleContextHolder.getLocale());
  }
}
