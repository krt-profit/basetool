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
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

  /** Captured generic type for the paged Gesuche (requests) board response. */
  private static final ParameterizedTypeReference<PageResponse<MaterialRequestDto>> REQUESTS_PAGE =
      new ParameterizedTypeReference<>() {};

  /** Captured generic type for a raw, forwarded backend payload (the material-catalog picker). */
  private static final ParameterizedTypeReference<Object> RAW =
      new ParameterizedTypeReference<>() {};

  /** Captured generic type for the releasable-items picker response. */
  private static final ParameterizedTypeReference<List<MaterialExchangeReleasableItemDto>>
      RELEASABLE_LIST = new ParameterizedTypeReference<>() {};

  /** Captured generic type for the offerable blueprint-products picker response. */
  private static final ParameterizedTypeReference<List<BlueprintProductDto>> PRODUCT_LIST =
      new ParameterizedTypeReference<>() {};

  /** The board request size — large enough to show the whole board in one scrollable list. */
  private static final int BOARD_SIZE = 200;

  /** The sort key the board falls back to, and the one its {@code <select>} pre-selects. */
  private static final String DEFAULT_SORT = "qual";

  /**
   * The sort keys the board's {@code <select>} offers, and therefore the only ones relayed to the
   * backend. Mirrors the option list in {@code materialboerse.html} and {@code
   * fragments/materialgesuch-board.html}.
   */
  private static final Set<String> SORT_KEYS = Set.of(DEFAULT_SORT, "menge", "mat", "neu");

  /** Cap on the number of blueprint-products the item-offer type-ahead requests. */
  private static final int PRODUCT_PICKER_LIMIT = 25;

  /** Cap on the number of catalogue materials the material-request type-ahead requests. */
  private static final int MATERIAL_PICKER_LIMIT = 25;

  private final BackendApiClient backendApiClient;

  /**
   * Renders the Materialbörse page, or just its {@code board} region / {@code detail} pane for an
   * in-place swap. The board carries two modes — offers (Angebote) and requests (Gesuche) —
   * selected by {@code mode}; both count pairs are always loaded so the shared four-tab bar stays
   * accurate regardless of the active mode.
   *
   * @param mode {@code "requests"} for the Gesuche board, anything else for the Angebote board.
   * @param tab {@code "mein"} for "Meine …", else "Alle …".
   * @param q the search fragment (material/item or player), or {@code null}.
   * @param minQuality the minimum quality filter 0–1000, or {@code null}.
   * @param minAmount the minimum quantity filter, or {@code null}.
   * @param sort the sort key ({@code qual} / {@code menge} / {@code mat} / {@code neu}); anything
   *     else falls back to {@code qual}.
   * @param selected the offer/request id to show in the detail pane, or {@code null} for the first.
   * @param fragment {@code "board"} / {@code "list"} / {@code "detail"} for an AJAX swap, else the
   *     full page.
   * @param model the Thymeleaf model.
   * @return the view name or fragment selector.
   */
  @GetMapping
  public String board(
      @RequestParam(required = false) String mode,
      @RequestParam(required = false) String tab,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) Double minAmount,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String selected,
      @RequestParam(required = false) String fragment,
      Model model) {
    boolean requests = "requests".equals(mode);

    // A detail swap needs no counts/tab-bar; return the pane directly for the active mode.
    if ("detail".equals(fragment)) {
      if (requests) {
        model.addAttribute("selectedRequest", loadRequestDetail(selected));
        return "fragments/materialgesuch-board :: requestDetail";
      }
      model.addAttribute("selectedOffer", loadDetail(selected));
      return "materialboerse :: detail";
    }

    String activeTab = "mein".equals(tab) ? "mein" : "alle";
    // The sort key is relayed straight into the backend query, so it is narrowed to the four keys
    // the <select> actually offers rather than forwarded raw (REQ-SEC-051). An unknown key already
    // collapsed to the default in the backend's own normalizeSort; it now never leaves this page.
    String activeSort = sort != null && SORT_KEYS.contains(sort) ? sort : DEFAULT_SORT;

    // Both count pairs feed the shared four-tab bar, whichever mode is active.
    MaterialExchangeCountsDto offerCounts = loadCounts();
    MaterialExchangeCountsDto requestCounts = loadRequestCounts();
    model.addAttribute("countAll", offerCounts.all());
    model.addAttribute("countMine", offerCounts.mine());
    model.addAttribute("countReqAll", requestCounts.all());
    model.addAttribute("countReqMine", requestCounts.mine());
    model.addAttribute("activeMode", requests ? "requests" : "offers");
    model.addAttribute("activeTab", activeTab);
    model.addAttribute("filterQ", q);
    model.addAttribute("filterMinQuality", minQuality);
    model.addAttribute("filterMinAmount", minAmount);
    model.addAttribute("filterSort", activeSort);

    if (requests) {
      List<MaterialRequestDto> reqs = loadRequests(activeTab, q, minQuality, minAmount, activeSort);
      model.addAttribute("requests", reqs);
      model.addAttribute(
          "selectedRequest", loadRequestDetail(pickSelectedRequestId(reqs, selected)));
      if ("board".equals(fragment)) {
        return "fragments/materialgesuch-board :: requestBoard";
      }
      if ("list".equals(fragment)) {
        return "fragments/materialgesuch-board :: requestList";
      }
      return "materialboerse";
    }

    List<MaterialExchangeOfferDto> offers =
        loadOffers(activeTab, q, minQuality, minAmount, activeSort);
    model.addAttribute("offers", offers);
    model.addAttribute("selectedOffer", loadDetail(pickSelectedId(offers, selected)));
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
    return proxy(
        "Load Materialbörse offerable products failed",
        () -> backendGetWithQuery(uri, q, PRODUCT_LIST));
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
   * Returns the caller's own Lager rows eligible for release, for the "Material anbieten" picker,
   * optionally narrowed to one row kind by the dialog's Material/Item radio (REQ-MARKET-002).
   *
   * @param q a material-name fragment, or {@code null}.
   * @param kind {@code MATERIAL} or {@code ITEM} to restrict the picker to that kind, or {@code
   *     null}/anything else for both.
   * @return the picker rows, or the backend error status + body.
   */
  @GetMapping("/releasable-items")
  @ResponseBody
  public ResponseEntity<Object> releasableItems(
      @RequestParam(required = false) String q, @RequestParam(required = false) String kind) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/material-exchange/releasable-items");
    // Never forward the raw request value into the outbound URI: map the caller-supplied kind to a
    // fixed literal, so only one of the two known enum tokens (or nothing) reaches the backend URL.
    // The value placed on the URI is a compile-time constant, not user-controlled input — which is
    // both the correct allow-list validation and what closes the CodeQL SSRF finding on this hop.
    appendIfPresent(uri, "kind", normaliseKind(kind));
    return proxy(
        "Load Materialbörse releasable items failed",
        () -> backendGetWithQuery(uri, q, RELEASABLE_LIST));
  }

  /**
   * Maps the caller-supplied release-picker kind to one of the two known, constant enum tokens,
   * dropping anything else. Returning a compile-time literal (never the request value) is a strict
   * allow-list: an unexpected value yields {@code null} (the picker then lists both kinds) instead
   * of being reflected into the backend URI.
   *
   * @param kind the raw request value, or {@code null}.
   * @return the literal {@code "MATERIAL"} or {@code "ITEM"}, or {@code null} for anything else.
   */
  private static String normaliseKind(String kind) {
    if ("MATERIAL".equals(kind)) {
      return "MATERIAL";
    }
    if ("ITEM".equals(kind)) {
      return "ITEM";
    }
    return null;
  }

  // ============================ Gesuche (requests) proxies ============================

  /**
   * Posts a material wanted-listing to the board ("Material suchen").
   *
   * @param body the {@code {materialId, minQuality, requestedAmount, remark}} payload.
   * @return the backend result, or its error status + body.
   */
  @PostMapping("/requests/ajax")
  @ResponseBody
  public ResponseEntity<Object> createMaterialRequest(@RequestBody Map<String, Object> body) {
    return proxy(
        "Create Materialbörse request failed",
        () -> backendApiClient.post("/api/v1/material-requests", body, Object.class));
  }

  /**
   * Posts a craftable-item wanted-listing to the board ("Item suchen").
   *
   * @param body the {@code {productKey, minQuality, quantity, remark}} payload.
   * @return the backend result, or its error status + body.
   */
  @PostMapping("/item-requests/ajax")
  @ResponseBody
  public ResponseEntity<Object> createItemRequest(@RequestBody Map<String, Object> body) {
    return proxy(
        "Create Materialbörse item request failed",
        () -> backendApiClient.post("/api/v1/material-requests/item", body, Object.class));
  }

  /**
   * Edits a request's desired quantity, minimum quality and description ("Gesuch bearbeiten").
   *
   * @param id the request id.
   * @param body the {@code {desiredAmount, minQuality, remark, version}} payload.
   * @return the backend result, or its error status + body.
   */
  @PutMapping("/requests/{id}/ajax")
  @ResponseBody
  public ResponseEntity<Object> updateRequest(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return proxy(
        "Update Materialbörse request failed",
        () -> backendApiClient.put("/api/v1/material-requests/" + id, body, Object.class));
  }

  /**
   * Deactivates a request by id ("Gesuch zurückziehen").
   *
   * @param id the request id.
   * @return the backend result, or its error status + body.
   */
  @PostMapping("/requests/{id}/deactivate/ajax")
  @ResponseBody
  public ResponseEntity<Object> deactivateRequest(@PathVariable @NotNull UUID id) {
    return proxy(
        "Deactivate Materialbörse request failed",
        () ->
            backendApiClient.post(
                "/api/v1/material-requests/" + id + "/deactivate", null, Object.class));
  }

  /**
   * Signals that the caller can supply a request ("Ich kann liefern").
   *
   * @param id the request id.
   * @return the backend result, or its error status + body.
   */
  @PostMapping("/requests/{id}/interest/ajax")
  @ResponseBody
  public ResponseEntity<Object> signalFulfillment(@PathVariable @NotNull UUID id) {
    return proxy(
        "Signal Materialbörse fulfilment failed",
        () ->
            backendApiClient.post(
                "/api/v1/material-requests/" + id + "/interest", null, Object.class));
  }

  /**
   * Withdraws the caller's fulfilment signal from a request ("doch nicht liefern").
   *
   * @param id the request id.
   * @return the backend result, or its error status + body.
   */
  @DeleteMapping("/requests/{id}/interest/ajax")
  @ResponseBody
  public ResponseEntity<Object> withdrawFulfillment(@PathVariable @NotNull UUID id) {
    return proxy(
        "Withdraw Materialbörse fulfilment failed",
        () ->
            backendApiClient.delete("/api/v1/material-requests/" + id + "/interest", Object.class));
  }

  /**
   * Returns catalogue materials matching a name fragment, for the "Material suchen" type-ahead — a
   * member-gated proxy over the backend material search. Unlike the offer picker (the caller's own
   * Lager rows), a request picks from the whole material catalogue.
   *
   * @param q a material-name fragment, or {@code null} for the first materials.
   * @return the matching materials page (raw), or the backend error status + body.
   */
  @GetMapping("/request-materials")
  @ResponseBody
  public ResponseEntity<Object> requestMaterials(@RequestParam(required = false) String q) {
    String base =
        UriComponentsBuilder.fromPath("/api/v1/materials/search")
            .queryParam("size", MATERIAL_PICKER_LIMIT)
            .toUriString();
    // Pass the free-text fragment as a URI-template variable so the WebClient encodes it exactly
    // once (the #371 frontend→backend re-encoding contract); the backend param name is `search`.
    return proxy(
        "Load Materialbörse request materials failed",
        () ->
            q == null || q.isBlank()
                ? backendApiClient.get(base, RAW)
                : backendApiClient.get(base + "&search={search}", RAW, q));
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
    appendIfPresent(uri, "minQuality", minQuality);
    appendIfPresent(uri, "minAmount", minAmount);
    appendIfPresent(uri, "sort", sort);
    try {
      PageResponse<MaterialExchangeOfferDto> page = backendGetWithQuery(uri, q, OFFERS_PAGE);
      return page == null ? List.of() : page.content();
    } catch (BackendServiceException e) {
      log.debug("Failed to load Materialbörse board", e);
      return List.of();
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
    } catch (BackendServiceException e) {
      log.debug("Failed to load Materialbörse offer {}", offerId, e);
      return null;
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
    } catch (BackendServiceException e) {
      log.debug("Failed to load Materialbörse counts", e);
      return new MaterialExchangeCountsDto(0, 0);
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
   * Loads the board requests (Gesuche) for a tab with the toolbar filters applied.
   *
   * @param tab the active tab ({@code alle} / {@code mein}).
   * @param q the search fragment, or {@code null}.
   * @param minQuality the minimum quality floor, or {@code null}.
   * @param minAmount the minimum desired quantity, or {@code null}.
   * @param sort the sort key, or {@code null}.
   * @return the requests, never {@code null} (empty on a backend error).
   */
  private List<MaterialRequestDto> loadRequests(
      String tab, String q, Integer minQuality, Double minAmount, String sort) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/material-requests")
            .queryParam("tab", tab)
            .queryParam("size", BOARD_SIZE);
    appendIfPresent(uri, "minQuality", minQuality);
    appendIfPresent(uri, "minAmount", minAmount);
    appendIfPresent(uri, "sort", sort);
    try {
      PageResponse<MaterialRequestDto> page = backendGetWithQuery(uri, q, REQUESTS_PAGE);
      return page == null ? List.of() : page.content();
    } catch (BackendServiceException e) {
      log.debug("Failed to load Materialbörse Gesuche board", e);
      return List.of();
    } catch (Exception e) {
      log.error("Failed to load Materialbörse Gesuche board", e);
      return List.of();
    }
  }

  /**
   * Loads one request's detail (supplier names included only when the caller is the owner).
   *
   * @param id the request id as a string, or {@code null}.
   * @return the request detail, or {@code null} if absent/unparseable.
   */
  private MaterialRequestDto loadRequestDetail(String id) {
    UUID requestId = parseUuid(id);
    if (requestId == null) {
      return null;
    }
    try {
      return backendApiClient.get(
          "/api/v1/material-requests/" + requestId, MaterialRequestDto.class);
    } catch (BackendServiceException e) {
      log.debug("Failed to load Materialbörse request {}", requestId, e);
      return null;
    } catch (Exception e) {
      log.error("Failed to load Materialbörse request {}", requestId, e);
      return null;
    }
  }

  /**
   * Loads the request board tab counts, defaulting to zero on a backend error.
   *
   * @return the counts.
   */
  private MaterialExchangeCountsDto loadRequestCounts() {
    try {
      MaterialExchangeCountsDto counts =
          backendApiClient.get("/api/v1/material-requests/counts", MaterialExchangeCountsDto.class);
      return counts == null ? new MaterialExchangeCountsDto(0, 0) : counts;
    } catch (BackendServiceException e) {
      log.debug("Failed to load Materialbörse Gesuche counts", e);
      return new MaterialExchangeCountsDto(0, 0);
    } catch (Exception e) {
      log.error("Failed to load Materialbörse Gesuche counts", e);
      return new MaterialExchangeCountsDto(0, 0);
    }
  }

  /**
   * Chooses the request id to show in the detail pane — the requested one if present, else the
   * first request.
   *
   * @param requests the board list.
   * @param requested the requested request id as a string, or {@code null}.
   * @return the selected request id as a string, or {@code null} if the list is empty.
   */
  private static String pickSelectedRequestId(List<MaterialRequestDto> requests, String requested) {
    UUID requestedId = parseUuid(requested);
    if (requestedId != null
        && requests.stream().anyMatch(request -> requestedId.equals(request.id()))) {
      return requested;
    }
    return requests.isEmpty() ? null : requests.get(0).id().toString();
  }

  /**
   * Runs an authenticated backend GET whose query string carries an optional free-text {@code q}
   * fragment, encoding {@code q} <em>exactly once</em> across the frontend&rarr;backend hop. The
   * caller pre-sets every non-free-text (safe) parameter on {@code uri}; this method appends the
   * {@code q} fragment as a WebClient URI-template variable ({@code q={q}}) rather than into the
   * pre-encoded {@link UriComponentsBuilder#toUriString()} output.
   *
   * <p>The distinction matters because {@code toUriString()} already percent-encodes its query
   * values and the WebClient then encodes the resulting string a second time: a space in a
   * multi-word term becomes {@code %2520} instead of {@code %20}, so the backend
   * {@code @RequestParam} decodes a literal {@code %20}-laden string that matches nothing (the
   * {@code MaterialboardItemStockOfferE2eTest} "E2E Boerse Item Stock Widget" release-picker search
   * returned zero rows). Passing {@code q} as a URI variable lets the WebClient encode it once, per
   * RFC 3986 — the #371 frontend&rarr;backend re-encoding contract (REQ-MARKET-002/014).
   *
   * @param uri the pre-built URI (path plus every safe, non-free-text query parameter).
   * @param q the free-text search fragment, or {@code null}/blank for no filter.
   * @param responseType the decoded response type.
   * @param <T> the response body type.
   * @return the decoded backend response.
   */
  private <T> T backendGetWithQuery(
      @NotNull UriComponentsBuilder uri,
      @Nullable String q,
      @NotNull ParameterizedTypeReference<T> responseType) {
    String base = uri.toUriString();
    if (q == null || q.isBlank()) {
      return backendApiClient.get(base, responseType);
    }
    String separator = base.indexOf('?') >= 0 ? "&" : "?";
    return backendApiClient.get(base + separator + "q={q}", responseType, q);
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
