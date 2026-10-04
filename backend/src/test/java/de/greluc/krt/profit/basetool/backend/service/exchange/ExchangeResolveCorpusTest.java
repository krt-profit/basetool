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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.FuzzyNameMatcher;
import de.greluc.krt.profit.basetool.backend.model.BlueprintExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.BlueprintExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportStatus;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveResponse.Entry;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveResponse.Result;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveResponse.Status;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

/** REQ-XCH-012: the anonymised corpus resolves the same through the exchange and the web import. */
@ExtendWith(MockitoExtension.class)
class ExchangeResolveCorpusTest {

  private static final String FIXTURE = "/fixtures/blueprint-corpus/game-log-corpus-v1.json";
  private static final UUID OWNER = UUID.fromString("0e000001-0000-4000-8000-0000000000c1");

  @Mock private BlueprintProductService blueprintProductService;
  @Mock private BlueprintExternalAliasRepository blueprintAliasRepository;
  @Mock private PersonalBlueprintRepository personalBlueprintRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private AuditService auditService;
  @Mock private BlueprintRepository blueprintRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private MaterialExternalAliasRepository materialAliasRepository;
  @Mock private ShipTypeRepository shipTypeRepository;

  @Test
  void theCorpusResolvesTheSameThroughTheExchangeAndTheWebImport() throws IOException {
    when(blueprintProductService.allProducts())
        .thenReturn(
            List.of(
                product("monde legs delta camo", "Monde Legs Delta Camo"),
                product("monde arms hemlock camo", "Monde Arms Hemlock Camo"),
                product("r97 shotgun", "R97 Shotgun"),
                product("strata arms levski edition", "Strata Arms Levski Edition"),
                product("strata helmet levski edition", "Strata Helmet Levski Edition"),
                product("pitman mining laser", "Pitman Mining Laser"),
                product(
                    "cf-337 panther \"hazard-zone\" repeater",
                    "CF-337 Panther \"Hazard-Zone\" Repeater"),
                product("cq7 rifle", "CQ7 Rifle"),
                product("lynx arms (core)", "Lynx Arms (Core)"),
                product("oracle helmet", "Oracle Helmet"),
                product("bul-h4 helmet", "BUL-H4 Helmet"),
                product("bul-h4 armor", "BUL-H4 Armor")));
    BlueprintExternalAlias lynx = new BlueprintExternalAlias();
    lynx.setSourceSystem(BlueprintExternalAliasSource.SCMDB);
    lynx.setExternalName("Lynx Arms");
    lynx.setProductKey("lynx arms (core)");
    lynx.setProductName("Lynx Arms (Core)");
    lenient()
        .when(
            blueprintAliasRepository.findBySourceSystemAndExternalNameIgnoreCase(
                any(BlueprintExternalAliasSource.class), any(String.class)))
        .thenReturn(Optional.empty());
    lenient()
        .when(
            blueprintAliasRepository.findBySourceSystemAndExternalNameIgnoreCase(
                eq(BlueprintExternalAliasSource.SCMDB), eq("Lynx Arms")))
        .thenReturn(Optional.of(lynx));

    JsonMapper mapper = JsonMapper.builder().build();
    BlueprintImportService importService =
        new BlueprintImportService(
            mapper,
            blueprintProductService,
            new BlueprintNameNormalizer(),
            new FuzzyNameMatcher(),
            blueprintAliasRepository,
            personalBlueprintRepository,
            gameItemRepository,
            auditService);
    ExchangeResolveService exchange =
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
            new SimpleMeterRegistry());

    BlueprintImportPreviewDto preview = importService.previewImport(OWNER, fixture());
    List<BlueprintImportEntryDto> entries = preview.entries();
    ExchangeResolveResponse response =
        exchange.resolve(
            new ExchangeResolveRequest(
                ExchangeCatalogKind.BLUEPRINT,
                entries.stream()
                    .map(
                        e ->
                            new ExchangeItemRef(
                                null, null, null, null, null, e.externalName(), null))
                    .toList()));

    assertThat(entries).hasSize(31);
    assertThat(response.results()).hasSize(entries.size());
    for (int i = 0; i < entries.size(); i++) {
      assertThat(response.results().get(i))
          .as(entries.get(i).externalName())
          .isEqualTo(expected(i, entries.get(i)));
    }
    assertThat(entries.stream().map(BlueprintImportEntryDto::status).distinct())
        .as("the corpus exercises every status of the chain")
        .containsExactlyInAnyOrderElementsOf(
            EnumSet.complementOf(EnumSet.of(BlueprintImportStatus.ALREADY_OWNED)));
  }

  /**
   * Translates a web-import row into the exchange result it must equal.
   *
   * @param index the row's position
   * @param entry the web-import row
   * @return the expected exchange result
   */
  private static @NotNull Result expected(int index, @NotNull BlueprintImportEntryDto entry) {
    return switch (entry.status()) {
      case MATCHED, MATCHED_BY_ALIAS, ALREADY_OWNED ->
          new Result(
              index, Status.RESOLVED, new Entry(entry.productKey(), entry.productName()), null);
      case SUGGESTED ->
          new Result(
              index,
              Status.AMBIGUOUS,
              null,
              entry.suggestions().stream()
                  .map(s -> new Entry(s.productKey(), s.productName()))
                  .toList());
      case UNMATCHED -> new Result(index, Status.UNMATCHED, null, null);
    };
  }

  /**
   * Builds a master product without an output item.
   *
   * @param key the product key
   * @param name the display name
   * @return the product
   */
  private static @NotNull ResolvedProduct product(@NotNull String key, @NotNull String name) {
    return new ResolvedProduct(key, name, null);
  }

  /**
   * Loads the corpus as an upload.
   *
   * @return the upload
   * @throws IOException if the fixture cannot be read
   */
  private @NotNull MockMultipartFile fixture() throws IOException {
    try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
      assertThat(in).isNotNull();
      return new MockMultipartFile("file", "game-log-corpus-v1.json", "application/json", in);
    }
  }
}
