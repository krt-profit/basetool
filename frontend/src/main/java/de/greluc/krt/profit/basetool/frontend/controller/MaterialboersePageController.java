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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintProductDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialExchangeCountsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialExchangeReleasableItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Spring MVC controller for the Materialbörse page ({@code /materialboerse}, Flotte &amp; Logistik,
 * REQ-MARKET-001…). Renders the master-detail board (lean list left, full offer right) server-side
 * and proxies the release / remark / deactivate / interest writes to the backend, relaying any
 * RFC-7807 failure as its original status + a slim {@code {code, detail}} body so the page JS can
 * toast the localised message and recognise {@code OPTIMISTIC_LOCK}.
 *
 * <p>The board renders through {@code krtFetch} fragment swaps: a tab / filter / sort change
 * re-swaps the whole {@code board} region, a master-row select re-swaps only the {@code detail}
 * pane (REQ-FE-005/013). The whole surface is gated on {@code KRT_MEMBER} (decision D2).
 */
@Controller
@RequestMapping("/materialboerse")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.KRT_MEMBER + "')")
public class MaterialboersePageController {

  /** Captured generic type for the paged board response. */
  private static final ParameterizedTypeReference<PageResponse<MaterialExchangeOfferDto>>
      OFFERS_PAGE = new ParameterizedTypeReference<>() {};

  /** Captured generic type for the releasable-items picker response. */
  private static final ParameterizedTypeReference<List<MaterialExchangeReleasableItemDto>>
      RELEASABLE_LIST = new ParameterizedTypeReference<>() {};

  /** Captured generic type for the offerable blueprint-products picker response. */
  private static final ParameterizedTypeReference<List<BlueprintProductDto>> PRODUCT_LIST =
      new ParameterizedTypeReference<>() {};

  /** The board request size — large enough to show the whole board in one scrollable list. */
  private static final int BOARD_SIZE = 200;

  /** Cap on the number of blueprint-products the item-offer type-ahead requests. */
  private static final int PRODUCT_PICKER_LIMIT = 25;

  private final BackendApiClient backendApiClient;

