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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.DerivedMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ItemDerivationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SubAssemblySuggestionDto;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.support.QuantityTypeRounding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives, snapshots, aggregates and maps the material side of {@code ITEM} job orders.
 *
 * <p>The single source of truth for "which materials does a finished item need" is the SC-Wiki
 * blueprint graph ({@link Blueprint} + {@link BlueprintIngredient}). At create time this service
 * reads the chosen blueprint's RESOURCE ingredients, scales each by the ordered amount, applies the
 * requester's per-material Gut/Keine choice (defaulting from the ingredient's {@code minQuality}),
 * and snapshots the result onto the order so it stays stable even if the wiki data later changes.
 * The amount's unit follows the material's {@link QuantityType}: PIECE quantities are kept whole,
 * SCU quantities stay fractional.
 *
 * <p>ITEM ingredients are handled in two ways. A <b>craftable</b> ITEM ingredient (the referenced
 * game item has its own orderable blueprint) is a genuine sub-assembly: it surfaces to the create
 * UI as a separate adoptable line (issue #304 decision 1), so each adopted sub-item contributes its
 * own materials. A <b>non-craftable</b> ITEM ingredient (no blueprint) that nonetheless exists in
 * the shared {@code material} catalogue by name is a procurement component — e.g. a UEX-commodity
 * gem the wiki lists as an "item" with a piece count — and is bridged to that material so it
 * appears as a (PIECE) material requirement on the same {@code material} row that Lager and
 * Refinery use, instead of a recipe-less sub-assembly. ITEM ingredients with neither a blueprint
 * nor a matching material stay unresolved/adoptable as before.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderItemService {

  /**
   * Refining-grade quality threshold: an ingredient {@code minQuality} at or above this maps to
   * {@link QualityRequirement#GOOD}.
   */
  private static final int GOOD_QUALITY_THRESHOLD = 650;

  private final BlueprintRepository blueprintRepository;
  private final GameItemRepository gameItemRepository;
  private final MaterialRepository materialRepository;
  private final MaterialMapper materialMapper;

  /**
   * Builds one ordered-item line with its derived, snapshotted material requirements. The returned
   * {@link JobOrderItem} is detached (not yet attached to an order) — the caller wires it onto the
   * {@link JobOrder} and resolves any sub-assembly parent link.
   *
   * @param line the create payload for this line (game item, chosen blueprint, amount, quality
   *     choices)
   * @return a populated {@link JobOrderItem} with its {@link JobOrderItemMaterial} children
   * @throws NotFoundException when the game item or blueprint id is unknown
   * @throws BadRequestException when the chosen blueprint does not produce the ordered game item
   */
  @NotNull
  public JobOrderItem buildItemLine(@NotNull CreateJobOrderItemLineDto line) {
    GameItem gameItem =
        Entities.require(
            gameItemRepository.findById(line.gameItemId()),
            () -> "GameItem not found: " + line.gameItemId());
    Blueprint blueprint =
        Entities.require(
            blueprintRepository.findById(line.blueprintId()),
            () -> "Blueprint not found: " + line.blueprintId());

    if (blueprint.getOutputItem() == null
        || !gameItem.getId().equals(blueprint.getOutputItem().getId())) {
      throw new BadRequestException(
          "Blueprint " + line.blueprintId() + " does not produce game item " + line.gameItemId());
    }

    JobOrderItem item =
        JobOrderItem.builder()
            .gameItem(gameItem)
            .blueprint(blueprint)
            .amount(line.amount())
            .build();

    Map<UUID, QualityRequirement> qualityChoices = qualityChoicesByMaterial(line.materials());

    for (BlueprintIngredient ingredient : blueprint.getIngredients()) {
      Material material;
      double rawQuantity;
      if (ingredient.getKind() == BlueprintIngredientKind.RESOURCE) {
        material = ingredient.getMaterial();
        if (material == null) {
          // Unresolved RESOURCE line: cannot be snapshotted, surfaces only as a create-time
          // warning.
          continue;
        }
        double perUnit = ingredient.getQuantityScu() == null ? 0.0 : ingredient.getQuantityScu();
        rawQuantity = perUnit * line.amount();
      } else {
        // ITEM ingredient: a craftable sub-assembly stays a separate adoptable line; a
        // non-craftable
        // item that maps to a known material is bridged to that material (piece count as quantity).
        material = bridgedMaterial(ingredient);
        if (material == null) {
          continue;
        }
        int perUnit = ingredient.getQuantityUnits() == null ? 0 : ingredient.getQuantityUnits();
        rawQuantity = (double) perUnit * line.amount();
      }

      double required = QuantityTypeRounding.roundForQuantityType(rawQuantity, material);
      QualityRequirement quality =
          qualityChoices.getOrDefault(material.getId(), defaultQuality(ingredient.getMinQuality()));

      item.addMaterial(
          JobOrderItemMaterial.builder()
              .material(material)
              .requiredQuantity(required)
              .qualityRequirement(quality)
              .build());
    }
    return item;
  }

  /**
   * Maps an item order's ordered lines to DTOs, sorted by item name then id for a stable table.
   *
   * @param order the (item) job order whose {@code items} to project
   * @return the ordered-item line DTOs; empty for a material order
   */
  @NotNull
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
   * Aggregates every snapshotted material requirement across all ordered lines into one row per
   * {@code (material, quality)}, summing the quantities. SCU materials sort first, then by name,
   * then GOOD before NONE — matching the material-handover table ordering.
   *
   * @param order the (item) job order to aggregate
   * @return the aggregated material rows; empty for a material order
   */
  @NotNull
  public List<AggregatedMaterialDto> aggregateMaterials(@NotNull JobOrder order) {
    record Key(UUID materialId, QualityRequirement quality) {}

    Map<Key, Double> sums = new LinkedHashMap<>();
    Map<UUID, Material> materials = new LinkedHashMap<>();
    for (JobOrderItem item : order.getItems()) {
      for (JobOrderItemMaterial req : item.getMaterials()) {
        Material material = req.getMaterial();
        Key key = new Key(material.getId(), req.getQualityRequirement());
        sums.merge(
            key, req.getRequiredQuantity() == null ? 0.0 : req.getRequiredQuantity(), Double::sum);
        materials.putIfAbsent(material.getId(), material);
      }
    }
    return sums.entrySet().stream()
        .map(
            e -> {
              Material material = materials.get(e.getKey().materialId());
              // currentStock and the claim fields stay neutral here — JobOrderService enriches them
              // in mapToDtoWithStock: it sums the order-linked inventory per bucket (collection
              // progress for the overview, #595) and overlays the SK claims (Phase 5, #345).
              // Non-SK orders keep the null open-amount so the UI renders no claim columns.
              return new AggregatedMaterialDto(
                  materialMapper.toDto(material),
                  e.getKey().quality(),
                  QuantityTypeRounding.roundForQuantityType(e.getValue(), material),
                  null,
                  java.util.List.of(),
                  null);
            })
        .sorted(
            Comparator.<AggregatedMaterialDto, Integer>comparing(
                    a ->
                        a.material() != null && "SCU".equalsIgnoreCase(a.material().quantityType())
                            ? 0
                            : 1)
                .thenComparing(
                    a ->
                        a.material() != null && a.material().name() != null
                            ? a.material().name()
                            : "",
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing(a -> a.qualityRequirement().name()))
        .toList();
  }

  /**
   * Collects the distinct material ids an order requires, across both order kinds: an {@code ITEM}
   * order's requirements are its snapshotted per-item materials ({@code items → materials}); a
   * {@code MATERIAL} order's are its material lines ({@code materials}). The two collections are
   * mutually exclusive per kind, so the result is the non-empty one.
   *
   * <p>This is the authoritative "may this material be linked to this order?" set: the inventory →
   * job-order link gate ({@code InventoryItemService}) rejects a material that is not in it, the
   * Lager order picker hides orders that do not require the row's material, and the order-detail
   * orphaned-link warning flags already-linked inventory whose material is absent here. A material
   * not in this set has no requirement row to surface under, so its link would be invisible.
   *
   * <p>Walks the lazy {@code items}/{@code items.materials} (ITEM) or {@code materials} (MATERIAL)
   * collections, so it must run inside a transaction; every current caller is
   * {@code @Transactional}.
   *
   * @param order the order whose required materials to collect.
   * @return the distinct required material ids, insertion-ordered; never {@code null}, possibly
   *     empty (an order with no requirements accepts no inventory link).
   */
  @NotNull
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

  private JobOrderItemDto toItemDto(JobOrderItem item) {
    List<JobOrderItemMaterialDto> materials =
        item.getMaterials().stream()
            .sorted(
                Comparator.comparing(
                    m -> m.getMaterial() != null ? m.getMaterial().getName() : "",
                    String.CASE_INSENSITIVE_ORDER))
            .map(
                m ->
                    new JobOrderItemMaterialDto(
                        m.getId(),
                        materialMapper.toDto(m.getMaterial()),
                        m.getRequiredQuantity(),
                        m.getQualityRequirement(),
                        m.getVersion()))
            .toList();
    GameItem gameItem = item.getGameItem();
    Blueprint blueprint = item.getBlueprint();
    return new JobOrderItemDto(
        item.getId(),
        gameItem == null ? null : gameItemRef(gameItem),
        blueprint == null ? null : blueprintRef(blueprint),
        item.getAmount(),
        item.getManufacturedAmount(),
        item.getDeliveredAmount(),
        item.getParentItem() == null ? null : item.getParentItem().getId(),
        materials,
        item.getVersion());
  }

  private static Map<UUID, QualityRequirement> qualityChoicesByMaterial(
      List<CreateJobOrderItemMaterialDto> choices) {
    Map<UUID, QualityRequirement> map = new LinkedHashMap<>();
    if (choices != null) {
      for (CreateJobOrderItemMaterialDto choice : choices) {
        map.put(choice.materialId(), choice.quality());
      }
    }
    return map;
  }

  private static QualityRequirement defaultQuality(Integer minQuality) {
    return minQuality != null && minQuality >= GOOD_QUALITY_THRESHOLD
        ? QualityRequirement.GOOD
        : QualityRequirement.NONE;
  }

  /**
   * Resolves the available blueprint references for a game item, by output name. Exposed for the
   * create UI's blueprint picker (shown when an item has more than one recipe).
   *
   * @param gameItemId the ordered item
   * @return blueprint references producing that item; empty when none exist
   */
  @NotNull
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
   * Page of orderable items (blueprint outputs with at least one resolvable material) for the
   * create UI's item picker. An optional name filter narrows the list.
   *
   * @param search case-insensitive name substring, or {@code null}/blank for no filter
   * @param pageable page request (whitelisted sort)
   * @return a page of orderable item references
   */
  @NotNull
  public Page<GameItemReferenceDto> findOrderableItems(String search, @NotNull Pageable pageable) {
    // Empty string (not null) for "no filter": a null bind into the query's LOWER(CONCAT(...))
    // makes PostgreSQL infer bytea and fail; "" matches every row via the %% pattern.
    String q = search != null && !search.isBlank() ? search.strip() : "";
    return blueprintRepository.findOrderableItems(q, pageable).map(this::gameItemRef);
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
   */
  @NotNull
  public ItemDerivationDto deriveForPreview(@NotNull UUID blueprintId, int amount) {
    Blueprint blueprint =
        Entities.require(
            blueprintRepository.findById(blueprintId), () -> "Blueprint not found: " + blueprintId);
    int scaledBy = Math.max(1, amount);

    List<DerivedMaterialDto> materials = new ArrayList<>();
    List<SubAssemblySuggestionDto> subAssemblies = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();

    for (BlueprintIngredient ingredient : blueprint.getIngredients()) {
      if (ingredient.getKind() == BlueprintIngredientKind.RESOURCE) {
        Material material = ingredient.getMaterial();
        if (material == null) {
          unresolved.add(unresolvedLabel(ingredient));
          continue;
        }
        double perUnit = ingredient.getQuantityScu() == null ? 0.0 : ingredient.getQuantityScu();
        materials.add(
            new DerivedMaterialDto(
                materialMapper.toDto(material),
                QuantityTypeRounding.roundForQuantityType(perUnit * scaledBy, material),
                defaultQuality(ingredient.getMinQuality())));
      } else {
        GameItem subItem = ingredient.getGameItem();
        if (subItem == null) {
          unresolved.add(unresolvedLabel(ingredient));
          continue;
        }
        int perUnit = ingredient.getQuantityUnits() == null ? 0 : ingredient.getQuantityUnits();
        List<BlueprintReferenceDto> subBlueprints = blueprintsForItem(subItem.getId());
        if (subBlueprints.isEmpty()) {
          // Non-craftable item: if it maps to a known material it is a procurement requirement
          // (PIECE piece-count), not a sub-assembly. Bridge it onto that shared material row.
          Material material = resolveItemMaterial(subItem, ingredient);
          if (material != null) {
            materials.add(
                new DerivedMaterialDto(
                    materialMapper.toDto(material),
                    QuantityTypeRounding.roundForQuantityType(
                        (double) perUnit * scaledBy, material),
                    defaultQuality(ingredient.getMinQuality())));
            continue;
          }
        }
        subAssemblies.add(
            new SubAssemblySuggestionDto(gameItemRef(subItem), perUnit * scaledBy, subBlueprints));
      }
    }
    return new ItemDerivationDto(
        blueprintRef(blueprint), scaledBy, materials, subAssemblies, unresolved);
  }

  private GameItemReferenceDto gameItemRef(GameItem gameItem) {
    return new GameItemReferenceDto(
        gameItem.getId(),
        gameItem.getName(),
        gameItem.getKind() == null ? null : gameItem.getKind().name());
  }

  private BlueprintReferenceDto blueprintRef(Blueprint blueprint) {
    return new BlueprintReferenceDto(
        blueprint.getId(), blueprint.getOutputName(), blueprint.getScwikiKey());
  }

  private static String unresolvedLabel(BlueprintIngredient ingredient) {
    return ingredient.getWikiNameSnapshot() != null
        ? ingredient.getWikiNameSnapshot()
        : "(unresolved ingredient)";
  }

  /**
   * Bridges a non-craftable ITEM ingredient to the shared {@code material} catalogue. Returns the
   * matching material when the ingredient is an ITEM line whose referenced game item has <b>no</b>
   * orderable blueprint (so it is not a real sub-assembly) yet exists as a material by name — e.g.
   * a UEX-commodity gem the wiki lists as an "item" with a piece count. Returns {@code null} for
   * RESOURCE lines, for craftable items (which stay adoptable sub-assemblies), and for items with
   * no matching material (which stay unresolved). Used by the persist path; the preview path
   * inlines the same rule to reuse its already-fetched blueprint list.
   *
   * @param ingredient the blueprint ingredient to examine
   * @return the bridged material, or {@code null} when the ingredient is not a bridgeable item line
   */
  private Material bridgedMaterial(BlueprintIngredient ingredient) {
    if (ingredient.getKind() != BlueprintIngredientKind.ITEM) {
      return null;
    }
    GameItem subItem = ingredient.getGameItem();
    if (subItem == null) {
      return null;
    }
    if (!blueprintRepository.findByOutputItemId(subItem.getId()).isEmpty()) {
      // Craftable: a genuine sub-assembly, handled as a separate adoptable line, not a material.
      return null;
    }
    return resolveItemMaterial(subItem, ingredient);
  }

  /**
   * Resolves an ITEM ingredient's component to a material by name, preferring the resolved game
   * item's name and falling back to the wiki name snapshot. {@code material.name} is unique, so
   * this yields at most one row.
   *
   * @param subItem the resolved game item of the ITEM ingredient
   * @param ingredient the owning ingredient (for the wiki-name fallback)
   * @return the matching material, or {@code null} when none exists
   */
  private Material resolveItemMaterial(GameItem subItem, BlueprintIngredient ingredient) {
    String name = subItem.getName() != null ? subItem.getName() : ingredient.getWikiNameSnapshot();
    if (name == null || name.isBlank()) {
      return null;
    }
    return materialRepository.findByNameIgnoreCase(name).orElse(null);
  }
}
