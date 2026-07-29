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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemKind;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ItemDerivationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link JobOrderItemService}: blueprint-driven material derivation (amount scaling,
 * quality default vs override, quantity-type rounding, ignoring sub-assembly and unresolved
 * ingredients), the blueprint/item consistency check, and the per-order material aggregation.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderItemServiceTest {

  @Mock private BlueprintRepository blueprintRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private MaterialMapper materialMapper;
  @InjectMocks private JobOrderItemService service;

  @Test
  void requiredMaterialIdsCollectsMaterialLinesForMaterialOrderAndDerivedForItemOrder() {
    // REQ-ORDERS-018: the kind-agnostic required-material set. MATERIAL order -> its material
    // lines;
    // ITEM order -> the snapshotted per-item materials. Each kind's other collection is empty.
    Material steel = new Material();
    steel.setId(UUID.randomUUID());
    Material gold = new Material();
    gold.setId(UUID.randomUUID());

    JobOrder materialOrder = new JobOrder();
    materialOrder.addMaterial(JobOrderMaterial.builder().material(steel).amount(5.0).build());
    materialOrder.addMaterial(JobOrderMaterial.builder().material(gold).amount(1.0).build());

    assertThat(service.requiredMaterialIds(materialOrder))
        .containsExactlyInAnyOrder(steel.getId(), gold.getId());

    Material iron = new Material();
    iron.setId(UUID.randomUUID());
    JobOrderItemMaterial req =
        JobOrderItemMaterial.builder()
            .material(iron)
            .requiredQuantity(2.0)
            .qualityRequirement(QualityRequirement.GOOD)
            .build();
    JobOrderItem item = new JobOrderItem();
    item.addMaterial(req);
    JobOrder itemOrder = new JobOrder();
    itemOrder.setType(JobOrderType.ITEM);
    itemOrder.addItem(item);

    assertThat(service.requiredMaterialIds(itemOrder)).containsExactly(iron.getId());
  }

  // covers REQ-INV-031 (requested game-item set: ITEM order -> distinct line game items)
  @Test
  void requiredGameItemIdsCollectsDistinctLineGameItemsForItemOrder() {
    // Given an ITEM order with two lines ordering the same weapon plus one ordering a scope, and a
    // line whose gameItem is unresolved (null) — the null must be skipped, the duplicate collapsed.
    GameItem weapon = gameItem("Ballista", GameItemKind.WEAPON);
    GameItem scope = gameItem("Scope", GameItemKind.WEAPON_ATTACHMENT);

    JobOrder itemOrder = new JobOrder();
    itemOrder.setType(JobOrderType.ITEM);
    itemOrder.addItem(line(weapon));
    itemOrder.addItem(line(weapon));
    itemOrder.addItem(line(scope));
    itemOrder.addItem(line(null));

    // When / Then
    assertThat(service.requiredGameItemIds(itemOrder))
        .containsExactly(weapon.getId(), scope.getId());
  }

  // covers REQ-INV-031 (a MATERIAL order requests no game items => no item-stock link possible)
  @Test
  void requiredGameItemIdsIsEmptyForMaterialOrder() {
    // Given a MATERIAL order (it has material lines, never item lines)
    Material steel = new Material();
    steel.setId(UUID.randomUUID());
    JobOrder materialOrder = new JobOrder();
    materialOrder.addMaterial(JobOrderMaterial.builder().material(steel).amount(5.0).build());

    // When / Then — empty set = the item-stock link gate rejects every game item for this order
    assertThat(service.requiredGameItemIds(materialOrder)).isEmpty();
  }

  /**
   * Builds a bare ordered item line for the given game item — enough for the requested-game-item
   * collection, which reads only the line's {@code gameItem} reference.
   *
   * @param gameItem the ordered game item, or {@code null} for an unresolved line
   * @return the assembled line
   */
  private static JobOrderItem line(GameItem gameItem) {
    JobOrderItem line = new JobOrderItem();
    line.setGameItem(gameItem);
    return line;
  }

  @Test
  void buildItemLineDerivesResourceMaterialsScalingByAmountWithQualityDefaultAndOverride() {
    // Given a weapon blueprint with two RESOURCE ingredients plus an ITEM and an unresolved line.
    GameItem weapon = gameItem("Ballista", GameItemKind.WEAPON);
    Material steel = material("Steel", QuantityType.SCU);
    Material screws = material("Screws", QuantityType.PIECE);
    Blueprint blueprint = blueprint(weapon);
    blueprint.addIngredient(resource(steel, 2.5, 650)); // default GOOD (minQuality 650)
    blueprint.addIngredient(resource(screws, 4.0, null)); // default NONE
    blueprint.addIngredient(itemIngredient(gameItem("Scope", GameItemKind.WEAPON_ATTACHMENT), 1));
    blueprint.addIngredient(unresolvedResource(9.0)); // material == null, must be skipped

    when(gameItemRepository.findById(weapon.getId())).thenReturn(Optional.of(weapon));
    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));

    // When ordering 3 units, overriding screws to GOOD and leaving steel at its default.
    CreateJobOrderItemLineDto line =
        new CreateJobOrderItemLineDto(
            null,
            weapon.getId(),
            blueprint.getId(),
            3,
            List.of(new CreateJobOrderItemMaterialDto(screws.getId(), QualityRequirement.GOOD)),
            null,
            null);
    JobOrderItem built = service.buildItemLine(line);

    // Then only the two resolved RESOURCE materials are snapshotted, scaled by the amount.
    assertThat(built.getAmount()).isEqualTo(3);
    assertThat(built.getDeliveredAmount()).isZero();
    assertThat(built.getMaterials()).hasSize(2);

    JobOrderItemMaterial steelReq = requirementFor(built, steel);
    assertThat(steelReq.getRequiredQuantity()).isEqualTo(7.5);
    assertThat(steelReq.getQualityRequirement()).isEqualTo(QualityRequirement.GOOD);

    JobOrderItemMaterial screwsReq = requirementFor(built, screws);
    assertThat(screwsReq.getRequiredQuantity()).isEqualTo(12.0);
    assertThat(screwsReq.getQualityRequirement()).isEqualTo(QualityRequirement.GOOD);
  }

  @Test
  void buildItemLineRoundsPieceQuantitiesToWholeNumbers() {
    GameItem item = gameItem("Crate", GameItemKind.GENERIC);
    Material bolts = material("Bolts", QuantityType.PIECE);
    Blueprint blueprint = blueprint(item);
    blueprint.addIngredient(resource(bolts, 2.5, null)); // 2.5 * 3 = 7.5 -> rounds to 8

    when(gameItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));

    JobOrderItem built =
        service.buildItemLine(
            new CreateJobOrderItemLineDto(
                null, item.getId(), blueprint.getId(), 3, List.of(), null, null));

    assertThat(requirementFor(built, bolts).getRequiredQuantity()).isEqualTo(8.0);
  }

  @Test
  void buildItemLineRoundsScuQuantitiesAwayFromFloatingPointNoise() {
    // 0.36 SCU * 5 evaluates to 1.7999999999999998 as a binary double; the snapshot must store 1.8.
    GameItem weapon = gameItem("Longsword", GameItemKind.WEAPON);
    Material iron = material("Iron", QuantityType.SCU);
    Blueprint blueprint = blueprint(weapon);
    blueprint.addIngredient(resource(iron, 0.36, null));

    when(gameItemRepository.findById(weapon.getId())).thenReturn(Optional.of(weapon));
    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));

    JobOrderItem built =
        service.buildItemLine(
            new CreateJobOrderItemLineDto(
                null, weapon.getId(), blueprint.getId(), 5, List.of(), null, null));

    assertThat(requirementFor(built, iron).getRequiredQuantity()).isEqualTo(1.8);
  }

  @Test
  void buildItemLineRejectsBlueprintThatDoesNotProduceTheItem() {
    GameItem ordered = gameItem("Ballista", GameItemKind.WEAPON);
    GameItem other = gameItem("Other", GameItemKind.WEAPON);
    Blueprint blueprint = blueprint(other); // outputs a different item

    when(gameItemRepository.findById(ordered.getId())).thenReturn(Optional.of(ordered));
    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));

    CreateJobOrderItemLineDto line =
        new CreateJobOrderItemLineDto(
            null, ordered.getId(), blueprint.getId(), 1, List.of(), null, null);

    assertThatThrownBy(() -> service.buildItemLine(line))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("does not produce");
  }

  @Test
  void aggregateMaterialsGroupsByMaterialAndQualitySummingAcrossLines() {
    Material steel = material("Steel", QuantityType.SCU);
    stubMapper(steel);

    JobOrder order = JobOrder.builder().type(JobOrderType.ITEM).build();
    order.addItem(itemLine(steel, 5.0, QualityRequirement.GOOD));
    order.addItem(itemLine(steel, 3.0, QualityRequirement.GOOD));
    order.addItem(itemLine(steel, 2.0, QualityRequirement.NONE));

    List<AggregatedMaterialDto> aggregated = service.aggregateMaterials(order);

    // Two rows: Steel/GOOD = 8.0 (5+3), Steel/NONE = 2.0.
    assertThat(aggregated).hasSize(2);
    AggregatedMaterialDto good =
        aggregated.stream()
            .filter(a -> a.qualityRequirement() == QualityRequirement.GOOD)
            .findFirst()
            .orElseThrow();
    AggregatedMaterialDto none =
        aggregated.stream()
            .filter(a -> a.qualityRequirement() == QualityRequirement.NONE)
            .findFirst()
            .orElseThrow();
    assertThat(good.totalQuantity()).isEqualTo(8.0);
    assertThat(none.totalQuantity()).isEqualTo(2.0);
  }

  @Test
  void aggregateMaterialsRoundsSummedScuQuantitiesAwayFromFloatingPointNoise() {
    // 0.1 + 0.2 evaluates to 0.30000000000000004 as a binary double; the aggregate must report 0.3.
    Material steel = material("Steel", QuantityType.SCU);
    stubMapper(steel);

    JobOrder order = JobOrder.builder().type(JobOrderType.ITEM).build();
    order.addItem(itemLine(steel, 0.1, QualityRequirement.NONE));
    order.addItem(itemLine(steel, 0.2, QualityRequirement.NONE));

    List<AggregatedMaterialDto> aggregated = service.aggregateMaterials(order);

    assertThat(aggregated).hasSize(1);
    assertThat(aggregated.get(0).totalQuantity()).isEqualTo(0.3);
  }

  @Test
  void aggregateMaterialsReducesOutstandingDemandByManufacturedUnits() {
    // A 4-unit line needing 160 SCU Steel total; 1 unit already manufactured leaves 3 → the
    // outstanding aggregate is only 160 × 3 / 4 = 120 (REQ-ORDERS-025).
    Material steel = material("Steel", QuantityType.SCU);
    stubMapper(steel);

    JobOrderItem line = JobOrderItem.builder().amount(4).manufacturedAmount(1).build();
    line.addMaterial(
        JobOrderItemMaterial.builder()
            .material(steel)
            .requiredQuantity(160.0)
            .qualityRequirement(QualityRequirement.NONE)
            .build());
    JobOrder order = JobOrder.builder().type(JobOrderType.ITEM).build();
    order.addItem(line);

    List<AggregatedMaterialDto> aggregated = service.aggregateMaterials(order);

    assertThat(aggregated).hasSize(1);
    assertThat(aggregated.get(0).totalQuantity()).isEqualTo(120.0);
  }

  @Test
  void aggregateMaterialsIsZeroForAFullyManufacturedLine() {
    // Every ordered unit produced → no outstanding material demand, but the bucket row is kept so
    // its quality/claims stay visible (REQ-ORDERS-025).
    Material steel = material("Steel", QuantityType.SCU);
    stubMapper(steel);

    JobOrderItem line = JobOrderItem.builder().amount(2).manufacturedAmount(2).build();
    line.addMaterial(
        JobOrderItemMaterial.builder()
            .material(steel)
            .requiredQuantity(80.0)
            .qualityRequirement(QualityRequirement.NONE)
            .build());
    JobOrder order = JobOrder.builder().type(JobOrderType.ITEM).build();
    order.addItem(line);

    List<AggregatedMaterialDto> aggregated = service.aggregateMaterials(order);

    assertThat(aggregated).hasSize(1);
    assertThat(aggregated.get(0).totalQuantity()).isEqualTo(0.0);
  }

  @Test
  void deriveForPreviewReturnsResolvedMaterialsSubAssembliesAndUnresolvedNames() {
    GameItem weapon = gameItem("Ballista", GameItemKind.WEAPON);
    Material steel = material("Steel", QuantityType.SCU);
    stubMapper(steel);
    GameItem scope = gameItem("Scope", GameItemKind.WEAPON_ATTACHMENT);
    Blueprint blueprint = blueprint(weapon);
    blueprint.addIngredient(resource(steel, 2.0, 650));
    blueprint.addIngredient(itemIngredient(scope, 2));
    blueprint.addIngredient(unresolvedResource(5.0));

    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));
    when(blueprintRepository.findByOutputItemId(scope.getId())).thenReturn(List.of());

    ItemDerivationDto preview = service.deriveForPreview(blueprint.getId(), 3);

    assertThat(preview.amount()).isEqualTo(3);
    assertThat(preview.materials()).hasSize(1);
    assertThat(preview.materials().get(0).requiredQuantity()).isEqualTo(6.0);
    assertThat(preview.materials().get(0).defaultQuality()).isEqualTo(QualityRequirement.GOOD);
    assertThat(preview.subAssemblies()).hasSize(1);
    assertThat(preview.subAssemblies().get(0).quantity()).isEqualTo(6);
    assertThat(preview.subAssemblies().get(0).gameItem().name()).isEqualTo("Scope");
    assertThat(preview.unresolvedIngredients()).containsExactly("Unknownium");
  }

  @Test
  void deriveForPreviewRoundsScuQuantitiesAwayFromFloatingPointNoise() {
    // 0.36 SCU * 5 evaluates to 1.7999999999999998 as a binary double; the preview must show 1.8.
    GameItem weapon = gameItem("Longsword", GameItemKind.WEAPON);
    Material iron = material("Iron", QuantityType.SCU);
    stubMapper(iron);
    Blueprint blueprint = blueprint(weapon);
    blueprint.addIngredient(resource(iron, 0.36, null));

    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));

    ItemDerivationDto preview = service.deriveForPreview(blueprint.getId(), 5);

    assertThat(preview.materials()).hasSize(1);
    assertThat(preview.materials().get(0).requiredQuantity()).isEqualTo(1.8);
  }

  @Test
  void deriveForPreviewBridgesNonCraftableItemIngredientIntoMaterials() {
    // A wiki ITEM ingredient (Beradom, counted in pieces) with no own blueprint but an existing
    // PIECE material of the same name must surface as a material requirement, not a sub-assembly.
    GameItem weapon = gameItem("Palisade", GameItemKind.WEAPON);
    GameItem beradomItem = gameItem("Beradom", GameItemKind.GENERIC);
    Material beradomMaterial = material("Beradom", QuantityType.PIECE);
    stubMapper(beradomMaterial);
    Blueprint blueprint = blueprint(weapon);
    blueprint.addIngredient(itemIngredient(beradomItem, 20));

    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));
    when(blueprintRepository.findByOutputItemId(beradomItem.getId())).thenReturn(List.of());
    when(materialRepository.findByNameIgnoreCase("Beradom"))
        .thenReturn(Optional.of(beradomMaterial));

    ItemDerivationDto preview = service.deriveForPreview(blueprint.getId(), 3);

    assertThat(preview.subAssemblies()).isEmpty();
    assertThat(preview.materials()).hasSize(1);
    assertThat(preview.materials().get(0).material().name()).isEqualTo("Beradom");
    assertThat(preview.materials().get(0).requiredQuantity()).isEqualTo(60.0); // 20 pieces * 3
    assertThat(preview.materials().get(0).defaultQuality()).isEqualTo(QualityRequirement.NONE);
  }

  @Test
  void deriveForPreviewKeepsCraftableItemAsSubAssemblyAndDoesNotBridge() {
    // A craftable ITEM ingredient (has its own blueprint) stays an adoptable sub-assembly and is
    // never bridged to a material, even though the bridge would otherwise look one up.
    GameItem rifle = gameItem("Rifle", GameItemKind.WEAPON);
    GameItem scope = gameItem("Scope", GameItemKind.WEAPON_ATTACHMENT);
    Blueprint blueprint = blueprint(rifle);
    blueprint.addIngredient(itemIngredient(scope, 1));

    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));
    when(blueprintRepository.findByOutputItemId(scope.getId()))
        .thenReturn(List.of(blueprint(scope)));

    ItemDerivationDto preview = service.deriveForPreview(blueprint.getId(), 1);

    assertThat(preview.materials()).isEmpty();
    assertThat(preview.subAssemblies()).hasSize(1);
    assertThat(preview.subAssemblies().get(0).gameItem().name()).isEqualTo("Scope");
  }

  @Test
  void buildItemLineSnapshotsBridgedItemIngredientAsPieceMaterial() {
    // Persist path: the bridged Beradom requirement is snapshotted onto the order as a PIECE
    // material alongside the regular RESOURCE materials.
    GameItem weapon = gameItem("Palisade", GameItemKind.WEAPON);
    Material riccite = material("Riccite", QuantityType.SCU);
    GameItem beradomItem = gameItem("Beradom", GameItemKind.GENERIC);
    Material beradomMaterial = material("Beradom", QuantityType.PIECE);
    Blueprint blueprint = blueprint(weapon);
    blueprint.addIngredient(resource(riccite, 1.5, 0));
    blueprint.addIngredient(itemIngredient(beradomItem, 20));

    when(gameItemRepository.findById(weapon.getId())).thenReturn(Optional.of(weapon));
    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));
    when(blueprintRepository.findByOutputItemId(beradomItem.getId())).thenReturn(List.of());
    when(materialRepository.findByNameIgnoreCase("Beradom"))
        .thenReturn(Optional.of(beradomMaterial));

    JobOrderItem built =
        service.buildItemLine(
            new CreateJobOrderItemLineDto(
                null, weapon.getId(), blueprint.getId(), 2, List.of(), null, null));

    assertThat(built.getMaterials()).hasSize(2);
    JobOrderItemMaterial ricciteReq = requirementFor(built, riccite);
    assertThat(ricciteReq.getRequiredQuantity()).isEqualTo(3.0); // 1.5 SCU * 2
    JobOrderItemMaterial beradomReq = requirementFor(built, beradomMaterial);
    assertThat(beradomReq.getRequiredQuantity()).isEqualTo(40.0); // 20 pieces * 2, whole
    assertThat(beradomReq.getQualityRequirement()).isEqualTo(QualityRequirement.NONE);
  }

  // covers REQ-ORDERS-032 (an edit re-derives a line in place; booked production is not discarded)
  @Test
  void applyItemLineReDerivesMaterialsInPlaceAndKeepsBookedProduction() {
    // Given an existing line that already has 6 of 10 units manufactured and 2 delivered, carrying
    // a stale snapshot (Screws) from the recipe it was created with.
    GameItem weapon = gameItem("Ballista", GameItemKind.WEAPON);
    Material screws = material("Screws", QuantityType.PIECE);
    Material steel = material("Steel", QuantityType.SCU);
    Blueprint corrected = blueprint(weapon);
    corrected.addIngredient(resource(steel, 1.5, null));

    JobOrderItem existing = JobOrderItem.builder().amount(10).build();
    existing.setId(UUID.randomUUID());
    existing.setGameItem(weapon);
    existing.setManufacturedAmount(6);
    existing.setDeliveredAmount(2);
    existing.addMaterial(
        JobOrderItemMaterial.builder()
            .material(screws)
            .requiredQuantity(40.0)
            .qualityRequirement(QualityRequirement.NONE)
            .build());

    when(gameItemRepository.findById(weapon.getId())).thenReturn(Optional.of(weapon));
    when(blueprintRepository.findById(corrected.getId())).thenReturn(Optional.of(corrected));

    // When re-deriving that same line against the corrected blueprint at an unchanged amount
    service.applyItemLine(
        existing,
        new CreateJobOrderItemLineDto(
            existing.getId(), weapon.getId(), corrected.getId(), 10, List.of(), null, null));

    // Then the snapshot follows the new recipe...
    assertThat(existing.getMaterials()).hasSize(1);
    assertThat(requirementFor(existing, steel).getRequiredQuantity()).isEqualTo(15.0);
    assertThat(existing.getBlueprint()).isSameAs(corrected);
    // ...while the production counters — the whole point of editing in place — survive untouched.
    assertThat(existing.getManufacturedAmount()).isEqualTo(6);
    assertThat(existing.getDeliveredAmount()).isEqualTo(2);
  }

  // covers REQ-ORDERS-033 (a line whose blueprint drifted away from the ordered item is flagged)
  @Test
  void toItemDtosFlagsLinesWhoseBlueprintNoLongerProducesTheOrderedItem() {
    // Given two lines: one consistent, one whose blueprint now outputs a different item — exactly
    // what an SC-Wiki re-point leaves behind, since the pairing is only validated at write time.
    GameItem cooler = gameItem("Cryo-Star SL", GameItemKind.VEHICLE_ITEM);
    GameItem heatSink = gameItem("HeatSink", GameItemKind.VEHICLE_ITEM);

    JobOrderItem consistent = JobOrderItem.builder().amount(1).build();
    consistent.setId(UUID.randomUUID());
    consistent.setGameItem(cooler);
    consistent.setBlueprint(blueprint(cooler));

    JobOrderItem drifted = JobOrderItem.builder().amount(3).build();
    drifted.setId(UUID.randomUUID());
    drifted.setGameItem(cooler);
    drifted.setBlueprint(blueprint(heatSink));

    JobOrder order = new JobOrder();
    order.setType(JobOrderType.ITEM);
    order.addItem(consistent);
    order.addItem(drifted);

    // When / Then — only the drifted line carries the warning flag.
    assertThat(service.toItemDtos(order))
        .extracting(dto -> dto.id() + ":" + dto.blueprintStale())
        .containsExactlyInAnyOrder(consistent.getId() + ":false", drifted.getId() + ":true");
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private void stubMapper(Material material) {
    lenient()
        .when(materialMapper.toDto(material))
        .thenReturn(
            new MaterialDto(
                material.getId(),
                material.getName(),
                null,
                material.getQuantityType().name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
  }

  private JobOrderItem itemLine(Material material, double quantity, QualityRequirement quality) {
    JobOrderItem item = JobOrderItem.builder().amount(1).build();
    item.addMaterial(
        JobOrderItemMaterial.builder()
            .material(material)
            .requiredQuantity(quantity)
            .qualityRequirement(quality)
            .build());
    return item;
  }

  private static JobOrderItemMaterial requirementFor(JobOrderItem item, Material material) {
    return item.getMaterials().stream()
        .filter(m -> m.getMaterial().getId().equals(material.getId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no requirement for material " + material.getName()));
  }

  private static GameItem gameItem(String name, GameItemKind kind) {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setName(name);
    item.setKind(kind);
    return item;
  }

  private static Material material(String name, QuantityType quantityType) {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setName(name);
    material.setQuantityType(quantityType);
    return material;
  }

  private static Blueprint blueprint(GameItem output) {
    Blueprint blueprint = new Blueprint();
    blueprint.setId(UUID.randomUUID());
    blueprint.setOutputItem(output);
    blueprint.setOutputName(output.getName());
    blueprint.setScwikiKey(output.getName().toLowerCase());
    return blueprint;
  }

  private static BlueprintIngredient resource(
      Material material, double quantityScu, Integer minQuality) {
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setKind(BlueprintIngredientKind.RESOURCE);
    ingredient.setMaterial(material);
    ingredient.setQuantityScu(quantityScu);
    ingredient.setMinQuality(minQuality);
    return ingredient;
  }

  private static BlueprintIngredient unresolvedResource(double quantityScu) {
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setKind(BlueprintIngredientKind.RESOURCE);
    ingredient.setQuantityScu(quantityScu);
    ingredient.setWikiNameSnapshot("Unknownium");
    return ingredient;
  }

  private static BlueprintIngredient itemIngredient(GameItem gameItem, int quantityUnits) {
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setKind(BlueprintIngredientKind.ITEM);
    ingredient.setGameItem(gameItem);
    ingredient.setQuantityUnits(quantityUnits);
    return ingredient;
  }
}