  /**
   * Renders the Materialbörse page, or just its {@code board} region / {@code detail} pane for an
   * in-place swap.
   *
   * @param tab {@code "mein"} for "Meine Angebote", else "Alle Angebote".
   * @param q the search fragment (material or player), or {@code null}.
   * @param minQuality the minimum quality filter 0–1000, or {@code null}.
   * @param minAmount the minimum quantity filter in SCU, or {@code null}.
   * @param sort the sort key ({@code qual} / {@code menge} / {@code mat} / {@code neu}).
   * @param selected the offer id to show in the detail pane, or {@code null} for the first.
   * @param fragment {@code "board"} or {@code "detail"} for an AJAX swap, else the full page.
   * @param model the Thymeleaf model.
   * @return the view name or fragment selector.
   */
  @GetMapping
  public String board(
      @RequestParam(required = false) String tab,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) Double minAmount,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String selected,
      @RequestParam(required = false) String fragment,
      Model model) {
    if ("detail".equals(fragment)) {
      model.addAttribute("selectedOffer", loadDetail(selected));
      return "materialboerse :: detail";
    }

    String activeTab = "mein".equals(tab) ? "mein" : "alle";
    List<MaterialExchangeOfferDto> offers = loadOffers(activeTab, q, minQuality, minAmount, sort);
    MaterialExchangeOfferDto selectedOffer = loadDetail(pickSelectedId(offers, selected));
    MaterialExchangeCountsDto counts = loadCounts();

    model.addAttribute("offers", offers);
    model.addAttribute("selectedOffer", selectedOffer);
    model.addAttribute("countAll", counts.all());
    model.addAttribute("countMine", counts.mine());
    model.addAttribute("activeTab", activeTab);
    model.addAttribute("filterQ", q);
    model.addAttribute("filterMinQuality", minQuality);
    model.addAttribute("filterMinAmount", minAmount);
    model.addAttribute("filterSort", sort == null ? "qual" : sort);

    if ("board".equals(fragment)) {
      return "materialboerse :: board";
    }
    if ("list".equals(fragment)) {
      return "materialboerse :: list";
    }
    return "materialboerse";
  }

  /**
   * Releases one of the caller's own Lager rows to the board ("Material anbieten" / the Lager
   * checkbox).
   *
   * @param body the {@code {inventoryItemId, remark}} payload.
   * @return the backend result, or its error status + body.
   */
  @PostMapping("/offers/ajax")
  @ResponseBody
  public ResponseEntity<Object> release(@RequestBody Map<String, Object> body) {
    return proxy(
        "Release Materialbörse offer failed",
        () -> backendApiClient.post("/api/v1/material-exchange/offers", body, Object.class));
  }

  /**
   * Lists a craftable item on the board ("Item anbieten", #1185).
   *
   * @param body the {@code {productKey, quantity, remark}} payload.
   * @return the backend result, or its error status + body.
   */
  @PostMapping("/item-offers/ajax")
  @ResponseBody
  public ResponseEntity<Object> releaseItem(@RequestBody Map<String, Object> body) {
    return proxy(
        "List Materialbörse item offer failed",
        () -> backendApiClient.post("/api/v1/material-exchange/item-offers", body, Object.class));
  }

  /**
   * Returns craftable items (blueprint products) matching a name fragment, for the "Item anbieten"
   * type-ahead — a member-gated proxy over the backend blueprint-product search (only items an
   * active blueprint produces, #1185).
   *
   * @param q a product-name fragment, or {@code null} for the first products.
   * @return the matching products, or the backend error status + body.
   */
  @GetMapping("/offerable-products")
  @ResponseBody
  public ResponseEntity<Object> offerableProducts(@RequestParam(required = false) String q) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/blueprints/products/search")
            .queryParam("limit", PRODUCT_PICKER_LIMIT);
    appendIfPresent(uri, "q", q);
    return proxy(
        "Load Materialbörse offerable products failed",
        () -> backendApiClient.get(uri.toUriString(), PRODUCT_LIST));
  }

  /**
   * Edits an offer's offered quantity and trade remark ("Angebot bearbeiten").
   *
   * @param id the offer id.
   * @param body the {@code {offeredAmount, remark, version}} payload.
   * @return the backend result, or its error status + body.
   */
  @PutMapping("/offers/{id}/remark/ajax")
  @ResponseBody
  public ResponseEntity<Object> updateOffer(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return proxy(
        "Update Materialbörse offer failed",
        () ->
            backendApiClient.put(
                "/api/v1/material-exchange/offers/" + id + "/remark", body, Object.class));
  }

  /**
   * Deactivates an offer by id ("Angebot deaktivieren").
   *
   * @param id the offer id.
   * @return the backend result, or its error status + body.
   */
  @PostMapping("/offers/{id}/deactivate/ajax")
  @ResponseBody
  public ResponseEntity<Object> deactivate(@PathVariable @NotNull UUID id) {
    return proxy(
        "Deactivate Materialbörse offer failed",
        () ->
            backendApiClient.post(
                "/api/v1/material-exchange/offers/" + id + "/deactivate", null, Object.class));
  }

  /**
   * Deactivates the active offer for a Lager row (un-checking "Für Börse freigeben" on the Lager
   * leaf).
   *
   * @param inventoryItemId the Lager row.
   * @return the backend result, or its error status + body.
   */
  @PostMapping("/items/{inventoryItemId}/deactivate/ajax")
  @ResponseBody
  public ResponseEntity<Object> deactivateForItem(@PathVariable @NotNull UUID inventoryItemId) {
    return proxy(
        "Deactivate Materialbörse offer for item failed",
        () ->
            backendApiClient.post(
                "/api/v1/material-exchange/items/" + inventoryItemId + "/deactivate",
                null,
                Object.class));
  }

  /**
   * Registers the caller's interest in an offer ("Interesse anmelden").
   *
   * @param id the offer id.
   * @return the backend result, or its error status + body.
   */
  @PostMapping("/offers/{id}/interest/ajax")
  @ResponseBody
  public ResponseEntity<Object> registerInterest(@PathVariable @NotNull UUID id) {
    return proxy(
        "Register Materialbörse interest failed",
        () ->
            backendApiClient.post(
                "/api/v1/material-exchange/offers/" + id + "/interest", null, Object.class));
  }

  /**
   * Withdraws the caller's interest from an offer ("Interesse zurückziehen").
   *
   * @param id the offer id.
   * @return the backend result, or its error status + body.
   */
  @DeleteMapping("/offers/{id}/interest/ajax")
  @ResponseBody
  public ResponseEntity<Object> withdrawInterest(@PathVariable @NotNull UUID id) {
    return proxy(
        "Withdraw Materialbörse interest failed",
        () ->
            backendApiClient.delete(
                "/api/v1/material-exchange/offers/" + id + "/interest", Object.class));
  }

  /**
   * Returns the caller's own Lager rows eligible for release, for the "Material anbieten" picker.
   *
   * @param q a material-name fragment, or {@code null}.
   * @return the picker rows, or the backend error status + body.
   */
  @GetMapping("/releasable-items")
  @ResponseBody
  public ResponseEntity<Object> releasableItems(@RequestParam(required = false) String q) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/material-exchange/releasable-items");
    appendIfPresent(uri, "q", q);
    return proxy(
        "Load Materialbörse releasable items failed",
        () -> backendApiClient.get(uri.toUriString(), RELEASABLE_LIST));
  }

  /**
   * Loads the board offers for a tab with the toolbar filters applied.
   *
   * @param tab the active tab ({@code alle} / {@code mein}).
   * @param q the search fragment, or {@code null}.
   * @param minQuality the minimum quality, or {@code null}.
   * @param minAmount the minimum amount, or {@code null}.
   * @param sort the sort key, or {@code null}.
   * @return the offers, never {@code null} (empty on a backend error).
   */
  private List<MaterialExchangeOfferDto> loadOffers(
      String tab, String q, Integer minQuality, Double minAmount, String sort) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/material-exchange/offers")
            .queryParam("tab", tab)
            .queryParam("size", BOARD_SIZE);
    appendIfPresent(uri, "q", q);
    appendIfPresent(uri, "minQuality", minQuality);
    appendIfPresent(uri, "minAmount", minAmount);
    appendIfPresent(uri, "sort", sort);
    try {
      PageResponse<MaterialExchangeOfferDto> page =
          backendApiClient.get(uri.toUriString(), OFFERS_PAGE);
      return page == null ? List.of() : page.content();
    } catch (Exception e) {
      log.error("Failed to load Materialbörse board", e);
      return List.of();
    }
  }

  /**
   * Loads one offer's detail (interessenten names included only when the caller is the owner).
   *
   * @param id the offer id as a string, or {@code null}.
   * @return the offer detail, or {@code null} if absent/unparseable.
   */
  private MaterialExchangeOfferDto loadDetail(String id) {
    UUID offerId = parseUuid(id);
    if (offerId == null) {
      return null;
    }
    try {
      return backendApiClient.get(
          "/api/v1/material-exchange/offers/" + offerId, MaterialExchangeOfferDto.class);
    } catch (Exception e) {
      log.error("Failed to load Materialbörse offer {}", offerId, e);
      return null;
    }
  }

  /**
   * Loads the board tab counts, defaulting to zero on a backend error.
   *
   * @return the counts.
   */
  private MaterialExchangeCountsDto loadCounts() {
    try {
      return backendApiClient.get(
          "/api/v1/material-exchange/counts", MaterialExchangeCountsDto.class);
    } catch (Exception e) {
      log.error("Failed to load Materialbörse counts", e);
      return new MaterialExchangeCountsDto(0, 0);
    }
  }

  /**
   * Chooses the offer id to show in the detail pane — the requested one if it is present in the
   * list, otherwise the first offer.
   *
   * @param offers the board list.
   * @param requested the requested offer id as a string, or {@code null}.
   * @return the selected offer id as a string, or {@code null} if the list is empty.
   */
  private static String pickSelectedId(List<MaterialExchangeOfferDto> offers, String requested) {
    UUID requestedId = parseUuid(requested);
    if (requestedId != null && offers.stream().anyMatch(offer -> requestedId.equals(offer.id()))) {
      return requested;
    }
    return offers.isEmpty() ? null : offers.get(0).id().toString();
  }

  /**
   * Appends a query parameter when its value is present (and, for a string, non-blank).
   *
   * @param uri the builder.
   * @param name the parameter name.
   * @param value the value, or {@code null}.
   */
  private static void appendIfPresent(UriComponentsBuilder uri, String name, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof String s && s.isBlank()) {
      return;
    }
    uri.queryParam(name, value);
  }

  /**
   * Parses a UUID string leniently.
   *
   * @param value the string, or {@code null}.
   * @return the UUID, or {@code null} if absent/unparseable.
   */
  private static UUID parseUuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  /**
   * Runs a backend call, returning its result as 200 and relaying any backend RFC-7807 failure as
   * its original status + {@code {code, detail}} body (or a 500 for an unexpected error).
   *
   * @param logMessage the log prefix for a failure.
   * @param call the backend call.
   * @return the proxied response.
   */
  private ResponseEntity<Object> proxy(String logMessage, BackendCall call) {
    try {
      Object result = call.run();
      return ResponseEntity.ok(result == null ? Map.of() : result);
    } catch (BackendServiceException e) {
      log.warn("{}: status={}, code={}", logMessage, e.getStatusCode(), e.getProblemCode());
      Map<String, Object> payload = new HashMap<>();
      payload.put("code", e.getProblemCode());
      payload.put("detail", e.getProblemDetail());
      int status = e.getStatusCode() > 0 ? e.getStatusCode() : 500;
      return ResponseEntity.status(status).body(payload);
    } catch (Exception e) {
      log.error(logMessage, e);
      Map<String, Object> payload = new HashMap<>();
      payload.put("code", "INTERNAL_ERROR");
      return ResponseEntity.status(500).body(payload);
    }
  }

  /** A backend call that may throw a {@link BackendServiceException}. */
  @FunctionalInterface
  private interface BackendCall {
    /**
     * Runs the backend call.
     *
     * @return the backend result, possibly {@code null}.
     */
    Object run();
  }
}
