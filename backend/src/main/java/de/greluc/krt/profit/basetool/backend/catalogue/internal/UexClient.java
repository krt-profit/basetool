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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCategoryDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCommodityPriceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCompanyDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexFactionDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexItemPriceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexJurisdictionDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexMoonDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexOrbitDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexOutpostDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexPlanetDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexPoiDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexRefineryYieldDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexRefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexResponseDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexSpaceStationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexStarSystemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexTerminalDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexVehicleDto;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.ResponseSizeLimitInterceptor;
import de.greluc.krt.profit.basetool.backend.platform.api.RestClientConfig;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Read-only, fail-soft HTTP client for the UEX (uexcorp.space) catalog API.
 *
 * <p>Every fetch is a plain GET that returns empty rows on any error; callers must treat an empty
 * result as "skip this run", never as an empty catalogue. UEX serves no {@code ETag} or {@code
 * Last-Modified}, so there is no conditional request. An {@code ok} envelope with {@code data:
 * null} is UEX's empty result set, not a fault. Response bodies are capped at {@value
 * #MAX_RESPONSE_BYTES} bytes (ADR-0204).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConcurrencyLimit(UexClient.MAX_CONCURRENT_CALLS)
public class UexClient {

  /**
   * Most calls in flight at once through this client; further callers wait. Equal to the UEX
   * executor's maximum pool size.
   */
  static final int MAX_CONCURRENT_CALLS = 4;

  /**
   * Largest response body one UEX call may deliver (16 MiB). An oversized body fails the fetch into
   * the counted empty-result fallback rather than being truncated.
   */
  static final long MAX_RESPONSE_BYTES = 16L * 1024 * 1024;

  /**
   * The only {@code status} value a UEX envelope is documented to carry on success. Compared
   * case-insensitively; anything else present in the field is reported as an upstream self-declared
   * failure by {@link #unwrapEnvelope}.
   */
  private static final String ENVELOPE_STATUS_OK = "ok";

  /**
   * Cap for the upstream-supplied {@code status} string in log lines. UEX is a third party, so the
   * field is untrusted free text: it goes through {@link LogSafe} before it reaches a logger, and
   * 32 characters is generous for a token like {@code ok} or {@code error} while keeping a hostile
   * or malformed value from bloating the line.
   */
  private static final int MAX_STATUS_LOG_LENGTH = 32;

  /**
   * A fresh, observed builder for external hosts from {@code RestClientConfig} (prototype-scoped),
   * so the base URL set here does not leak into any other client.
   */
  @Qualifier(RestClientConfig.EXTERNAL_REST_CLIENT_BUILDER)
  private final RestClient.Builder restClientBuilder;

  /** The {@code app.uex.*} configuration: base URL and one path per endpoint. */
  private final UexProperties uexProperties;

  /**
   * Micrometer registry for the swallowed-fetch-error counter ({@link
   * CatalogueMetricNames#EXTERNAL_FETCH_ERRORS}). Injected so a transient-or-sustained upstream
   * failure — which {@link #fetchList} maps to an empty list — still leaves a metric trail
   * (REQ-OBS-011).
   */
  private final MeterRegistry meterRegistry;

  /**
   * Client bound to the UEX base URL, built once so its connection pool is shared; carries the
   * timeouts from {@code RestClientConfig}.
   */
  private RestClient client;

  /** Builds the shared {@link RestClient} once after dependency injection. */
  @PostConstruct
  void initClient() {
    this.client =
        restClientBuilder
            .baseUrl(uexProperties.apiUrl())
            .requestInterceptor(new ResponseSizeLimitInterceptor(MAX_RESPONSE_BYTES))
            .build();
  }

