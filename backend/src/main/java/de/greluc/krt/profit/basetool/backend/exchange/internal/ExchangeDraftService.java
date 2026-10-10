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

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBlueprintDraftDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryDraftRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractGoodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractImageDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.backend.service.BlueprintExportParser;
import de.greluc.krt.profit.basetool.backend.service.BlueprintImportService;
import de.greluc.krt.profit.basetool.backend.service.RefineryImportService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the drafts an exchange client stages for review in the browser: the same blueprint import
 * preview and refinery draft the SC Extractor's upload produces, never persisted (REQ-XCH-019).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExchangeDraftService {

  private final ExchangeResolveService resolveService;
  private final BlueprintImportService blueprintImportService;
  private final RefineryImportService refineryImportService;

  /**
   * Previews a blueprint draft for the member: each reference resolved through {@code
   * catalog/resolve} is previewed under its product's name, any other under the name the client
   * sent, so the member reviews it as an upload's unmatched row.
   *
   * @param member the member
   * @param draft the draft
   * @return the preview, one row per blueprint, the earliest acquisition kept for a repeated one
   */
  public @NotNull BlueprintImportPreviewDto blueprints(
      @NotNull UUID member, @NotNull ExchangeBlueprintDraftDto draft) {
    List<ExchangeBlueprintDraftDto.Item> items = draft.items();
    Map<String, Instant> earliest = new LinkedHashMap<>();
    for (int from = 0; from < items.size(); from += ExchangeResolveRequest.MAX_REFS) {
      List<ExchangeBlueprintDraftDto.Item> chunk =
          items.subList(from, Math.min(items.size(), from + ExchangeResolveRequest.MAX_REFS));
      List<ExchangeItemRef> refs = chunk.stream().map(ExchangeBlueprintDraftDto.Item::ref).toList();
      ExchangeResolveResponse response =
          resolveService.resolve(new ExchangeResolveRequest(ExchangeCatalogKind.BLUEPRINT, refs));
      for (ExchangeResolveResponse.Result result : response.results()) {
        ExchangeBlueprintDraftDto.Item item = chunk.get(result.index());
        String name = nameOf(item.ref(), result);
        if (name == null) {
          continue;
        }
        Instant acquiredAt = item.acquiredAt();
        earliest.merge(
            name, acquiredAt == null ? Instant.MAX : acquiredAt, (a, b) -> a.isBefore(b) ? a : b);
      }
    }
    List<BlueprintExportParser.ParsedEntry> entries = new ArrayList<>(earliest.size());
    earliest.forEach(
        (name, at) ->
            entries.add(
                new BlueprintExportParser.ParsedEntry(
                    name, null, Instant.MAX.equals(at) ? null : at)));
    return blueprintImportService.previewEntries(member, entries);
  }

  /**
   * Builds the refinery draft for the member, as the extractor's upload does.
   *
   * @param member the member, the draft's owner
   * @param draft the refinery extract the client sent
   * @return the draft with its issues
   */
  public @NotNull RefineryImportDraftDto refinery(
      @NotNull UUID member, @NotNull ExchangeRefineryDraftRequest draft) {
    return refineryImportService.buildDraft(extract(draft), member);
  }

  /**
   * Copies the exchange's refinery draft into the web import's extract, field by field.
   *
   * @param draft the refinery extract the client sent
   * @return the same extract as the web import reads it
   */
  static @NotNull RefineryExtractDto extract(@NotNull ExchangeRefineryDraftRequest draft) {
    return new RefineryExtractDto(
        draft.schemaVersion(),
        draft.tool(),
        draft.toolVersion(),
        draft.model(),
        draft.generatedAt(),
        draft.clientLanguage(),
        draft.orders() == null
            ? null
            : draft.orders().stream().map(ExchangeDraftService::order).toList());
  }

  /**
   * Copies one order of a refinery draft.
   *
   * @param order the order the client sent
   * @return the same order as the web import reads it
   */
  private static @Nullable RefineryExtractOrderDto order(
      @Nullable ExchangeRefineryDraftRequest.ExchangeRefineryDraftOrder order) {
    if (order == null) {
      return null;
    }
    return new RefineryExtractOrderDto(
        order.panelType(),
        order.quoted(),
        order.layoutConfidence(),
        order.rawLocationName(),
        order.rawMethodName(),
        order.rawInManifestTotal(),
        order.rawToRefineTotal(),
        order.expenses(),
        order.durationMinutes(),
        order.totalYieldScu(),
        order.sourceImages() == null
            ? null
            : order.sourceImages().stream().map(ExchangeDraftService::image).toList(),
        order.goods() == null
            ? null
            : order.goods().stream().map(ExchangeDraftService::good).toList());
  }

  /**
   * Copies one source screenshot of a refinery draft.
   *
   * @param image the screenshot the client named
   * @return the same screenshot as the web import reads it
   */
  private static @Nullable RefineryExtractImageDto image(
      @Nullable ExchangeRefineryDraftRequest.ExchangeRefineryDraftImage image) {
    if (image == null) {
      return null;
    }
    return new RefineryExtractImageDto(
        image.name(), image.width(), image.height(), image.cropMode(), image.capturedAt());
  }

  /**
   * Copies one material row of a refinery draft.
   *
   * @param good the row the client sent
   * @return the same row as the web import reads it
   */
  private static @Nullable RefineryExtractGoodDto good(
      @Nullable ExchangeRefineryDraftRequest.ExchangeRefineryDraftGood good) {
    if (good == null) {
      return null;
    }
    return new RefineryExtractGoodDto(
        good.rowIndex(),
        good.rawMaterialName(),
        good.quality(),
        good.inputQuantity(),
        good.outputQuantity(),
        good.refine(),
        good.confidence(),
        good.sourceImage());
  }

  /**
   * Names one blueprint for the preview.
   *
   * @param ref the reference
   * @param result how it resolved
   * @return the product's name when it resolved, else the sent name, else the first key, or {@code
   *     null} for a reference without any
   */
  private static @Nullable String nameOf(
      @NotNull ExchangeItemRef ref, @NotNull ExchangeResolveResponse.Result result) {
    if (result.status() == ExchangeResolveResponse.Status.RESOLVED
        && result.ref() != null
        && result.ref().name() != null) {
      return result.ref().name();
    }
    if (ref.name() != null && !ref.name().isBlank()) {
      return ref.name().trim();
    }
    if (ref.bt() != null) {
      return ref.bt();
    }
    if (ref.scRecord() != null) {
      return ref.scRecord();
    }
    if (ref.locKey() != null) {
      return ref.locKey();
    }
    if (ref.scGuid() != null) {
      return ref.scGuid().toString();
    }
    return ref.uexId() == null ? null : String.valueOf(ref.uexId());
  }
}
