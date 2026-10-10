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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse.Result;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse.Status;
import de.greluc.krt.profit.basetool.backend.kernel.FuzzyNameMatcher;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.BlueprintExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository.ExchangeItemKeyRow;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.BlueprintImportService;
import de.greluc.krt.profit.basetool.backend.service.BlueprintNameNormalizer;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ExchangeResolveServiceTest {

  @Mock private BlueprintProductService blueprintProductService;
  @Mock private BlueprintExternalAliasRepository blueprintAliasRepository;
  @Mock private PersonalBlueprintRepository personalBlueprintRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private AuditService auditService;
  @Mock private BlueprintRepository blueprintRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private MaterialExternalAliasRepository materialAliasRepository;
  @Mock private ShipTypeRepository shipTypeRepository;

  private SimpleMeterRegistry meterRegistry;
  private ExchangeResolveService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    BlueprintImportService importService =
        new BlueprintImportService(
            JsonMapper.builder().build(),
            blueprintProductService,
            new BlueprintNameNormalizer(),
            new FuzzyNameMatcher(),
            blueprintAliasRepository,
            personalBlueprintRepository,
            gameItemRepository,
            auditService);
    service =
        new ExchangeResolveService(
            blueprintProductService,
            importService,
            new BlueprintNameNormalizer(),
            new FuzzyNameMatcher(),
            blueprintRepository,
            gameItemRepository,
            materialRepository,
            materialAliasRepository,
            shipTypeRepository,
            meterRegistry);
    service.registerCounters();
    lenient()
        .when(
            blueprintAliasRepository.findBySourceSystemAndExternalNameIgnoreCase(
                any(BlueprintExternalAliasSource.class), any(String.class)))
        .thenReturn(Optional.empty());
  }

  @Test
  void theOwnKeyWinsOverAConflictingName() {
    products("arclight pistol", "Arclight Pistol", "gallant rifle", "Gallant Rifle");

    ExchangeResolveResponse response =
        resolve(ExchangeCatalogKind.BLUEPRINT, ref("arclight pistol", null, "Gallant Rifle"));

    assertThat(only(response).ref().bt()).isEqualTo("arclight pistol");
    verifyNoInteractions(blueprintRepository);
  }

  @Test
  void aProductKeyBeyondThePublishedLimitIsAnsweredAndAcceptedAsTheFeedsHash() {
    String longKey = "k".repeat(200);
    String hashed = ExchangeBlueprintFeedService.keyOf(longKey);
    products(longKey, "n".repeat(255));

    Result byHash = only(resolve(ExchangeCatalogKind.BLUEPRINT, ref(hashed, null, null)));
    Result byRawKey = only(resolve(ExchangeCatalogKind.BLUEPRINT, ref(longKey, null, null)));

    assertThat(byHash.status()).isEqualTo(Status.RESOLVED);
    assertThat(byHash.ref().bt()).isEqualTo(hashed);
    assertThat(byHash.ref().name()).hasSize(200);
    assertThat(byRawKey.status()).isEqualTo(Status.UNMATCHED);
  }

  @Test
  void anAmbiguousRecordFallsThroughToAResolvingName() {
    products("arclight pistol", "Arclight Pistol", "gallant rifle", "Gallant Rifle");
    when(blueprintProductService.scwikiKeyToProductKeys())
        .thenReturn(
            Map.of("bp_shared", new LinkedHashSet<>(List.of("arclight pistol", "gallant rifle"))));

    ExchangeResolveResponse onlyRecord =
        resolve(ExchangeCatalogKind.BLUEPRINT, ref(null, "BP_SHARED", null));
    ExchangeResolveResponse withName =
        resolve(ExchangeCatalogKind.BLUEPRINT, ref(null, "BP_SHARED", "Gallant Rifle"));
    ExchangeResolveResponse withUnknownName =
        resolve(ExchangeCatalogKind.BLUEPRINT, ref(null, "BP_SHARED", "Qqqq Zzzz"));

    assertThat(only(onlyRecord).status()).isEqualTo(Status.AMBIGUOUS);
    assertThat(only(onlyRecord).candidates()).hasSize(2);
    assertThat(only(withName).ref().bt()).isEqualTo("gallant rifle");
    assertThat(only(withUnknownName).status()).isEqualTo(Status.AMBIGUOUS);
    assertThat(only(withUnknownName).candidates()).hasSize(2);
  }

  @Test
  void aSingleFuzzySuggestionStaysAmbiguous() {
    products("arclight pistol", "Arclight Pistol");

    Result result = only(resolve(ExchangeCatalogKind.BLUEPRINT, ref(null, null, "Arclite Pistol")));

    assertThat(result.status()).isEqualTo(Status.AMBIGUOUS);
    assertThat(result.ref()).isNull();
    assertThat(result.candidates()).extracting("bt").containsExactly("arclight pistol");
  }

  @Test
  void aNameKeyWarnsOnlyWhenTheKeysDidNotResolve() {
    products("arclight pistol", "Arclight Pistol");

    ExchangeResolveResponse response =
        resolve(
            ExchangeCatalogKind.BLUEPRINT,
            new ExchangeItemRef("arclight pistol", null, null, null, "item_a", null, null),
            new ExchangeItemRef(null, null, null, null, "item_b", "Arclight Pistol", "en"));

    assertThat(response.results()).allMatch(r -> r.status() == Status.RESOLVED);
    assertThat(response.warnings())
        .singleElement()
        .satisfies(
            w -> {
              assertThat(w.pointer()).isEqualTo("/refs/1/locKey");
              assertThat(w.code()).isEqualTo("LOC_KEY_UNRESOLVED");
            });
  }

  @Test
  void aNameKeyResolvesCaseInsensitivelyAndSilencesTheWarning() {
    ShipType cutlass = new ShipType();
    cutlass.setId(UUID.randomUUID());
    cutlass.setName("Cutlass Black");
    cutlass.setNameKey("vehicle_NameDRAK_Cutlass_Black");
    when(shipTypeRepository.findAll()).thenReturn(List.of(cutlass));
    Material agricium = material("Agricium", true);
    agricium.setNameKey("items_commodities_agricium");
    when(materialRepository.findAll()).thenReturn(List.of(agricium));

    ExchangeResolveResponse ship =
        resolve(
            ExchangeCatalogKind.SHIP_TYPE,
            new ExchangeItemRef(
                null, null, null, null, "VEHICLE_NameDRAK_Cutlass_Black", "Something Else", null));
    ExchangeResolveResponse unknown =
        resolve(
            ExchangeCatalogKind.MATERIAL,
            new ExchangeItemRef(
                null, null, null, null, "items_commodities_nope", "Agricium", null));
    ExchangeResolveResponse known =
        resolve(
            ExchangeCatalogKind.MATERIAL,
            new ExchangeItemRef(null, null, null, null, "items_commodities_agricium", null, null));

    assertThat(only(ship).ref().name()).isEqualTo("Cutlass Black");
    assertThat(ship.warnings()).isNull();
    assertThat(only(unknown).ref().name()).isEqualTo("Agricium");
    assertThat(unknown.warnings()).hasSize(1);
    assertThat(only(known).ref().name()).isEqualTo("Agricium");
    assertThat(known.warnings()).isNull();
  }

  @Test
  void warningsAreCappedAtFifty() {
    products("arclight pistol", "Arclight Pistol");
    List<ExchangeItemRef> refs = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      refs.add(new ExchangeItemRef(null, null, null, null, "item_" + i, null, null));
    }

    ExchangeResolveResponse response =
        service.resolve(new ExchangeResolveRequest(ExchangeCatalogKind.BLUEPRINT, refs));

    assertThat(response.results()).hasSize(60);
    assertThat(response.warnings()).hasSize(ExchangeResolveResponse.MAX_WARNINGS);
  }

  @Test
  void itemsWithOneNameListAtMostTenCandidates() {
    List<ExchangeItemKeyRow> twins = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      twins.add(new ExchangeItemKeyRow(UUID.randomUUID(), "Twin", null, null, null, null, null));
    }
    when(gameItemRepository.findExchangeKeyRows(any(), any(), any(), any(), any(), any()))
        .thenReturn(twins);

    Result result = only(resolve(ExchangeCatalogKind.ITEM, ref(null, null, "twin")));

    assertThat(result.status()).isEqualTo(Status.AMBIGUOUS);
    assertThat(result.candidates()).hasSize(ExchangeResolveResponse.MAX_CANDIDATES);
  }

  @Test
  void itemsQueryNothingWhenNoReferenceCarriesAUsableKey() {
    Result result = only(resolve(ExchangeCatalogKind.ITEM, ref("not-a-uuid", null, null)));

    assertThat(result.status()).isEqualTo(Status.UNMATCHED);
    verifyNoInteractions(gameItemRepository);
  }

  @Test
  void materialNamesGoExactThenCanonicalThenAliasThenFuzzy() {
    Material silicon = material("Silicon", true);
    Material rawSilicon = material("Silicon (Raw)", true);
    Material agricium = material("Agricium (Ore)", true);
    Material hidden = material("Hidden Ore", false);
    when(materialRepository.findAll()).thenReturn(List.of(silicon, rawSilicon, agricium, hidden));
    when(materialAliasRepository.findAllByOrderByExternalNameAsc())
        .thenReturn(List.of(alias("Sili", silicon), alias("Secret", hidden)));

    ExchangeResolveResponse response =
        resolve(
            ExchangeCatalogKind.MATERIAL,
            ref(null, null, "silicon"),
            ref(null, null, "Raw Agricium"),
            ref(null, null, "SILI"),
            ref(null, null, "Secret"),
            ref(null, null, "Agricum Ore"));

    assertThat(response.results().get(0).ref().name()).isEqualTo("Silicon");
    assertThat(response.results().get(1).ref().name()).isEqualTo("Agricium (Ore)");
    assertThat(response.results().get(2).ref().name()).isEqualTo("Silicon");
    assertThat(response.results().get(3).ref()).isNull();
    assertThat(response.results().get(4).status()).isEqualTo(Status.AMBIGUOUS);
    assertThat(response.results().get(4).candidates())
        .extracting("name")
        .contains("Agricium (Ore)")
        .doesNotContain("Hidden Ore");
  }

  @Test
  void shipTypesResolveThroughTheHangarMatcher() {
    ShipType cutlass = new ShipType();
    cutlass.setId(UUID.randomUUID());
    cutlass.setName("Cutlass Black");
    cutlass.setClassName("DRAK_Cutlass_Black");
    when(shipTypeRepository.findAll()).thenReturn(List.of(cutlass));

    ExchangeResolveResponse response =
        resolve(
            ExchangeCatalogKind.SHIP_TYPE,
            ref(null, "drak_cutlass_black", null),
            ref(null, null, "cutlass black"),
            ref(cutlass.getId().toString(), null, null));

    assertThat(response.results())
        .allSatisfy(r -> assertThat(r.ref().bt()).isEqualTo(cutlass.getId().toString()));
  }

  @Test
  void everyReferenceIsCountedByKindAndStatus() {
    products("arclight pistol", "Arclight Pistol");

    resolve(
        ExchangeCatalogKind.BLUEPRINT,
        ref("arclight pistol", null, null),
        ref(null, null, "Qqqq Zzzz Wwww"));

    assertThat(count("blueprint", "resolved")).isEqualTo(1.0);
    assertThat(count("blueprint", "unmatched")).isEqualTo(1.0);
    assertThat(count("ship_type", "ambiguous")).isZero();
  }

  /**
   * Stubs the master products from alternating keys and names; a trailing key without a name is
   * ignored.
   *
   * @param keysAndNames key, name, key, name, …
   */
  private void products(@NotNull String... keysAndNames) {
    List<ResolvedProduct> products = new ArrayList<>();
    for (int i = 0; i + 1 < keysAndNames.length; i += 2) {
      products.add(new ResolvedProduct(keysAndNames[i], keysAndNames[i + 1], null));
    }
    lenient().when(blueprintProductService.allProducts()).thenReturn(products);
  }

  /**
   * Resolves references of one kind.
   *
   * @param kind the kind
   * @param refs the references
   * @return the response
   */
  private @NotNull ExchangeResolveResponse resolve(
      @NotNull ExchangeCatalogKind kind, @NotNull ExchangeItemRef... refs) {
    return service.resolve(new ExchangeResolveRequest(kind, List.of(refs)));
  }

  /**
   * Builds a reference from its own key, record name and name.
   *
   * @param bt the own key, or {@code null}
   * @param scRecord the record name, or {@code null}
   * @param name the name, or {@code null}
   * @return the reference
   */
  private static @NotNull ExchangeItemRef ref(
      @Nullable String bt, @Nullable String scRecord, @Nullable String name) {
    return new ExchangeItemRef(bt, scRecord, null, null, null, name, null);
  }

  /**
   * Returns the single result of a response.
   *
   * @param response the response
   * @return the result
   */
  private static @NotNull Result only(@NotNull ExchangeResolveResponse response) {
    assertThat(response.results()).hasSize(1);
    return response.results().getFirst();
  }

  /**
   * Builds a raw material with an id.
   *
   * @param name the name
   * @param visible whether it is visible
   * @return the material
   */
  private static @NotNull Material material(@NotNull String name, boolean visible) {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setName(name);
    material.setType(MaterialType.RAW);
    material.setIsVisible(visible);
    return material;
  }

  /**
   * Builds an external alias.
   *
   * @param externalName the external name
   * @param target the material it names
   * @return the alias
   */
  private static @NotNull MaterialExternalAlias alias(
      @NotNull String externalName, @NotNull Material target) {
    MaterialExternalAlias alias = new MaterialExternalAlias();
    alias.setExternalName(externalName);
    alias.setMaterial(target);
    return alias;
  }

  /**
   * Reads the resolution counter.
   *
   * @param kind the kind tag
   * @param status the status tag
   * @return the count
   */
  private double count(@NotNull String kind, @NotNull String status) {
    return meterRegistry
        .get(MetricNames.EXCHANGE_RESOLVE_REFS)
        .tag(MetricNames.TAG_KIND, kind)
        .tag(MetricNames.TAG_STATUS, status)
        .counter()
        .count();
  }
}