  /**
   * Fetches the full UEX commodity catalog. See class Javadoc for the shared pattern.
   *
   * @return the commodities; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexCommodityDto> getCommodities() {
    return fetchListWithOutcome(
        uexProperties.commoditiesEndpoint(), new ParameterizedTypeReference<>() {}, "commodities");
  }

  /**
   * Fetches the full UEX commodity-price matrix (every commodity × every terminal). This is the
   * largest UEX payload by far ({@literal >}1 MB) — see {@link #MAX_RESPONSE_BYTES}.
   *
   * @return the commodity prices; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexCommodityPriceDto> getCommoditiesPricesAll() {
    return fetchListWithOutcome(
        uexProperties.commoditiesPricesEndpoint(),
        new ParameterizedTypeReference<>() {},
        "commodities prices");
  }

  /**
   * Fetches the full UEX item-price matrix ({@code /items_prices_all}).
   *
   * @return the item prices; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexItemPriceDto> getItemPrices() {
    return fetchListWithOutcome(
        uexProperties.itemsPricesEndpoint(), new ParameterizedTypeReference<>() {}, "item prices");
  }

  /**
   * Returns all star systems.
   *
   * @return the star systems; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexStarSystemDto> getStarSystems() {
    return fetchListWithOutcome(
        uexProperties.starSystemsEndpoint(), new ParameterizedTypeReference<>() {}, "star systems");
  }

  /**
   * Returns all companies (in-universe manufacturers).
   *
   * @return the companies; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexCompanyDto> getCompanies() {
    return fetchListWithOutcome(
        uexProperties.companiesEndpoint(), new ParameterizedTypeReference<>() {}, "companies");
  }

  /**
   * Returns all vehicles (ships and ground vehicles).
   *
   * @return the vehicles; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexVehicleDto> getVehicles() {
    return fetchListWithOutcome(
        uexProperties.vehiclesEndpoint(), new ParameterizedTypeReference<>() {}, "vehicles");
  }

  /**
   * Returns all cities.
   *
   * @return the cities; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexCityDto> getCities() {
    return fetchListWithOutcome(
        uexProperties.citiesEndpoint(), new ParameterizedTypeReference<>() {}, "cities");
  }

  /**
   * Returns all in-universe factions.
   *
   * @return the factions; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexFactionDto> getFactions() {
    return fetchListWithOutcome(
        uexProperties.factionsEndpoint(), new ParameterizedTypeReference<>() {}, "factions");
  }

  /**
   * Returns all jurisdictions (legal authorities covering a system or region).
   *
   * @return the jurisdictions; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexJurisdictionDto> getJurisdictions() {
    return fetchListWithOutcome(
        uexProperties.jurisdictionsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "jurisdictions");
  }

  /**
   * Returns all moons.
   *
   * @return the moons; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexMoonDto> getMoons() {
    return fetchListWithOutcome(
        uexProperties.moonsEndpoint(), new ParameterizedTypeReference<>() {}, "moons");
  }

  /**
   * Returns all orbital locations.
   *
   * @return the orbits; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexOrbitDto> getOrbits() {
    return fetchListWithOutcome(
        uexProperties.orbitsEndpoint(), new ParameterizedTypeReference<>() {}, "orbits");
  }

  /**
   * Returns all outposts.
   *
   * @return the outposts; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexOutpostDto> getOutposts() {
    return fetchListWithOutcome(
        uexProperties.outpostsEndpoint(), new ParameterizedTypeReference<>() {}, "outposts");
  }

  /**
   * Returns all planets.
   *
   * @return the planets; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexPlanetDto> getPlanets() {
    return fetchListWithOutcome(
        uexProperties.planetsEndpoint(), new ParameterizedTypeReference<>() {}, "planets");
  }

  /**
   * Returns all points of interest (Lagrange points, derelicts, anomalies).
   *
   * @return the points of interest; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexPoiDto> getPoi() {
    return fetchListWithOutcome(
        uexProperties.poiEndpoint(), new ParameterizedTypeReference<>() {}, "pois");
  }

  /**
   * Returns all space stations.
   *
   * @return the space stations; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexSpaceStationDto> getSpaceStations() {
    return fetchListWithOutcome(
        uexProperties.spaceStationsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "spacestations");
  }

  /**
   * Returns all terminals (trade kiosks at any location type).
   *
   * @return the terminals; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexTerminalDto> getTerminals() {
    return fetchListWithOutcome(
        uexProperties.terminalsEndpoint(), new ParameterizedTypeReference<>() {}, "terminals");
  }

  /**
   * Fetches all refining methods (e.g. {@code Cormack}, {@code Pyrometric}).
   *
   * @return all refining methods, or an empty list on error
   */
  public List<UexRefiningMethodDto> getRefineriesMethods() {
    return fetchList(
        uexProperties.refineriesMethodsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "refineries methods");
  }

