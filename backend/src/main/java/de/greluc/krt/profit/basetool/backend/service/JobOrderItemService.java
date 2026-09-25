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
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives, snapshots, aggregates and maps the material side of {@code ITEM} job orders from the
 * SC-Wiki blueprint graph ({@link Blueprint} + {@link BlueprintIngredient}).
 *
 * <p>At create time a blueprint's RESOURCE ingredients are scaled by the amount, given the
 * requester's quality choice and snapshotted onto the order. A craftable ITEM ingredient becomes an
 * adoptable sub-assembly; a non-craftable one that exists in the {@code material} catalogue is
 * bridged to that material.
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
   * Builds one new, detached ordered-item line with its derived material snapshot; the caller
   * attaches it to the {@link JobOrder}. To re-derive an existing line use {@link
   * #applyItemLine(JobOrderItem, CreateJobOrderItemLineDto)}.
   *
   * @param line the line payload (game item, blueprint, amount, quality choices)
   * @return a populated {@link JobOrderItem} with its {@link JobOrderItemMaterial} children
   * @throws NotFoundException when the game item or blueprint id is unknown
   * @throws BadRequestException when the chosen blueprint does not produce the ordered game item
   */
  @NotNull
  public JobOrderItem buildItemLine(@NotNull CreateJobOrderItemLineDto line) {
    JobOrderItem item = JobOrderItem.builder().build();
    applyItemLine(item, line);
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
   */
  public void applyItemLine(JobOrderItem item, @NotNull CreateJobOrderItemLineDto line) {
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

    item.setGameItem(gameItem);
    item.setBlueprint(blueprint);
    item.setAmount(line.amount());
    item.getMaterials().clear();

    Map<UUID, QualityRequirement> qualityChoices = qualityChoicesByMaterial(line.materials());

    for (BlueprintIngredient ingredient : blueprint.getIngredients()) {
      Material material;
      double rawQuantity;
      if (ingredient.getKind() == BlueprintIngredientKind.RESOURCE) {
        material = ingredient.getMaterial();
        if (material == null) {
          continue;
        }
        double perUnit = ingredient.getQuantityScu() == null ? 0.0 : ingredient.getQuantityScu();
        rawQuantity = perUnit * line.amount();
      } else {
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
   * Aggregates the outstanding material demand of all ordered lines into one row per {@code
   * (material, quality)}, counting only each line's not-yet-manufactured share. Sorted SCU first,
   * then by name, then GOOD before NONE.
   *
   * @param order the job order to aggregate
   * @return the outstanding-material rows; empty for a material order
   */
  @NotNull
  public List<AggregatedMaterialDto> aggregateMaterials(@NotNull JobOrder order) {
    record Key(UUID materialId, QualityRequirement quality) {}

    Map<Key, Double> sums = new LinkedHashMap<>();
    Map<UUID, Material> materials = new LinkedHashMap<>();
    for (JobOrderItem item : order.getItems()) {
      int lineAmount = item.getAmount() != null ? item.getAmount() : 0;
      int manufactured = item.getManufacturedAmount() != null ? item.getManufacturedAmount() : 0;
      int remaining = Math.max(0, lineAmount - manufactured);
      for (JobOrderItemMaterial req : item.getMaterials()) {
        Material material = req.getMaterial();
        Key key = new Key(material.getId(), req.getQualityRequirement());
        double reqTotal = req.getRequiredQuantity() == null ? 0.0 : req.getRequiredQuantity();
        double outstanding = lineAmount > 0 ? reqTotal * remaining / lineAmount : 0.0;
        sums.merge(key, outstanding, Double::sum);
        materials.putIfAbsent(material.getId(), material);
      }
    }
    return sums.entrySet().stream()
        .map(
            e -> {
              Material material = materials.get(e.getKey().materialId());
              return new AggregatedMaterialDto(
                  materialMapper.toDto(material),
                  e.getKey().quality(),
                  QuantityTypeRounding.roundForQuantityType(e.getValue(), material),
                  null,
                  List.of(),
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

  @NotNull
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

  @NotNull
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

  @NotNull
  private GameItemReferenceDto gameItemRef(@NotNull GameItem gameItem) {
    return new GameItemReferenceDto(
        gameItem.getId(),
        gameItem.getName(),
        gameItem.getKind() == null ? null : gameItem.getKind().name());
  }

  @NotNull
  private BlueprintReferenceDto blueprintRef(@NotNull Blueprint blueprint) {
    return new BlueprintReferenceDto(
        blueprint.getId(), blueprint.getOutputName(), blueprint.getScwikiKey());
  }

  private static String unresolvedLabel(@NotNull BlueprintIngredient ingredient) {
    return ingredient.getWikiNameSnapshot() != null
        ? ingredient.getWikiNameSnapshot()
        : "(unresolved ingredient)";
  }

  /**
   * Bridges a non-craftable ITEM ingredient to the {@code material} catalogue by name.
   *
   * @param ingredient the blueprint ingredient to examine
   * @return the bridged material, or {@code null} for RESOURCE lines, craftable items and items
   *     without a matching material
   */
  @Nullable
  private Material bridgedMaterial(BlueprintIngredient ingredient) {
    if (ingredient.getKind() != BlueprintIngredientKind.ITEM) {
      return null;
    }
    GameItem subItem = ingredient.getGameItem();
    if (subItem == null) {
      return null;
    }
    if (!blueprintRepository.findByOutputItemId(subItem.getId()).isEmpty()) {
      return null;
    }
    return resolveItemMaterial(subItem, ingredient);
  }

  /**
   * Resolves an ITEM ingredient's component to a material by name, preferring the game item's name
   * and falling back to the Wiki name snapshot.
   *
   * @param subItem the resolved game item of the ingredient
   * @param ingredient the owning ingredient, for the Wiki-name fallback
   * @return the matching material, or {@code null} when none exists
   */
  @Nullable
  private Material resolveItemMaterial(@NotNull GameItem subItem, BlueprintIngredient ingredient) {
    String name = subItem.getName() != null ? subItem.getName() : ingredient.getWikiNameSnapshot();
    if (name == null || name.isBlank()) {
      return null;
    }
    return materialRepository.findByNameIgnoreCase(name).orElse(null);
  }
}
