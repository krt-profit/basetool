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

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintCraftabilityDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CraftabilityGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CraftabilityMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.support.QuantityTypeRounding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes, for each of the caller's owned blueprints, whether and how many times it can be crafted
 * from the caller's own stock — the answer the Personal Inventory blueprint view annotates each
 * blueprint with (#781, REQ-INV-019).
 *
 * <p>Strictly owner-scoped: the owned blueprints come from the caller's {@code sub}, the stock from
 * the caller's "My Inventory" rows ({@code user == me}, pooled across all locations), and the
 * optional refinery yield from the caller's own {@code OPEN}/{@code IN_PROGRESS} refinery orders.
 * No other user's data is ever read. Read-only and allocation-light.
 *
 * <p>Evaluated ingredients are RESOURCE (commodity) lines <em>and</em> the ITEM lines the wiki
 * counts in pieces but that resolve to a shared {@code material} — a hand-mined gem such as
 * Hadanite or Beradom the wiki models as a non-craftable game item (ADR-0046). Such an ITEM is
 * bridged to its PIECE material by name, the same bridge {@code
 * JobOrderItemService.bridgedMaterial} applies, so a craftability figure and the job-order
 * requirement consume the identical material row. A <em>craftable</em> ITEM (the output of another
 * blueprint — a genuine sub-assembly) and an unresolved ITEM carry no material and stay flagged
 * "not evaluated". The bridge is resolved once for the caller's whole owned set (one
 * craftable-output query, one game-item load, one material-by-name query) so the cost stays bounded
 * regardless of how many blueprints are owned.
 *
 * <p>Per material the recipe needs, only stock at or above a quality floor counts: the stricter of
 * the ingredient's {@code min_quality} and the lowest quality at which none of the slot's stat
 * modifiers would worsen the output (see {@link BlueprintModifierMath#noDegradationFloor}). The
 * craftable count is {@code floor(min over materials of available / required)}; the effective
 * output quality is the SCU-weighted average of the best-quality qualifying stock consumed first,
 * over one craft's requirement. Every figure is produced twice — inventory alone and with the
 * refinery yield folded in — so the UI's refinery toggle switches client-side.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlueprintCraftabilityService {

  private final PersonalBlueprintService personalBlueprintService;
  private final BlueprintProductService blueprintProductService;
  private final InventoryItemService inventoryItemService;
  private final RefineryOrderService refineryOrderService;
  private final BlueprintRepository blueprintRepository;
  private final GameItemRepository gameItemRepository;
  private final MaterialRepository materialRepository;

  /**
   * Computes the craftability of every blueprint the caller owns.
   *
   * @param userId the caller's {@code app_user.id}: it owns both the personal blueprints and the
   *     inventory + refinery rows. Two parameters carried this one value until V235 made the
   *     blueprint owner column a UUID foreign key like every other (ADR-0142).
   * @param includeRefinery whether to fold the caller's open refinery yield into the {@code
   *     *WithRefinery} figures; when {@code false} those equal the inventory-only figures
   * @return one craftability entry per owned blueprint; never {@code null}
   */
  @NotNull
  public List<BlueprintCraftabilityDto> computeForOwner(
      @NotNull UUID userId, boolean includeRefinery) {
    List<PersonalBlueprintResponse> owned =
        personalBlueprintService.listOwn(userId, null, Pageable.unpaged()).getContent();
    if (owned.isEmpty()) {
      return List.of();
    }

    Set<String> productKeys =
        owned.stream().map(PersonalBlueprintResponse::productKey).collect(Collectors.toSet());
    Map<String, Blueprint> recipes =
        blueprintProductService.resolveRepresentativeBlueprints(productKeys);

    Map<UUID, List<OwnedStockSlice>> inventory =
        slicesPerMaterial(inventoryItemService.getOwnedStockSlices(userId));
    Map<UUID, List<OwnedStockSlice>> withRefinery = inventory;
    if (includeRefinery) {
      List<OwnedStockSlice> merged =
          new ArrayList<>(inventoryItemService.getOwnedStockSlices(userId));
      merged.addAll(refineryOrderService.getOwnedOpenRefineryYieldSlices(userId));
      withRefinery = slicesPerMaterial(merged);
    }

    // Resolve, once for the whole owned set, which PIECE-counted ITEM ingredients bridge to a
    // material (the hand-mined gems the wiki models as items); a craftable sub-assembly or an
    // unresolved item stays absent and is reported "not evaluated".
    Map<UUID, Material> itemBridges = resolveItemBridges(recipes.values());

    List<BlueprintCraftabilityDto> out = new ArrayList<>(owned.size());
    for (PersonalBlueprintResponse blueprint : owned) {
      out.add(
          computeOne(
              blueprint.id(),
              recipes.get(blueprint.productKey()),
              inventory,
              withRefinery,
              itemBridges));
    }
    return out;
  }

  /**
   * Resolves, for the caller's whole owned set in one pass, every recipe ITEM ingredient that
   * should be evaluated as a material requirement rather than skipped: a non-craftable component
   * the wiki counts in pieces (a hand-mined gem such as Hadanite or Beradom) that also exists in
   * the shared {@code material} catalogue by name. Mirrors {@code
   * JobOrderItemService.bridgedMaterial} so a craftability figure and the job-order requirement
   * bridge the identical {@code material}, but batched: one query for the craftable (sub-assembly)
   * game items, one load for the bridgeable game items' canonical names, and one case-insensitive
   * material-by-name query — so the cost is bounded regardless of how many blueprints are owned
   * (ADR-0046, honouring ADR-0035's bounded-cost claim).
   *
   * @param recipes the representative recipes resolved for the owned set (managed entities; their
   *     lazy ingredient / game-item associations are touched inside this read transaction)
   * @return a map from a bridgeable ITEM ingredient's resolved {@code game_item} id to its matched
   *     {@link Material}; a craftable, unresolved, or name-unmatched item is absent
   */
  @NotNull
  private Map<UUID, Material> resolveItemBridges(@NotNull Collection<Blueprint> recipes) {
    Set<UUID> itemGameItemIds = new LinkedHashSet<>();
    for (Blueprint recipe : recipes) {
      for (BlueprintIngredient ingredient : recipe.getIngredients()) {
        if (ingredient.getKind() != BlueprintIngredientKind.ITEM) {
          continue;
        }
        GameItem gameItem = ingredient.getGameItem();
        if (gameItem != null && gameItem.getId() != null) {
          itemGameItemIds.add(gameItem.getId());
        }
      }
    }
    if (itemGameItemIds.isEmpty()) {
      return Map.of();
    }
    // A craftable ITEM (the output of an active blueprint) is a genuine sub-assembly, not a raw
    // material — it is excluded exactly as the job-order bridge excludes it.
    Set<UUID> craftable =
        new HashSet<>(blueprintRepository.findCraftableOutputItemIds(itemGameItemIds));
    List<UUID> bridgeable = itemGameItemIds.stream().filter(id -> !craftable.contains(id)).toList();
    if (bridgeable.isEmpty()) {
      return Map.of();
    }
    // Load the bridgeable game items for their canonical names, then resolve each name to a
    // material
    // (case-insensitive). Using the resolved game item's name keeps the match identical to the
    // job-order bridge's resolveItemMaterial.
    Map<UUID, String> nameByGameItemId = new LinkedHashMap<>();
    Set<String> lowerNames = new LinkedHashSet<>();
    for (GameItem item : gameItemRepository.findAllById(bridgeable)) {
      if (item.getName() == null || item.getName().isBlank()) {
        continue;
      }
      nameByGameItemId.put(item.getId(), item.getName());
      lowerNames.add(item.getName().toLowerCase(Locale.ROOT));
    }
    if (lowerNames.isEmpty()) {
      return Map.of();
    }
    // The batched name match folds the candidate names in Java (Locale.ROOT) while the column is
    // folded by the DB's LOWER() — these agree for the ASCII commodity/gem names this bridges (the
    // only names it ever sees), so the resolved row matches the all-DB fold the job-order bridge
    // (JobOrderItemService.resolveItemMaterial) uses; a non-ASCII name could differ, hence the
    // ASCII-scoped note on MaterialRepository.findByNameInIgnoreCase.
    Map<String, Material> materialByLowerName = new HashMap<>();
    for (Material material : materialRepository.findByNameInIgnoreCase(lowerNames)) {
      if (material.getName() != null) {
        materialByLowerName.putIfAbsent(material.getName().toLowerCase(Locale.ROOT), material);
      }
    }
    Map<UUID, Material> bridges = new LinkedHashMap<>();
    nameByGameItemId.forEach(
        (gameItemId, name) -> {
          Material material = materialByLowerName.get(name.toLowerCase(Locale.ROOT));
          if (material != null && material.getId() != null) {
            bridges.put(gameItemId, material);
          }
        });
    return bridges;
  }

  /**
   * Computes one blueprint's craftability from the pooled stock maps.
   *
   * @param blueprintId the owned blueprint id
   * @param recipe the representative recipe, or {@code null} when none resolved
   * @param inventory stock pooled per material, inventory only
   * @param withRefinery stock pooled per material, inventory plus open refinery yield
   * @param itemBridges resolved ITEM-ingredient bridges ({@code game_item} id → {@link Material}),
   *     from {@link #resolveItemBridges(Collection)}
   * @return the craftability entry
   */
  @NotNull
  private BlueprintCraftabilityDto computeOne(
      @NotNull UUID blueprintId,
      @Nullable Blueprint recipe,
      @NotNull Map<UUID, List<OwnedStockSlice>> inventory,
      @NotNull Map<UUID, List<OwnedStockSlice>> withRefinery,
      @NotNull Map<UUID, Material> itemBridges) {
    if (recipe == null) {
      return new BlueprintCraftabilityDto(
          blueprintId, false, false, false, 0, 0, null, null, List.of(), List.of());
    }

    List<BlueprintIngredient> flat = recipe.getIngredients();
    boolean hasUnevaluatedItem = false;

    // Aggregate the material requirement per material (a material used in several slots is pooled;
    // its floor is the strictest of the slots' floors). A requirement comes from a RESOURCE line or
    // from a PIECE-counted ITEM line bridged to a material (ADR-0046).
    Map<UUID, MaterialRequirement> requirements = new LinkedHashMap<>();
    for (BlueprintIngredient ingredient : flat) {
      Material material;
      double rawRequired;
      if (ingredient.getKind() == BlueprintIngredientKind.RESOURCE) {
        material = ingredient.getMaterial();
        if (material == null || material.getId() == null) {
          continue;
        }
        rawRequired = ingredient.getQuantityScu() == null ? 0.0d : ingredient.getQuantityScu();
      } else {
        // ITEM line: a non-craftable component the wiki counts in pieces is bridged to its PIECE
        // material and evaluated as that requirement (its per-craft quantity is the whole-unit
        // count); a craftable sub-assembly or an unresolved item carries no material and is flagged
        // "not evaluated".
        material = bridgedItemMaterial(ingredient, itemBridges);
        if (material == null || material.getId() == null) {
          hasUnevaluatedItem = true;
          continue;
        }
        rawRequired = ingredient.getQuantityUnits() == null ? 0.0d : ingredient.getQuantityUnits();
      }
      if (rawRequired <= 0.0d) {
        continue;
      }
      // A PIECE-quantity material's per-craft requirement is a whole piece count, rounded exactly
      // as
      // JobOrderItemService.roundForQuantityType does — so the recipe never demands a fractional
      // piece and the craftable count stays in step with the rest of the app. SCU materials keep
      // their fractional requirement.
      QuantityType quantityType = material.getQuantityType();
      double required = QuantityTypeRounding.roundForQuantityType(rawRequired, quantityType);
      if (required <= 0.0d) {
        continue;
      }
      int floor = slotFloor(ingredient);
      Material requirementMaterial = material;
      requirements
          .computeIfAbsent(
              material.getId(),
              id -> new MaterialRequirement(id, requirementMaterial.getName(), quantityType))
          .add(required, floor);
    }

    if (requirements.isEmpty()) {
      // No RESOURCE and no bridged ITEM requirement (ITEM-only sub-assembly / unresolved recipe):
      // craftability cannot be assessed.
      return new BlueprintCraftabilityDto(
          blueprintId, true, hasUnevaluatedItem, false, 0, 0, null, null, List.of(), List.of());
    }

    Map<UUID, MaterialResult> results = new LinkedHashMap<>();
    int craftable = Integer.MAX_VALUE;
    int craftableWithRefinery = Integer.MAX_VALUE;
    double tightest = Double.MAX_VALUE;
    double tightestWithRefinery = Double.MAX_VALUE;
    String limiting = null;
    String limitingWithRefinery = null;

    for (MaterialRequirement requirement : requirements.values()) {
      Availability inv =
          availability(
              inventory.get(requirement.materialId), requirement.floor, requirement.required);
      Availability ref =
          availability(
              withRefinery.get(requirement.materialId), requirement.floor, requirement.required);
      results.put(requirement.materialId, new MaterialResult(requirement, inv, ref));

      int countInv = (int) Math.floor(inv.available / requirement.required);
      int countRef = (int) Math.floor(ref.available / requirement.required);
      craftable = Math.min(craftable, countInv);
      craftableWithRefinery = Math.min(craftableWithRefinery, countRef);

      double ratioInv = inv.available / requirement.required;
      if (ratioInv < tightest) {
        tightest = ratioInv;
        limiting = requirement.materialName;
      }
      double ratioRef = ref.available / requirement.required;
      if (ratioRef < tightestWithRefinery) {
        tightestWithRefinery = ratioRef;
        limitingWithRefinery = requirement.materialName;
      }
    }

    List<CraftabilityMaterialDto> materials = new ArrayList<>(results.size());
    for (MaterialResult result : results.values()) {
      MaterialRequirement req = result.requirement;
      materials.add(
          new CraftabilityMaterialDto(
              req.materialId,
              req.materialName,
              round(req.required),
              req.floor,
              round(result.inventory.available),
              round(result.withRefinery.available),
              roundNullable(result.inventory.effectiveQuality),
              roundNullable(result.withRefinery.effectiveQuality),
              round(result.inventory.missing),
              round(result.withRefinery.missing),
              (int) Math.floor(result.inventory.available / req.required),
              (int) Math.floor(result.withRefinery.available / req.required),
              req.quantityType));
    }

    List<CraftabilityGroupDto> groups = buildGroups(recipe, flat, results, itemBridges);

    return new BlueprintCraftabilityDto(
        blueprintId,
        true,
        hasUnevaluatedItem,
        true,
        craftable == Integer.MAX_VALUE ? 0 : craftable,
        craftableWithRefinery == Integer.MAX_VALUE ? 0 : craftableWithRefinery,
        limiting,
        limitingWithRefinery,
        groups,
        materials);
  }

  /**
   * Builds the per-requirement-group overlay in recipe order, each carrying the effective quality
   * of its driving material so the frontend slider can default to it. The slot's driving material
   * is found from the owned flat ingredient list (which always carries the group back-reference)
   * rather than the group's inverse collection, so the result is independent of whether Hibernate
   * hydrated that inverse.
   *
   * @param recipe the representative recipe
   * @param flat the recipe's flat ingredient list (group back-references set)
   * @param results the per-material results computed for this recipe
   * @param itemBridges resolved ITEM-ingredient bridges ({@code game_item} id → {@link Material}),
   *     so a slot whose only material-bearing line is a bridged ITEM still carries an effective
   *     quality
   * @return the group overlays, in {@code requirementGroups} order
   */
  @NotNull
  private List<CraftabilityGroupDto> buildGroups(
      @NotNull Blueprint recipe,
      @NotNull List<BlueprintIngredient> flat,
      @NotNull Map<UUID, MaterialResult> results,
      @NotNull Map<UUID, Material> itemBridges) {
    List<BlueprintRequirementGroup> recipeGroups = recipe.getRequirementGroups();
    List<CraftabilityGroupDto> groups = new ArrayList<>(recipeGroups.size());
    for (BlueprintRequirementGroup group : recipeGroups) {
      UUID drivingMaterialId = firstRequirementMaterialId(flat, group, itemBridges);
      MaterialResult result = drivingMaterialId == null ? null : results.get(drivingMaterialId);
      groups.add(
          new CraftabilityGroupDto(
              drivingMaterialId,
              result == null ? null : roundNullable(result.inventory.effectiveQuality),
              result == null ? null : roundNullable(result.withRefinery.effectiveQuality)));
    }
    return groups;
  }

  /**
   * Returns the id of a slot's first material-bearing ingredient, or {@code null}, by scanning the
   * flat ingredient list for lines that back-reference this exact group instance. A line
   * contributes its material if it is a RESOURCE with a resolved material, or an ITEM bridged to a
   * material (ADR-0046) — so a slot driven only by a bridged ITEM (e.g. a hand-mined gem) still
   * resolves a driving material.
   *
   * @param flat the recipe's flat ingredient list
   * @param group the requirement group instance to match by reference
   * @param itemBridges resolved ITEM-ingredient bridges ({@code game_item} id → {@link Material})
   * @return the driving material id, or {@code null} for a slot with no material-bearing line
   */
  @Nullable
  private static UUID firstRequirementMaterialId(
      @NotNull List<BlueprintIngredient> flat,
      @NotNull BlueprintRequirementGroup group,
      @NotNull Map<UUID, Material> itemBridges) {
    for (BlueprintIngredient ingredient : flat) {
      if (ingredient.getRequirementGroup() != group) {
        continue;
      }
      if (ingredient.getKind() == BlueprintIngredientKind.RESOURCE) {
        Material material = ingredient.getMaterial();
        if (material != null && material.getId() != null) {
          return material.getId();
        }
      } else {
        Material bridged = bridgedItemMaterial(ingredient, itemBridges);
        if (bridged != null && bridged.getId() != null) {
          return bridged.getId();
        }
      }
    }
    return null;
  }

  /**
   * Resolves the bridged material for an ITEM ingredient, or {@code null} when the line is not
   * bridged (no resolved {@code game_item}, a craftable sub-assembly, or no name-matched material).
   * The map is pre-computed once per owned set by {@link #resolveItemBridges(Collection)}.
   *
   * @param ingredient the ITEM ingredient line
   * @param itemBridges resolved ITEM-ingredient bridges ({@code game_item} id → {@link Material})
   * @return the bridged material, or {@code null}
   */
  @Nullable
  private static Material bridgedItemMaterial(
      @NotNull BlueprintIngredient ingredient, @NotNull Map<UUID, Material> itemBridges) {
    GameItem gameItem = ingredient.getGameItem();
    if (gameItem == null || gameItem.getId() == null) {
      return null;
    }
    return itemBridges.get(gameItem.getId());
  }

  /**
   * Computes the quality floor for one ingredient line: the stricter of its {@code min_quality} and
   * its slot's no-degradation floor.
   *
   * @param ingredient the RESOURCE ingredient line
   * @return the quality floor (0..1000)
   */
  private static int slotFloor(@NotNull BlueprintIngredient ingredient) {
    BlueprintRequirementGroup group = ingredient.getRequirementGroup();
    int degradationFloor =
        group == null ? 0 : BlueprintModifierMath.noDegradationFloor(group.getModifiers());
    int minQuality = ingredient.getMinQuality() == null ? 0 : ingredient.getMinQuality();
    return Math.max(degradationFloor, minQuality);
  }

  /**
   * Computes availability for one material from its qualifying stock slices: the total SCU, the
   * shortfall against one craft, and the best-first SCU-weighted effective quality.
   *
   * @param slices the material's stock slices (may be {@code null} when none owned)
   * @param floor the quality floor below which stock does not count
   * @param required the SCU one craft needs of this material
   * @return the availability summary
   */
  @NotNull
  private static Availability availability(
      @Nullable List<OwnedStockSlice> slices, int floor, double required) {
    if (slices == null || slices.isEmpty()) {
      return new Availability(0.0d, null, required);
    }
    List<OwnedStockSlice> qualifying =
        slices.stream()
            .filter(
                s ->
                    s.quality() != null
                        && s.quality() >= floor
                        && s.totalScu() != null
                        && s.totalScu() > 0.0d)
            .sorted(Comparator.comparingInt(OwnedStockSlice::quality).reversed())
            .toList();
    double available = qualifying.stream().mapToDouble(OwnedStockSlice::totalScu).sum();
    double missing = Math.max(0.0d, required - available);

    double needed = required;
    double consumed = 0.0d;
    double weighted = 0.0d;
    for (OwnedStockSlice slice : qualifying) {
      double take = Math.min(slice.totalScu(), needed - consumed);
      if (take <= 0.0d) {
        break;
      }
      weighted += take * slice.quality();
      consumed += take;
      if (consumed >= needed) {
        break;
      }
    }
    Double effectiveQuality = consumed > 0.0d ? weighted / consumed : null;
    return new Availability(available, effectiveQuality, missing);
  }

  /**
   * Groups stock slices by material id, preserving the list per material for best-first
   * consumption.
   *
   * @param slices the flat slice list
   * @return slices keyed by material id
   */
  @NotNull
  private static Map<UUID, List<OwnedStockSlice>> slicesPerMaterial(
      @NotNull List<OwnedStockSlice> slices) {
    return slices.stream()
        .filter(s -> s.materialId() != null)
        .collect(Collectors.groupingBy(OwnedStockSlice::materialId));
  }

  /**
   * Rounds an SCU value to three decimals (the inventory amount scale).
   *
   * @param value the value
   * @return the rounded value
   */
  private static double round(double value) {
    return Math.round(value * 1000.0d) / 1000.0d;
  }

  /**
   * Rounds a nullable quality value to two decimals.
   *
   * @param value the value, or {@code null}
   * @return the rounded value, or {@code null}
   */
  @Nullable
  private static Double roundNullable(@Nullable Double value) {
    return value == null ? null : Math.round(value * 100.0d) / 100.0d;
  }

  /** Mutable accumulator for one material's pooled RESOURCE requirement across a recipe's slots. */
  private static final class MaterialRequirement {
    private final UUID materialId;
    private final String materialName;
    private final QuantityType quantityType;
    private double required;
    private int floor;

    private MaterialRequirement(
        UUID materialId, String materialName, @Nullable QuantityType quantityType) {
      this.materialId = materialId;
      this.materialName = materialName;
      this.quantityType = quantityType == null ? QuantityType.SCU : quantityType;
    }

    private void add(double requiredScu, int slotFloor) {
      this.required += requiredScu;
      this.floor = Math.max(this.floor, slotFloor);
    }
  }

  /**
   * Availability of one material: total qualifying SCU, the effective quality of the stock consumed
   * for one craft, and the SCU shortfall against one craft.
   *
   * @param available the total qualifying SCU
   * @param effectiveQuality the best-first SCU-weighted quality, or {@code null} when none
   *     qualifies
   * @param missing the SCU short of one craft
   */
  private record Availability(
      double available, @Nullable Double effectiveQuality, double missing) {}

  /**
   * One material's requirement paired with its inventory-only and refinery-included availability.
   *
   * @param requirement the pooled requirement
   * @param inventory availability from inventory alone
   * @param withRefinery availability with the open refinery yield folded in
   */
  private record MaterialResult(
      MaterialRequirement requirement, Availability inventory, Availability withRefinery) {}
}