  /**
   * Fetches refinery yield ratios per (terminal, method, commodity). Used by the refinery sync to
   * compute expected output quantities for a given input.
   *
   * <p>Bare-list caveat as for {@link #getRefineriesMethods()}.
   *
   * @return all refinery yields, or an empty list on error
   */
  public List<UexRefineryYieldDto> getRefineriesYields() {
    return fetchList(
        uexProperties.refineriesYieldsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "refineries yields");
  }

  /**
   * Fetches the UEX category reference table (R2). The list drives {@code UexItemSyncService}'s
   * walk over every game-related item category through {@code /items?id_category=<n>}.
   *
   * @return the categories; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexCategoryDto> getCategories() {
    return fetchListWithOutcome(
        uexProperties.categoriesEndpoint(), new ParameterizedTypeReference<>() {}, "categories");
  }

  /**
   * Fetches every UEX item in one category.
   *
   * @param categoryId UEX integer category id (from {@code /categories[].id})
   * @return the items; {@code data} is empty on error or an empty 200
   */
  public FetchResult<UexItemDto> getItemsForCategory(int categoryId) {
    String endpoint = uexProperties.itemsEndpoint() + "?id_category=" + categoryId;
    return fetchListWithOutcome(
        endpoint, new ParameterizedTypeReference<>() {}, "items (category=" + categoryId + ")");
  }

  /**
   * {@code List}-returning wrapper over {@link #fetchListWithOutcome}; discards the outcome, so new
   * endpoints must not use it.
   *
   * @param <T> the per-row payload type inside {@code UexResponseDto.data}
   * @param endpoint UEX endpoint path
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log messages
   * @return the parsed rows, or an empty list on error
   */
  private <T> List<T> fetchList(
      String endpoint,
      ParameterizedTypeReference<UexResponseDto<T>> typeRef,
      String resourceLabel) {
    return fetchListWithOutcome(endpoint, typeRef, resourceLabel).data();
  }

  /**
   * Shared request pipeline for every UEX list endpoint: GET, envelope unwrapping, and a fail-soft
   * fallback.
   *
   * <p>A {@code 2xx} goes through {@link #unwrapEnvelope}; any error is logged at WARN, counted on
   * {@link CatalogueMetricNames#EXTERNAL_FETCH_ERRORS} and yields empty rows.
   *
   * @param <T> the per-row payload type inside {@code UexResponseDto.data}
   * @param endpoint UEX endpoint path
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log messages
   * @return the parsed rows; {@code data} is empty on error or an empty 200
   */
  private <T> FetchResult<T> fetchListWithOutcome(
      String endpoint,
      ParameterizedTypeReference<UexResponseDto<T>> typeRef,
      String resourceLabel) {
    log.info("Fetching all {} from UEX API", resourceLabel);
    try {
      UexResponseDto<T> body = client.get().uri(endpoint).retrieve().body(typeRef);
      return body == null
          ? FetchResult.partial(Collections.emptyList())
          : unwrapEnvelope(body, resourceLabel);
    } catch (RuntimeException e) {
      log.warn("Failed to fetch {} from UEX API", resourceLabel, e);
      recordFetchError();
      return FetchResult.partial(Collections.emptyList());
    }
  }

