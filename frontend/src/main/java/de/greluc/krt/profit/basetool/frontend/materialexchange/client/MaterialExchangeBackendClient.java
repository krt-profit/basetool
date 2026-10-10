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

package de.greluc.krt.profit.basetool.frontend.materialexchange.client;

import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintProductDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeCountsDto;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeItemReleaseRequest;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeOfferUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeReleasableItemDto;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeReleaseRequest;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialItemRequestCreateRequest;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialRequestCreateRequest;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialRequestDto;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialRequestUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the material-exchange domain: the Materialbörse offer and request
 * (Gesuch) boards, their writes and the pickers behind the release and request dialogs
 * (REQ-MARKET-*), over {@link BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class MaterialExchangeBackendClient {

  private static final ParameterizedTypeReference<PageResponse<MaterialExchangeOfferDto>>
      OFFERS_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<MaterialRequestDto>> REQUESTS_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<MaterialDto>> MATERIAL_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<MaterialExchangeReleasableItemDto>>
      RELEASABLE_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<BlueprintProductDto>> PRODUCT_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * The toolbar filters of one board read; every value is typed or already narrowed to the offered
   * options.
   *
   * @param tab the scope tab, {@code alle} or {@code mein}
   * @param size the page size
   * @param minQuality the minimum quality, or {@code null}
   * @param minAmount the minimum quantity, or {@code null}
   * @param sort an offered sort key, or {@code null}
   * @param q the free-text search fragment, or {@code null}/blank for none
   */
  public record BoardFilter(
      @NotNull String tab,
      int size,
      @Nullable Integer minQuality,
      @Nullable Double minAmount,
      @Nullable String sort,
      @Nullable String q) {}

  /**
   * Reads one page of the offer board.
   *
   * @param filter the toolbar filters
   * @param excludeStolen whether offers of stock marked stolen are left out
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialExchangeOfferDto> offers(
      @NotNull BoardFilter filter, boolean excludeStolen) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/material-exchange/offers")
            .queryParam("tab", filter.tab())
            .queryParam("size", filter.size());
    appendIfPresent(uri, "minQuality", filter.minQuality());
    appendIfPresent(uri, "minAmount", filter.minAmount());
    appendIfPresent(uri, "sort", filter.sort());
    if (excludeStolen) {
      uri.queryParam("excludeStolen", true);
    }
    return getWithQuery(uri, filter.q(), OFFERS_PAGE);
  }

  /**
   * Reads one offer with the interested handles the caller may see.
   *
   * @param id the offer
   * @return the offer, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExchangeOfferDto offer(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/material-exchange/offers/{id}", MaterialExchangeOfferDto.class, id);
  }

  /**
   * Reads the offer board's tab counts.
   *
   * @return the counts, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExchangeCountsDto offerCounts() {
    return backendApiClient.get(
        "/api/v1/material-exchange/counts", MaterialExchangeCountsDto.class);
  }

  /**
   * Releases one of the caller's Lager rows to the board.
   *
   * @param request the row, quantity and remark
   * @return the offer, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExchangeOfferDto release(@NotNull MaterialExchangeReleaseRequest request) {
    return backendApiClient.post(
        "/api/v1/material-exchange/offers", request, MaterialExchangeOfferDto.class);
  }

  /**
   * Lists a craftable item on the board.
   *
   * @param request the product, quantity and remark
   * @return the offer, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExchangeOfferDto releaseItem(@NotNull MaterialExchangeItemReleaseRequest request) {
    return backendApiClient.post(
        "/api/v1/material-exchange/item-offers", request, MaterialExchangeOfferDto.class);
  }

  /**
   * Searches the blueprint products the item-offer dialog can list.
   *
   * @param q the name fragment, or {@code null}/blank for the first products
   * @param limit the result cap
   * @return the products, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BlueprintProductDto> offerableProducts(@Nullable String q, int limit) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/blueprints/products/search")
            .queryParam("limit", limit);
    return getWithQuery(uri, q, PRODUCT_LIST);
  }

  /**
   * Edits an offer's quantity and remark.
   *
   * @param id the offer
   * @param request the new values and the version they replace
   * @return the offer, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExchangeOfferDto updateOffer(
      @NotNull UUID id, @NotNull MaterialExchangeOfferUpdateRequest request) {
    return backendApiClient.put(
        "/api/v1/material-exchange/offers/{id}/remark",
        request,
        MaterialExchangeOfferDto.class,
        id);
  }

  /**
   * Deactivates an offer.
   *
   * @param id the offer
   * @return the offer, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExchangeOfferDto deactivateOffer(@NotNull UUID id) {
    return backendApiClient.post(
        "/api/v1/material-exchange/offers/{id}/deactivate",
        null,
        MaterialExchangeOfferDto.class,
        id);
  }

  /**
   * Deactivates the active offer of a Lager row.
   *
   * @param inventoryItemId the Lager row
   * @return the offer, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExchangeOfferDto deactivateOfferForItem(@NotNull UUID inventoryItemId) {
    return backendApiClient.post(
        "/api/v1/material-exchange/items/{inventoryItemId}/deactivate",
        null,
        MaterialExchangeOfferDto.class,
        inventoryItemId);
  }

  /**
   * Registers the caller's interest in an offer.
   *
   * @param id the offer
   * @return the offer, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExchangeOfferDto registerInterest(@NotNull UUID id) {
    return backendApiClient.post(
        "/api/v1/material-exchange/offers/{id}/interest", null, MaterialExchangeOfferDto.class, id);
  }

  /**
   * Withdraws the caller's interest from an offer.
   *
   * @param id the offer
   * @return the offer, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExchangeOfferDto withdrawInterest(@NotNull UUID id) {
    return backendApiClient.delete(
        "/api/v1/material-exchange/offers/{id}/interest", MaterialExchangeOfferDto.class, id);
  }

  /**
   * Lists the caller's Lager rows eligible for release.
   *
   * @param q the material-name fragment, or {@code null}/blank for none
   * @param kind {@code MATERIAL} or {@code ITEM}, or {@code null} for both
   * @return the rows, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MaterialExchangeReleasableItemDto> releasableItems(
      @Nullable String q, @Nullable String kind) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/material-exchange/releasable-items");
    if (kind != null && !kind.isBlank()) {
      uri.queryParam("kind", kind);
    }
    return getWithQuery(uri, q, RELEASABLE_LIST);
  }

  /**
   * Reads one page of the request (Gesuch) board.
   *
   * @param filter the toolbar filters
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialRequestDto> requests(@NotNull BoardFilter filter) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/material-requests")
            .queryParam("tab", filter.tab())
            .queryParam("size", filter.size());
    appendIfPresent(uri, "minQuality", filter.minQuality());
    appendIfPresent(uri, "minAmount", filter.minAmount());
    appendIfPresent(uri, "sort", filter.sort());
    return getWithQuery(uri, filter.q(), REQUESTS_PAGE);
  }

  /**
   * Reads one request with the supplier handles the caller may see.
   *
   * @param id the request
   * @return the request, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialRequestDto request(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/material-requests/{id}", MaterialRequestDto.class, id);
  }

  /**
   * Reads the request board's tab counts.
   *
   * @return the counts, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExchangeCountsDto requestCounts() {
    return backendApiClient.get(
        "/api/v1/material-requests/counts", MaterialExchangeCountsDto.class);
  }

  /**
   * Posts a material wanted-listing.
   *
   * @param request the material, quality floor, quantity and description
   * @return the request, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialRequestDto createMaterialRequest(@NotNull MaterialRequestCreateRequest request) {
    return backendApiClient.post("/api/v1/material-requests", request, MaterialRequestDto.class);
  }

  /**
   * Posts a craftable-item wanted-listing.
   *
   * @param request the product, quality floor, quantity and description
   * @return the request, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialRequestDto createItemRequest(@NotNull MaterialItemRequestCreateRequest request) {
    return backendApiClient.post(
        "/api/v1/material-requests/item", request, MaterialRequestDto.class);
  }

  /**
   * Edits a request's quantity, quality floor and description.
   *
   * @param id the request
   * @param request the new values and the version they replace
   * @return the request, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialRequestDto updateRequest(
      @NotNull UUID id, @NotNull MaterialRequestUpdateRequest request) {
    return backendApiClient.put(
        "/api/v1/material-requests/{id}", request, MaterialRequestDto.class, id);
  }

  /**
   * Deactivates a request.
   *
   * @param id the request
   * @return the request, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialRequestDto deactivateRequest(@NotNull UUID id) {
    return backendApiClient.post(
        "/api/v1/material-requests/{id}/deactivate", null, MaterialRequestDto.class, id);
  }

  /**
   * Signals that the caller can supply a request.
   *
   * @param id the request
   * @return the request, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialRequestDto signalFulfillment(@NotNull UUID id) {
    return backendApiClient.post(
        "/api/v1/material-requests/{id}/interest", null, MaterialRequestDto.class, id);
  }

  /**
   * Withdraws the caller's supply signal from a request.
   *
   * @param id the request
   * @return the request, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialRequestDto withdrawFulfillment(@NotNull UUID id) {
    return backendApiClient.delete(
        "/api/v1/material-requests/{id}/interest", MaterialRequestDto.class, id);
  }

  /**
   * Searches the catalogue materials for the request dialog's type-ahead.
   *
   * @param q the name fragment, or {@code null}/blank for the first materials
   * @param size the page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialDto> requestMaterials(@Nullable String q, int size) {
    String base =
        UriComponentsBuilder.fromPath("/api/v1/materials/search")
            .queryParam("size", size)
            .toUriString();
    return q == null || q.isBlank()
        ? backendApiClient.get(base, MATERIAL_PAGE)
        : backendApiClient.get(base + "&search={search}", MATERIAL_PAGE, q);
  }

  /**
   * Runs a GET, appending the free-text fragment as a URI-template variable so it is encoded once
   * (REQ-MARKET-002/014).
   *
   * @param uri the path with every non-free-text query parameter
   * @param q the free-text fragment, or {@code null}/blank for none
   * @param responseType the decoded response type
   * @param <T> the response body type
   * @return the decoded body, or {@code null} when the backend sent none
   */
  @Nullable
  private <T> T getWithQuery(
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
   * Appends a query parameter when its value is present and, for a string, non-blank.
   *
   * @param uri the builder
   * @param name the parameter name
   * @param value the value, or {@code null}
   */
  private static void appendIfPresent(
      @NotNull UriComponentsBuilder uri, @NotNull String name, @Nullable Object value) {
    if (value == null || (value instanceof String s && s.isBlank())) {
      return;
    }
    uri.queryParam(name, value);
  }
}
