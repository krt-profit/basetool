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

package de.greluc.krt.profit.basetool.backend.integration;

import de.greluc.krt.profit.basetool.backend.config.UexProperties;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexCategoryDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexCityDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexCommodityPriceDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexCompanyDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexFactionDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexItemDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexItemPriceDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexJurisdictionDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexMoonDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexOrbitDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexOutpostDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexPlanetDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexPoiDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexRefineryYieldDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexRefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexResponseDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexSpaceStationDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexStarSystemDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexTerminalDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexVehicleDto;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Read-only HTTP client for the UEX (uexcorp.space) catalog API.
 *
 * <p>Every public {@code get…()} method delegates to the shared {@link #fetchList} helper which
 * adds {@code If-None-Match} to the request when a previous ETag is known for the same endpoint
 * (M-5 from the performance audit), unwraps the {@code UexResponseDto<T>} envelope into a plain
 * {@code List<T>}, applies a 30-second per-call timeout, and on ANY error or {@code 304 Not
 * Modified} returns an empty list — the calling sync services treat an empty payload as "skip this
 * run" and explicitly never wipe local tables based on it, so a transient outage or a feed that has
 * not changed since the last sync both result in the same no-op behaviour.
 *
 * <p>ETag storage is an in-memory {@link ConcurrentHashMap} keyed by endpoint URL; entries survive
 * for the lifetime of the application context and are deliberately not persisted (a restart pays
 * the cost of one full sync per endpoint to repopulate them, which is the desired "fresh start"
 * behaviour and avoids stale ETags surviving across a UEX-side cache flush).
 *
 * <p>The reactive {@code Mono} chain is collapsed with {@code blockOptional()} because all callers
 * are scheduled background tasks that have nothing else to do while waiting — synchronous code here
 * is simpler than threading the reactive type through every service method.
 *
 * <p>The WebClient is built once in {@link #initClient()} after dependency injection so the
 * underlying Reactor-Netty connection pool is actually shared across requests. {@code
 * maxInMemorySize(16 MB)} raises Spring's default 256 KB ceiling because the {@code
 * commodities_prices_all} response routinely exceeds 1 MB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UexClient {

  /** Per-call timeout for the underlying reactive request. */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

  private final WebClient.Builder webClientBuilder;
  private final UexProperties uexProperties;

  /**
   * Micrometer registry for the swallowed-fetch-error counter ({@link
   * MetricNames#EXTERNAL_FETCH_ERRORS}). Injected so a transient-or-sustained upstream failure —
   * which {@link #fetchList} maps to an empty list — still leaves a metric trail (REQ-OBS-011).
   */
  private final MeterRegistry meterRegistry;

  /**
   * Reusable WebClient bound to the UEX base URL. Built once after dependency injection completes
   * (instead of per call) so the underlying connection pool is actually shared across requests. The
   * Reactor-Netty HttpClient already carries the connect / read / write timeouts configured in
   * WebClientConfig.
   */
  private WebClient client;

  /**
   * Last-seen {@code ETag} response header value, keyed by UEX endpoint path. Populated from the
   * response of every successful {@code 2xx} call and replayed as {@code If-None-Match} on the next
   * request to the same endpoint, so an unchanged feed short-circuits with a {@code 304 Not
   * Modified}. Concurrent because two scheduled syncs may overlap if the previous one ran long.
   */
  private final Map<String, String> etagByEndpoint = new ConcurrentHashMap<>();

  /**
   * Builds the {@link WebClient} after dependency injection. Done once in {@code @PostConstruct}
   * instead of lazily per call so the Reactor-Netty connection pool from {@link
   * de.greluc.krt.profit.basetool.backend.config.WebClientConfig} is reused across all UEX requests
   * for the lifetime of the application.
   */
  @PostConstruct
  void initClient() {
    this.client =
        webClientBuilder
            .baseUrl(uexProperties.getApiUrl())
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();
  }

  /**
   * Fetches the full UEX commodity catalog. See class Javadoc for the shared pattern.
   *
   * @return all commodities, or an empty list if the upstream call fails, times out, or returns
   *     {@code 304 Not Modified}
   */
  public List<UexCommodityDto> getCommodities() {
    return fetchList(
        uexProperties.getCommoditiesEndpoint(),
        new ParameterizedTypeReference<>() {},
        "commodities");
  }

  /**
   * Fetches the full UEX commodity-price matrix (every commodity × every terminal). This is the
   * largest UEX payload by far ({@literal >}1 MB) — see the {@code maxInMemorySize} in {@link
   * #initClient()}.
   *
   * @return all commodity prices, or an empty list on error / 304
   */
  public List<UexCommodityPriceDto> getCommoditiesPricesAll() {
    return fetchList(
        uexProperties.getCommoditiesPricesEndpoint(),
        new ParameterizedTypeReference<>() {},
        "commodities prices");
  }

  /**
   * Fetches the full UEX item-price matrix ({@code /items_prices_all}, ~24 000 rows, {@literal >}1
   * MB — covered by the {@code maxInMemorySize} in {@link #initClient()}). Drives the R7 {@code
   * UexItemPriceSyncService}, which is feature-flagged off by default.
   *
   * @return all item prices, or an empty list on error / 304
   */
  public List<UexItemPriceDto> getItemPrices() {
    return fetchList(
        uexProperties.getItemsPricesEndpoint(),
        new ParameterizedTypeReference<>() {},
        "item prices");
  }

  /**
   * Returns all star systems, or an empty list on error / 304.
   *
   * @return all star systems, or an empty list on error / 304
   */
  public List<UexStarSystemDto> getStarSystems() {
    return fetchList(
        uexProperties.getStarSystemsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "star systems");
  }

  /**
   * Returns all companies (in-universe manufacturers), or an empty list on error / 304.
   *
   * @return all companies, or an empty list on error / 304
   */
  public List<UexCompanyDto> getCompanies() {
    return fetchList(
        uexProperties.getCompaniesEndpoint(), new ParameterizedTypeReference<>() {}, "companies");
  }

  /**
   * Returns all vehicles (ships and ground vehicles), or an empty list on error / 304.
   *
   * @return all vehicles, or an empty list on error / 304
   */
  public List<UexVehicleDto> getVehicles() {
    return fetchList(
        uexProperties.getVehiclesEndpoint(), new ParameterizedTypeReference<>() {}, "vehicles");
  }

  /**
   * Returns all cities, or an empty list on error / 304.
   *
   * @return all cities, or an empty list on error / 304
   */
  public List<UexCityDto> getCities() {
    return fetchList(
        uexProperties.getCitiesEndpoint(), new ParameterizedTypeReference<>() {}, "cities");
  }

  /**
   * Returns all in-universe factions, or an empty list on error / 304.
   *
   * @return all factions, or an empty list on error / 304
   */
  public List<UexFactionDto> getFactions() {
    return fetchList(
        uexProperties.getFactionsEndpoint(), new ParameterizedTypeReference<>() {}, "factions");
  }

  /**
   * Returns all jurisdictions (legal authorities covering a system or region), or an empty list on
   * error / 304.
   *
   * @return all jurisdictions, or an empty list on error / 304
   */
  public List<UexJurisdictionDto> getJurisdictions() {
    return fetchList(
        uexProperties.getJurisdictionsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "jurisdictions");
  }

  /**
   * Returns all moons, or an empty list on error / 304.
   *
   * @return all moons, or an empty list on error / 304
   */
  public List<UexMoonDto> getMoons() {
    return fetchList(
        uexProperties.getMoonsEndpoint(), new ParameterizedTypeReference<>() {}, "moons");
  }

  /**
   * Returns all orbital locations, or an empty list on error / 304.
   *
   * @return all orbits, or an empty list on error / 304
   */
  public List<UexOrbitDto> getOrbits() {
    return fetchList(
        uexProperties.getOrbitsEndpoint(), new ParameterizedTypeReference<>() {}, "orbits");
  }

  /**
   * Returns all outposts, or an empty list on error / 304.
   *
   * @return all outposts, or an empty list on error / 304
   */
  public List<UexOutpostDto> getOutposts() {
    return fetchList(
        uexProperties.getOutpostsEndpoint(), new ParameterizedTypeReference<>() {}, "outposts");
  }

  /**
   * Returns all planets, or an empty list on error / 304.
   *
   * @return all planets, or an empty list on error / 304
   */
  public List<UexPlanetDto> getPlanets() {
    return fetchList(
        uexProperties.getPlanetsEndpoint(), new ParameterizedTypeReference<>() {}, "planets");
  }

  /**
   * Returns all points of interest (Lagrange points, derelicts, anomalies), or an empty list on
   * error / 304.
   *
   * @return all points of interest, or an empty list on error / 304
   */
  public List<UexPoiDto> getPoi() {
    return fetchList(uexProperties.getPoiEndpoint(), new ParameterizedTypeReference<>() {}, "pois");
  }

  /**
   * Returns all space stations, or an empty list on error / 304.
   *
   * @return all space stations, or an empty list on error / 304
   */
  public List<UexSpaceStationDto> getSpaceStations() {
    return fetchList(
        uexProperties.getSpaceStationsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "spacestations");
  }

  /**
   * Returns all terminals (trade kiosks at any location type), or an empty list on error / 304.
   *
   * @return all terminals, or an empty list on error / 304
   */
  public List<UexTerminalDto> getTerminals() {
    return fetchList(
        uexProperties.getTerminalsEndpoint(), new ParameterizedTypeReference<>() {}, "terminals");
  }

  /**
   * Fetches all refining methods (e.g. {@code Cormack}, {@code Pyrometric}, …). Drives the local
   * refining-method catalog used by the refinery-order pricing.
   *
   * @return all refining methods, or an empty list on error / 304
   */
  public List<UexRefiningMethodDto> getRefineriesMethods() {
    return fetchList(
        uexProperties.getRefineriesMethodsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "refineries methods");
  }

  /**
   * Fetches refinery yield ratios per (terminal, method, commodity). Used by the refinery sync to
   * compute expected output quantities for a given input.
   *
   * @return all refinery yields, or an empty list on error / 304
   */
  public List<UexRefineryYieldDto> getRefineriesYields() {
    return fetchList(
        uexProperties.getRefineriesYieldsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "refineries yields");
  }

  /**
   * Fetches the UEX category reference table (R2). The list drives {@code UexItemSyncService}'s
   * 98-iteration walk through {@code /items?id_category=<n>}.
   *
   * @return all categories, or an empty list on error / 304
   */
  public List<UexCategoryDto> getCategories() {
    return fetchList(
        uexProperties.getCategoriesEndpoint(), new ParameterizedTypeReference<>() {}, "categories");
  }

  /**
   * Fetches every UEX item in a single category (R2). UEX rejects {@code /items} without a filter
   * parameter; the {@code UexItemSyncService} drives this method from the {@code uex_category}
   * reference table.
   *
   * <p>ETag storage is keyed by the full URL including the {@code id_category} query so each
   * category's response is conditionally cached independently — a category whose roster has not
   * changed between sync runs short-circuits via {@code 304 Not Modified} without re-parsing the
   * payload.
   *
   * @param categoryId UEX integer category id (from {@code /categories[].id})
   * @return items in this category, or an empty list on error / 304
   */
  public List<UexItemDto> getItemsForCategory(int categoryId) {
    String endpoint = uexProperties.getItemsEndpoint() + "?id_category=" + categoryId;
    return fetchList(
        endpoint, new ParameterizedTypeReference<>() {}, "items (category=" + categoryId + ")");
  }

  /**
   * Shared request pipeline for every UEX list endpoint. Implements the conditional GET (M-5)
   * behaviour and the unified error / empty-list fallback.
   *
   * <ol>
   *   <li>If we have a previous ETag for {@code endpoint}, attach it as {@code If-None-Match}.
   *   <li>If the response is {@code 304 Not Modified}, log at DEBUG and return an empty list — sync
   *       services treat this as "skip this run", exactly the right behaviour for unchanged data.
   *   <li>On {@code 2xx}, store the response's ETag (if any) keyed by endpoint URL for the next
   *       call, then deserialise the body into {@code UexResponseDto<T>} and unwrap the data list.
   *   <li>On non-2xx (and non-304) responses, propagate the {@link
   *       org.springframework.web.reactive.function.client.WebClientResponseException} through
   *       {@code .createError()} into the unified {@code onErrorResume} fallback.
   *   <li>On any error (timeout, decoding failure, server error), log at ERROR and return an empty
   *       list — the caller MUST treat the result as "skip this run", not as "the table is empty".
   * </ol>
   *
   * @param <T> the per-row payload type inside {@code UexResponseDto.data}
   * @param endpoint UEX endpoint path (e.g. {@code /commodities}); also serves as the cache key
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel human-readable label for log messages (singular/plural to taste)
   * @return parsed list of {@code T}, or an empty list on error / 304
   */
  private <T> List<T> fetchList(
      String endpoint,
      ParameterizedTypeReference<UexResponseDto<T>> typeRef,
      String resourceLabel) {
    log.info("Fetching all {} from UEX API", resourceLabel);
    WebClient.RequestHeadersSpec<?> request = client.get().uri(endpoint);
    String previousEtag = etagByEndpoint.get(endpoint);
    if (previousEtag != null) {
      request = request.header(HttpHeaders.IF_NONE_MATCH, previousEtag);
    }
    return request
        .exchangeToMono(
            response -> {
              if (response.statusCode().value() == 304) {
                log.debug(
                    "UEX {} unchanged since last sync (304 Not Modified) — skipping",
                    resourceLabel);
                return Mono.just(Collections.<T>emptyList());
              }
              if (!response.statusCode().is2xxSuccessful()) {
                return response.createError();
              }
              String etag = response.headers().asHttpHeaders().getETag();
              if (etag != null && !etag.isBlank()) {
                etagByEndpoint.put(endpoint, etag);
              }
              return response
                  .bodyToMono(typeRef)
                  .map(body -> body.data() == null ? Collections.<T>emptyList() : body.data());
            })
        .timeout(CALL_TIMEOUT)
        .onErrorResume(
            e -> {
              log.warn("Failed to fetch {} from UEX API", resourceLabel, e);
              meterRegistry
                  .counter(
                      MetricNames.EXTERNAL_FETCH_ERRORS,
                      MetricNames.TAG_SOURCE,
                      MetricNames.SOURCE_UEX)
                  .increment();
              return Mono.just(Collections.<T>emptyList());
            })
        .blockOptional()
        .orElse(Collections.emptyList());
  }
}