  /**
   * Audits a {@code 2xx} envelope and turns it into a {@link FetchResult}, logging exactly one line
   * (REQ-OBS-001).
   *
   * <p>A non-{@code ok} {@code status} logs at WARN and increments {@link
   * CatalogueMetricNames#EXTERNAL_FETCH_ERRORS} but still returns the rows; an absent {@code data}
   * or a blank status is not treated as an anomaly.
   *
   * @param <T> the per-row payload type inside {@code UexResponseDto.data}
   * @param body the decoded envelope
   * @param resourceLabel label for log messages
   * @return the envelope's rows (empty when {@code data} was absent)
   */
  private <T> FetchResult<T> unwrapEnvelope(@NotNull UexResponseDto<T> body, String resourceLabel) {
    String status = LogSafe.text(body.status(), MAX_STATUS_LOG_LENGTH);
    List<T> rows = body.data() == null ? Collections.emptyList() : body.data();
    if (body.status() != null
        && !body.status().isBlank()
        && !ENVELOPE_STATUS_OK.equalsIgnoreCase(body.status().trim())) {
      log.warn(
          "UEX API answered 200 for {} with envelope status '{}' instead of '{}' ({} row(s)"
              + " received) — importing them anyway, but the payload may be partial.",
          resourceLabel,
          status,
          ENVELOPE_STATUS_OK,
          rows.size());
      recordFetchError();
      return FetchResult.partial(rows);
    }
    if (body.data() == null) {
      log.info(
          "Fetched 0 {} from UEX API: the upstream reported no matches at all (envelope status"
              + " '{}', data null) — an empty result set, not a failure; the local catalogue is"
              + " left untouched.",
          resourceLabel,
          status);
      return FetchResult.of(rows);
    }
    log.info(
        "Fetched {} {} from UEX API (envelope status '{}').", rows.size(), resourceLabel, status);
    return FetchResult.of(rows);
  }

  /**
   * Increments {@link CatalogueMetricNames#EXTERNAL_FETCH_ERRORS} for the {@code uex} source.
   * Called from every branch that swallows an upstream transport, decode or envelope failure into
   * an empty / short list, so a sustained UEX problem is visible even though the sync services
   * treat the payload as a skip and the scheduled job still records a success (REQ-OBS-011).
   */
  private void recordFetchError() {
    meterRegistry
        .counter(
            CatalogueMetricNames.EXTERNAL_FETCH_ERRORS,
            CatalogueMetricNames.TAG_SOURCE,
            CatalogueMetricNames.SOURCE_UEX,
            MetricNames.TAG_REASON,
            CatalogueMetricNames.REASON_FETCH)
        .increment();
  }

  /**
   * Outcome of one UEX list fetch: the rows and whether the call got a usable answer.
   *
   * <p>Only a {@link #complete()} result may drive a tombstone sweep (REQ-DATA-014), since failures
   * also yield empty rows.
   *
   * @param <T> the per-row payload type
   * @param data the parsed rows, or an empty list on an empty 200 or an error
   * @param complete {@code true} iff the upstream gave a usable answer
   */
  public record FetchResult<T>(List<T> data, boolean complete) {

    /**
     * The healthy outcome of a {@code 2xx} whose envelope reported no problem, including empty
     * rows.
     *
     * @param <T> the per-row payload type
     * @param data the parsed rows (possibly empty)
     * @return a result with {@code complete == true}
     */
    @NotNull
    public static <T> FetchResult<T> of(List<T> data) {
      return new FetchResult<>(data, true);
    }

    /**
     * The outcome of a call that cannot vouch for its rows (a failure or a non-{@code ok}
     * envelope); upserts may proceed, sweeps must not.
     *
     * @param <T> the per-row payload type
     * @param data whatever rows arrived (possibly empty)
     * @return a result with {@code complete == false}
     */
    @NotNull
    public static <T> FetchResult<T> partial(List<T> data) {
      return new FetchResult<>(data, false);
    }
  }
}
