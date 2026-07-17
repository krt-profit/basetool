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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.BlueprintMapper;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintIdNameRow;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintProductDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintProductRow;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintRequirementGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintRequirementIngredientDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintRecipeResponse;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link BlueprintProductService}. */
@ExtendWith(MockitoExtension.class)
class BlueprintProductServiceTest {

  private static final String SUB = "owner-1";

  @Mock private BlueprintRepository blueprintRepository;
  @Mock private PersonalBlueprintRepository personalBlueprintRepository;
  @Mock private BlueprintMapper blueprintMapper;

  private BlueprintProductService service;

  @BeforeEach
  void setUp() {
    service =
        new BlueprintProductService(
            blueprintRepository,
            personalBlueprintRepository,
            new BlueprintNameNormalizer(),
            blueprintMapper);
  }

  private static BlueprintProductRow row(
      String outputName, String key, String manufacturer, UUID outputItemId) {
    return new BlueprintProductRow(outputName, key, manufacturer, outputItemId);
  }

  private static Blueprint blueprint(String outputName) {
    Blueprint blueprint = new Blueprint();
    blueprint.setOutputName(outputName);
    return blueprint;
  }

  private void noneOwned() {
    when(personalBlueprintRepository.findAllByOwnerSubAndProductKeyIn(
            eq(SUB), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
  }

  @Test
  void searchProducts_dedupesMultiVariantNamesAndCountsThem() {
    UUID itemId = UUID.randomUUID();
    when(blueprintRepository.findActiveProductRows(""))
        .thenReturn(
            List.of(
                row("Arclight Pistol", "BP_A", "Behring", itemId),
                row("Arclight Pistol", "BP_B", null, null)));
    noneOwned();

    List<BlueprintProductDto> result = service.searchProducts(null, 25, SUB);

    assertEquals(1, result.size());
    BlueprintProductDto dto = result.get(0);
    assertEquals("arclight pistol", dto.productKey());
    assertEquals("Arclight Pistol", dto.name());
    assertEquals(2, dto.variantCount());
    assertEquals("BP_A", dto.exampleKey());
    assertEquals("Behring", dto.manufacturerName());
    assertFalse(dto.ownedByCurrentUser());
  }

  @Test
  void searchProducts_passesTrimmedQueryToRepository() {
    when(blueprintRepository.findActiveProductRows("pistol")).thenReturn(List.of());

    service.searchProducts("  pistol  ", 25, SUB);

    verify(blueprintRepository).findActiveProductRows("pistol");
  }

  @Test
  void searchProducts_sortsAlphabeticallyAndCapsToLimit() {
    when(blueprintRepository.findActiveProductRows(""))
        .thenReturn(
            List.of(
                row("Bravo", "B", null, null),
                row("Alpha", "A", null, null),
                row("Charlie", "C", null, null)));
    noneOwned();

    List<BlueprintProductDto> result = service.searchProducts("", 2, SUB);

    assertEquals(2, result.size());
    assertEquals("Alpha", result.get(0).name());
    assertEquals("Bravo", result.get(1).name());
  }

  @Test
  void searchProducts_clampsNonPositiveLimitToOne() {
    when(blueprintRepository.findActiveProductRows(""))
        .thenReturn(List.of(row("Alpha", "A", null, null), row("Bravo", "B", null, null)));
    noneOwned();

    List<BlueprintProductDto> result = service.searchProducts("", 0, SUB);

    assertEquals(1, result.size());
  }

  @Test
  void searchProducts_marksProductsOwnedByCaller() {
    when(blueprintRepository.findActiveProductRows(""))
        .thenReturn(List.of(row("Alpha", "A", null, null), row("Bravo", "B", null, null)));
    when(personalBlueprintRepository.findAllByOwnerSubAndProductKeyIn(
            eq(SUB), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(PersonalBlueprint.builder().productKey("alpha").build()));

    List<BlueprintProductDto> result = service.searchProducts("", 25, SUB);

    BlueprintProductDto alpha =
        result.stream().filter(d -> d.name().equals("Alpha")).findFirst().orElseThrow();
    BlueprintProductDto bravo =
        result.stream().filter(d -> d.name().equals("Bravo")).findFirst().orElseThrow();
    assertTrue(alpha.ownedByCurrentUser());
    assertFalse(bravo.ownedByCurrentUser());
  }

  @Test
  void resolveByProductKey_returnsCanonicalProductWithOutputItemId() {
    UUID itemId = UUID.randomUUID();
    when(blueprintRepository.findActiveProductRows(""))
        .thenReturn(List.of(row("Arclight Pistol", "BP_A", "Behring", itemId)));

    Optional<ResolvedProduct> resolved = service.resolveByProductKey("arclight pistol");

    assertTrue(resolved.isPresent());
    assertEquals("arclight pistol", resolved.get().productKey());
    assertEquals("Arclight Pistol", resolved.get().productName());
    assertEquals(itemId, resolved.get().outputItemId());
  }

  @Test
  void resolveByProductKey_returnsEmptyForBlankKey() {
    assertTrue(service.resolveByProductKey("  ").isEmpty());
    verify(blueprintRepository, org.mockito.Mockito.never()).findActiveProductRows(anyString());
  }

  @Test
  void resolveByProductKey_returnsEmptyForUnknownKey() {
    when(blueprintRepository.findActiveProductRows("")).thenReturn(List.of());

    assertTrue(service.resolveByProductKey("does-not-exist").isEmpty());
  }

  @Test
  void resolveByGameItem_nullId_returnsEmpty() {
    assertTrue(service.resolveByGameItem(null).isEmpty());
    verify(blueprintRepository, org.mockito.Mockito.never()).findByOutputItemId(any());
  }

  @Test
  void resolveByGameItem_derivesProductFromTheRowsGameItem() {
    // covers REQ-MARKET-014 — the stock-backed item-offer identity bridge: a game item resolves to
    // the same canonical product a free-stated offer of that item would carry.
    UUID gameItemId = UUID.randomUUID();
    UUID outputItemId = UUID.randomUUID();
    when(blueprintRepository.findByOutputItemId(gameItemId))
        .thenReturn(List.of(blueprint("Arclight Pistol")));
    when(blueprintRepository.findActiveProductRows(""))
        .thenReturn(List.of(row("Arclight Pistol", "BP_A", "Behring", outputItemId)));

    Optional<ResolvedProduct> resolved = service.resolveByGameItem(gameItemId);

    assertTrue(resolved.isPresent());
    assertEquals("arclight pistol", resolved.get().productKey());
    assertEquals("Arclight Pistol", resolved.get().productName());
    assertEquals(outputItemId, resolved.get().outputItemId());
  }

  @Test
  void resolveByGameItem_multipleBlueprints_picksLowestKeyRegardlessOfRowOrder() {
    // covers REQ-MARKET-014 / finding #3 — findByOutputItemId has no ORDER BY, so a game item
    // produced by several active blueprints whose names normalize to different keys must resolve
    // deterministically: the candidate keys are sorted and the lowest ("alpha widget") wins in
    // BOTH row orders.
    UUID itemForwardOrder = UUID.randomUUID();
    UUID itemReverseOrder = UUID.randomUUID();
    when(blueprintRepository.findByOutputItemId(itemForwardOrder))
        .thenReturn(List.of(blueprint("Zeta Widget"), blueprint("Alpha Widget")));
    when(blueprintRepository.findByOutputItemId(itemReverseOrder))
        .thenReturn(List.of(blueprint("Alpha Widget"), blueprint("Zeta Widget")));
    when(blueprintRepository.findActiveProductRows(""))
        .thenReturn(
            List.of(
                row("Alpha Widget", "BP_ALPHA", null, null),
                row("Zeta Widget", "BP_ZETA", null, null)));

    Optional<ResolvedProduct> forward = service.resolveByGameItem(itemForwardOrder);
    Optional<ResolvedProduct> reverse = service.resolveByGameItem(itemReverseOrder);

    assertTrue(forward.isPresent());
    assertTrue(reverse.isPresent());
    assertEquals("alpha widget", forward.get().productKey());
    assertEquals(
        forward.get().productKey(),
        reverse.get().productKey(),
        "the derived product key is stable regardless of the DB row order");
  }

  @Test
  void resolveByGameItem_noProducingBlueprint_returnsEmpty() {
    UUID gameItemId = UUID.randomUUID();
    when(blueprintRepository.findByOutputItemId(gameItemId)).thenReturn(List.of());

    assertTrue(service.resolveByGameItem(gameItemId).isEmpty());
  }

  @Test
  void scwikiKeyToProductKeyIndex_mapsLowercasedKeyToProductKey() {
    // covers REQ-INV-019 — the index lower-cases the scwiki_key and maps it to the normalized
    // product key, so the scmdb.net tag (a lower-cased DataForge key) resolves the product.
    when(blueprintRepository.findActiveProductRows(""))
        .thenReturn(
            List.of(
                row("Arclight Pistol", "BP_CRAFT_AMRS_LaserCannon_S1", null, null),
                row("P8-AR Rifle", "BP_CRAFT_behr_rifle_ballistic_02_civilian", null, null)));

    Map<String, String> index = service.scwikiKeyToProductKeyIndex();

    assertEquals("arclight pistol", index.get("bp_craft_amrs_lasercannon_s1"));
    assertEquals("p8-ar rifle", index.get("bp_craft_behr_rifle_ballistic_02_civilian"));
    assertEquals(2, index.size());
  }

  @Test
  void scwikiKeyToProductKeyIndex_excludesAmbiguousKeys() {
    // covers REQ-INV-019 — a scwiki_key (not UNIQUE) shared by two recipes with diverging output
    // names is ambiguous and excluded, so the tag match never picks an arbitrary product.
    when(blueprintRepository.findActiveProductRows(""))
        .thenReturn(
            List.of(
                row("Arclight Pistol", "BP_DUP", null, null),
                row("Different Product", "BP_DUP", null, null),
                row("P8-AR Rifle", "BP_UNIQUE", null, null)));

    Map<String, String> index = service.scwikiKeyToProductKeyIndex();

    assertFalse(index.containsKey("bp_dup"));
    assertEquals("p8-ar rifle", index.get("bp_unique"));
    assertEquals(1, index.size());
  }

  @Test
  void resolveRecipe_mapsRepresentativeRecipeAndCountsVariants() {
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    when(blueprintRepository.findActiveIdNameRows())
        .thenReturn(
            List.of(
                new BlueprintIdNameRow(id1, "Arclight Pistol"),
                new BlueprintIdNameRow(id2, "Arclight Pistol")));
    Blueprint recipe = new Blueprint();
    when(blueprintRepository.findById(id1)).thenReturn(Optional.of(recipe));
    List<BlueprintRequirementGroupDto> groups =
        List.of(new BlueprintRequirementGroupDto("Emitter", "EMITTER", 1, List.of(), List.of()));
    List<BlueprintRequirementIngredientDto> ingredients =
        List.of(new BlueprintRequirementIngredientDto("RESOURCE", "Tin", 0.36, null, null, "SCU"));
    when(blueprintMapper.toGroupDtos(any())).thenReturn(groups);
    when(blueprintMapper.toIngredientDtos(any())).thenReturn(ingredients);

    Optional<PersonalBlueprintRecipeResponse> result = service.resolveRecipe("arclight pistol");

    assertTrue(result.isPresent());
    assertEquals("Arclight Pistol", result.get().productName());
    assertEquals(2, result.get().variantCount());
    assertSame(groups, result.get().requirementGroups());
    assertSame(ingredients, result.get().ingredients());
  }

  @Test
  void resolveRecipe_returnsEmptyForBlankKey() {
    assertTrue(service.resolveRecipe("  ").isEmpty());
    verify(blueprintRepository, org.mockito.Mockito.never()).findActiveIdNameRows();
  }

  @Test
  void resolveRecipe_returnsEmptyForUnknownKey() {
    when(blueprintRepository.findActiveIdNameRows())
        .thenReturn(List.of(new BlueprintIdNameRow(UUID.randomUUID(), "Other Item")));

    assertTrue(service.resolveRecipe("ghost").isEmpty());
  }
}
