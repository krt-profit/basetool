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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintCraftabilityDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CraftabilityMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementModifier;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link BlueprintCraftabilityService} (#781, REQ-INV-019): craftable count,
 * best-first effective quality, the min-quality + no-degradation floor exclusion, the refinery
 * fold-in, and the ITEM / unresolved-recipe carve-outs.
 */
@ExtendWith(MockitoExtension.class)
class BlueprintCraftabilityServiceTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID BP_ID = UUID.randomUUID();
  private static final UUID MAT_A = UUID.randomUUID();
  private static final UUID MAT_B = UUID.randomUUID();
  private static final UUID GI_A = UUID.randomUUID();

  @Mock private PersonalBlueprintService personalBlueprintService;
  @Mock private BlueprintProductService blueprintProductService;
  @Mock private InventoryItemService inventoryItemService;
  @Mock private RefineryOrderService refineryOrderService;
  @Mock private BlueprintRepository blueprintRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private MaterialRepository materialRepository;
  @InjectMocks private BlueprintCraftabilityService service;

  @Test
  void computeForOwner_craftableCountIsTheLimitingMaterialAndQualityExcludesDegradingStock() {
    stubOwned(owned("widget", "Widget"));
    // Group 0: damage modifier ×0.95→×1.05 (no-degradation floor 500) + 10 SCU Iron + an ITEM line.
    // Group 1: no modifier + 5 SCU Tungsten.
    when(blueprintProductService.resolveRepresentativeBlueprints(any()))
        .thenReturn(Map.of("widget", twoSlotBlueprint()));
    when(inventoryItemService.getOwnedStockSlices(USER_ID))
        .thenReturn(
            List.of(
                new OwnedStockSlice(MAT_A, 1000, 25.0), // qualifies (≥ floor 500)
                new OwnedStockSlice(MAT_A, 300, 100.0), // excluded — below the no-degradation floor
                new OwnedStockSlice(MAT_B, 600, 12.0)));

    BlueprintCraftabilityDto dto = only(service.computeForOwner(USER_ID, false));

    assertEquals(BP_ID, dto.blueprintId());
    assertTrue(dto.recipeResolved());
    assertTrue(dto.hasResourceIngredients());
    assertTrue(dto.hasItemIngredients());
    assertEquals(2, dto.craftable()); // min(floor(25/10)=2, floor(12/5)=2)
    assertEquals(2, dto.craftableWithRefinery()); // includeRefinery=false → equals inventory
    assertEquals("Tungsten", dto.limitingMaterialName()); // ratio 2.4 < 2.5

    CraftabilityMaterialDto iron = material(dto, MAT_A);
    assertEquals(10.0, iron.requiredScu(), 1e-9);
    assertEquals(500, iron.qualityFloor());
    assertEquals(25.0, iron.availableScu(), 1e-9); // the 300-quality stack does not count
    assertEquals(1000.0, iron.effectiveQuality(), 1e-9); // one craft drawn entirely from q1000
    assertEquals(0.0, iron.missingScu(), 1e-9);
    assertEquals(2, iron.craftable());

    CraftabilityMaterialDto tungsten = material(dto, MAT_B);
    assertEquals(0, tungsten.qualityFloor());
    assertEquals(600.0, tungsten.effectiveQuality(), 1e-9);

    assertEquals(2, dto.groups().size());
    assertEquals(MAT_A, dto.groups().get(0).materialId());
    assertEquals(1000.0, dto.groups().get(0).effectiveQuality(), 1e-9);
    assertEquals(MAT_B, dto.groups().get(1).materialId());
    assertEquals(600.0, dto.groups().get(1).effectiveQuality(), 1e-9);
  }

  @Test
  void computeForOwner_belowFloorStockMakesTheBlueprintNotCraftable() {
    stubOwned(owned("widget", "Widget"));
    when(blueprintProductService.resolveRepresentativeBlueprints(any()))
        .thenReturn(Map.of("widget", twoSlotBlueprint()));
    when(inventoryItemService.getOwnedStockSlices(USER_ID))
        .thenReturn(
            List.of(
                new OwnedStockSlice(MAT_A, 300, 100.0), // below the no-degradation floor → excluded
                new OwnedStockSlice(MAT_B, 600, 50.0)));

    BlueprintCraftabilityDto dto = only(service.computeForOwner(USER_ID, false));

    assertEquals(0, dto.craftable());
    assertEquals("Iron", dto.limitingMaterialName());
    CraftabilityMaterialDto iron = material(dto, MAT_A);
    assertEquals(0.0, iron.availableScu(), 1e-9);
    assertEquals(10.0, iron.missingScu(), 1e-9);
    assertNull(iron.effectiveQuality());
  }

  @Test
  void computeForOwner_refineryYieldIsFoldedInAndShiftsTheEffectiveQuality() {
    stubOwned(owned("widget", "Widget"));
    when(blueprintProductService.resolveRepresentativeBlueprints(any()))
        .thenReturn(Map.of("widget", twoSlotBlueprint()));
    when(inventoryItemService.getOwnedStockSlices(USER_ID))
        .thenReturn(
            List.of(
                new OwnedStockSlice(
                    MAT_A, 1000, 6.0), // 6 < 10 → not craftable from inventory alone
                new OwnedStockSlice(MAT_B, 600, 50.0)));
    when(refineryOrderService.getOwnedOpenRefineryYieldSlices(USER_ID))
        .thenReturn(List.of(new OwnedStockSlice(MAT_A, 900, 8.0)));

    BlueprintCraftabilityDto dto = only(service.computeForOwner(USER_ID, true));

    assertEquals(0, dto.craftable()); // inventory alone
    assertEquals(1, dto.craftableWithRefinery()); // 6 + 8 = 14 → floor(14/10)
    CraftabilityMaterialDto iron = material(dto, MAT_A);
    assertEquals(6.0, iron.availableScu(), 1e-9);
    assertEquals(14.0, iron.availableScuWithRefinery(), 1e-9);
    // One craft (10 SCU) drawn best-first: 6 @ q1000 + 4 @ q900 → (6000 + 3600) / 10 = 960.
    assertEquals(960.0, iron.effectiveQualityWithRefinery(), 1e-9);
  }

  @Test
  void computeForOwner_pieceMaterialCountsInWholePiecesAndRoundsTheRequirement() {
    stubOwned(owned("widget", "Widget"));
    Blueprint bp = new Blueprint();
    BlueprintRequirementGroup slot = new BlueprintRequirementGroup();
    slot.setOrderIndex(0);
    slot.setName("Frame");
    bp.addRequirementGroup(slot);
    // A RESOURCE ingredient resolving to a PIECE material: its 2.6 per-craft quantity rounds to a
    // whole 3 pieces (parity with JobOrderItemService.roundForQuantityType), so 7 owned pieces
    // craft
    // floor(7/3) = 2 times — the count must be in pieces, not treated as SCU.
    bp.addIngredient(resource(0, material(MAT_A, "Hadanite", QuantityType.PIECE), 2.6, null, slot));
    when(blueprintProductService.resolveRepresentativeBlueprints(any()))
        .thenReturn(Map.of("widget", bp));
    when(inventoryItemService.getOwnedStockSlices(USER_ID))
        .thenReturn(List.of(new OwnedStockSlice(MAT_A, 400, 7.0)));

    BlueprintCraftabilityDto dto = only(service.computeForOwner(USER_ID, false));

    assertEquals(2, dto.craftable());
    assertEquals("Hadanite", dto.limitingMaterialName());
    CraftabilityMaterialDto hadanite = material(dto, MAT_A);
    assertEquals(QuantityType.PIECE, hadanite.quantityType());
    assertEquals(3.0, hadanite.requiredScu(), 1e-9); // 2.6 rounded up to a whole piece
    assertEquals(7.0, hadanite.availableScu(), 1e-9);
    assertEquals(0.0, hadanite.missingScu(), 1e-9);
    assertEquals(2, hadanite.craftable());
  }

  @Test
  void computeForOwner_pieceBridgedItemIngredientIsEvaluated() {
    stubOwned(owned("widget", "Widget"));
    // A recipe whose only material-bearing line is an ITEM the wiki counts in pieces (a hand-mined
    // gem such as Beradom) which is NOT craftable but exists as a PIECE material by name: it must
    // be
    // bridged and evaluated, not skipped (#840 follow-up, ADR-0046).
    Blueprint bp = new Blueprint();
    BlueprintRequirementGroup slot = new BlueprintRequirementGroup();
    slot.setOrderIndex(0);
    slot.setName("Frequency Controller");
    bp.addRequirementGroup(slot);
    GameItem beradom = gameItem(GI_A, "Beradom");
    bp.addIngredient(item(0, beradom, 2, null, slot));
    when(blueprintProductService.resolveRepresentativeBlueprints(any()))
        .thenReturn(Map.of("widget", bp));
    when(blueprintRepository.findCraftableOutputItemIds(any())).thenReturn(List.of());
    when(gameItemRepository.findAllById(any())).thenReturn(List.of(beradom));
    when(materialRepository.findByNameInIgnoreCase(any()))
        .thenReturn(List.of(material(MAT_A, "Beradom", QuantityType.PIECE)));
    when(inventoryItemService.getOwnedStockSlices(USER_ID))
        .thenReturn(List.of(new OwnedStockSlice(MAT_A, 400, 7.0)));

    BlueprintCraftabilityDto dto = only(service.computeForOwner(USER_ID, false));

    assertTrue(dto.recipeResolved());
    assertTrue(dto.hasResourceIngredients()); // the bridged ITEM counts as an evaluable requirement
    assertFalse(
        dto.hasItemIngredients()); // the only ITEM was bridged, so nothing stays unevaluated
    assertEquals(3, dto.craftable()); // floor(7 / 2)
    assertEquals("Beradom", dto.limitingMaterialName());

    CraftabilityMaterialDto beradomMat = material(dto, MAT_A);
    assertEquals(QuantityType.PIECE, beradomMat.quantityType());
    assertEquals(2.0, beradomMat.requiredScu(), 1e-9); // the per-craft whole-unit count
    assertEquals(7.0, beradomMat.availableScu(), 1e-9);
    assertEquals(0.0, beradomMat.missingScu(), 1e-9);
    assertEquals(3, beradomMat.craftable());

    // The slot overlay resolves its driving material from the bridged ITEM, so its slider defaults
    // to the stock's effective quality rather than the band maximum.
    assertEquals(1, dto.groups().size());
    assertEquals(MAT_A, dto.groups().get(0).materialId());
    assertEquals(400.0, dto.groups().get(0).effectiveQuality(), 1e-9);
  }

  @Test
  void computeForOwner_craftableItemIngredientStaysNotEvaluated() {
    stubOwned(owned("widget", "Widget"));
    // An ITEM ingredient whose game item is itself the output of a blueprint is a genuine
    // sub-assembly: it is NOT bridged to a material and stays "not evaluated", even if a material
    // of
    // the same name existed.
    Blueprint bp = new Blueprint();
    BlueprintRequirementGroup slot = new BlueprintRequirementGroup();
    slot.setOrderIndex(0);
    bp.addRequirementGroup(slot);
    bp.addIngredient(item(0, gameItem(GI_A, "Sub Widget"), 1, null, slot));
    when(blueprintProductService.resolveRepresentativeBlueprints(any()))
        .thenReturn(Map.of("widget", bp));
    when(blueprintRepository.findCraftableOutputItemIds(any())).thenReturn(List.of(GI_A));
    when(inventoryItemService.getOwnedStockSlices(USER_ID)).thenReturn(List.of());

    BlueprintCraftabilityDto dto = only(service.computeForOwner(USER_ID, false));

    assertTrue(dto.recipeResolved());
    assertTrue(dto.hasItemIngredients());
    assertFalse(dto.hasResourceIngredients());
    assertEquals(0, dto.craftable());
    assertTrue(dto.materials().isEmpty());
  }

  @Test
  void computeForOwner_scuMaterialReportsScuQuantityType() {
    stubOwned(owned("widget", "Widget"));
    when(blueprintProductService.resolveRepresentativeBlueprints(any()))
        .thenReturn(Map.of("widget", twoSlotBlueprint()));
    when(inventoryItemService.getOwnedStockSlices(USER_ID))
        .thenReturn(
            List.of(new OwnedStockSlice(MAT_A, 1000, 25.0), new OwnedStockSlice(MAT_B, 600, 12.0)));

    BlueprintCraftabilityDto dto = only(service.computeForOwner(USER_ID, false));

    assertEquals(QuantityType.SCU, material(dto, MAT_A).quantityType());
    assertEquals(QuantityType.SCU, material(dto, MAT_B).quantityType());
  }

  @Test
  void computeForOwner_itemOnlyRecipeIsNotAssessable() {
    stubOwned(owned("widget", "Widget"));
    Blueprint bp = new Blueprint();
    BlueprintRequirementGroup group = new BlueprintRequirementGroup();
    group.setOrderIndex(0);
    bp.addRequirementGroup(group);
    BlueprintIngredient item = new BlueprintIngredient();
    item.setOrderIndex(0);
    item.setKind(BlueprintIngredientKind.ITEM);
    item.setWikiNameSnapshot("Quantum Core");
    item.setQuantityUnits(1);
    item.setRequirementGroup(group);
    bp.addIngredient(item);
    when(blueprintProductService.resolveRepresentativeBlueprints(any()))
        .thenReturn(Map.of("widget", bp));

    BlueprintCraftabilityDto dto = only(service.computeForOwner(USER_ID, false));

    assertTrue(dto.recipeResolved());
    assertTrue(dto.hasItemIngredients());
    assertFalse(dto.hasResourceIngredients());
    assertEquals(0, dto.craftable());
    assertTrue(dto.materials().isEmpty());
  }

  @Test
  void computeForOwner_unresolvedRecipeIsReported() {
    stubOwned(owned("widget", "Widget"));
    when(blueprintProductService.resolveRepresentativeBlueprints(any())).thenReturn(Map.of());
    when(inventoryItemService.getOwnedStockSlices(USER_ID)).thenReturn(List.of());

    BlueprintCraftabilityDto dto = only(service.computeForOwner(USER_ID, false));

    assertFalse(dto.recipeResolved());
    assertFalse(dto.hasResourceIngredients());
    assertEquals(0, dto.craftable());
  }

  @Test
  void computeForOwner_returnsEmptyWhenNoBlueprintsOwned() {
    when(personalBlueprintService.listOwn(eq(USER_ID), isNull(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    assertTrue(service.computeForOwner(USER_ID, false).isEmpty());
  }

  /* ----------------------------------------------------------------- fixtures */

  private void stubOwned(PersonalBlueprintResponse... owned) {
    when(personalBlueprintService.listOwn(eq(USER_ID), isNull(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(owned)));
  }

  private static PersonalBlueprintResponse owned(String key, String name) {
    return new PersonalBlueprintResponse(BP_ID, key, name, null, null, null, true, 0L, null, null);
  }

  /**
   * Builds a two-slot recipe: slot 0 needs 10 SCU Iron and carries a ×0.95→×1.05 "higher is better"
   * modifier (no-degradation floor 500) plus one ITEM line; slot 1 needs 5 SCU Tungsten with no
   * modifier.
   *
   * @return the blueprint
   */
  private static Blueprint twoSlotBlueprint() {
    Blueprint bp = new Blueprint();

    BlueprintRequirementGroup core = new BlueprintRequirementGroup();
    core.setOrderIndex(0);
    core.setName("Core");
    core.addModifier(higher(0.95, 1.05));
    bp.addRequirementGroup(core);
    bp.addIngredient(resource(0, material(MAT_A, "Iron"), 10.0, null, core));
    BlueprintIngredient item = new BlueprintIngredient();
    item.setOrderIndex(1);
    item.setKind(BlueprintIngredientKind.ITEM);
    item.setWikiNameSnapshot("Focusing Lens");
    item.setQuantityUnits(1);
    item.setRequirementGroup(core);
    bp.addIngredient(item);

    BlueprintRequirementGroup frame = new BlueprintRequirementGroup();
    frame.setOrderIndex(1);
    frame.setName("Frame");
    bp.addRequirementGroup(frame);
    bp.addIngredient(resource(2, material(MAT_B, "Tungsten"), 5.0, null, frame));

    return bp;
  }

  private static Material material(UUID id, String name) {
    return material(id, name, QuantityType.SCU);
  }

  private static Material material(UUID id, String name, QuantityType quantityType) {
    Material material = new Material();
    material.setId(id);
    material.setName(name);
    material.setQuantityType(quantityType);
    return material;
  }

  private static BlueprintRequirementModifier higher(double atMin, double atMax) {
    BlueprintRequirementModifier modifier = new BlueprintRequirementModifier();
    modifier.setOrderIndex(0);
    modifier.setPropertyKey("weapon_damage");
    modifier.setLabel("Damage");
    modifier.setBetterWhen("higher");
    modifier.setQualityMin(0.0);
    modifier.setQualityMax(1000.0);
    modifier.setModifierAtMinQuality(atMin);
    modifier.setModifierAtMaxQuality(atMax);
    return modifier;
  }

  private static BlueprintIngredient resource(
      int order, Material mat, double scu, Integer minQuality, BlueprintRequirementGroup group) {
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setOrderIndex(order);
    ingredient.setKind(BlueprintIngredientKind.RESOURCE);
    ingredient.setMaterial(mat);
    ingredient.setQuantityScu(scu);
    ingredient.setMinQuality(minQuality);
    ingredient.setRequirementGroup(group);
    ingredient.setWikiNameSnapshot(mat.getName());
    return ingredient;
  }

  private static BlueprintIngredient item(
      int order,
      GameItem gameItem,
      int units,
      Integer minQuality,
      BlueprintRequirementGroup group) {
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setOrderIndex(order);
    ingredient.setKind(BlueprintIngredientKind.ITEM);
    ingredient.setGameItem(gameItem);
    ingredient.setQuantityUnits(units);
    ingredient.setMinQuality(minQuality);
    ingredient.setRequirementGroup(group);
    ingredient.setWikiNameSnapshot(gameItem.getName());
    return ingredient;
  }

  private static GameItem gameItem(UUID id, String name) {
    GameItem gameItem = new GameItem();
    gameItem.setId(id);
    gameItem.setName(name);
    return gameItem;
  }

  private static BlueprintCraftabilityDto only(List<BlueprintCraftabilityDto> list) {
    assertEquals(1, list.size());
    return list.get(0);
  }

  private static CraftabilityMaterialDto material(BlueprintCraftabilityDto dto, UUID materialId) {
    return dto.materials().stream()
        .filter(m -> materialId.equals(m.materialId()))
        .findFirst()
        .orElseThrow();
  }
}
