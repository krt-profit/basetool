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
import de.greluc.krt.profit.basetool.backend.support.LogSafe;
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
 * <p>Every public {@code get…()} method delegates to the shared {@link #fetchListWithOutcome}
 * helper which adds {@code If-None-Match} to the request when a previous ETag is known for the same
 * endpoint (M-5 from the performance audit), unwraps the {@code UexResponseDto<T>} envelope,
 * applies a 30-second per-call timeout, and on ANY error or {@code 304 Not Modified} returns an
 * empty list — the calling sync services treat an empty payload as "skip this run" and explicitly
 * never wipe local tables based on it, so a transient outage or a feed that has not changed since
 * the last sync both result in the same no-op behaviour.
 *
 * <p><b>Empty is not one thing (H6).</b> Because that empty list used to be the <em>only</em> thing
 * a caller saw, every sync service greeted an unchanged (304) feed with the same alarming {@code
 * WARN No X received from UEX API. Aborting…} it emits for a broken one — and a malformed envelope
 * threw no exception, so it left no {@code basetool_external_fetch_errors_total} trail either. The
 * getters therefore return {@link FetchResult}, not a bare {@code List}: the {@code notModified}
 * flag lets a sync log an unchanged feed at INFO, and this client now reports a non-{@code ok}
 * envelope {@code status} itself, at WARN and on the error counter. {@link #getRefineriesMethods()}
 * and {@link #getRefineriesYields()} still return plain lists — their caller has not been migrated
 * yet.
 *
 * <p><b>{@code data: null} is UEX's empty result set, not a fault.</b> The envelope audit
 * originally also counted a {@code 200} whose {@code data} was absent, on the assumption that it
 * could only mean a renamed field or an error document dressed as a success. Observation of the
 * live API disproves that: UEX answers a query that legitimately matches nothing with {@code
 * {"status":"ok","http_code":200,"data":null}}, and signals a genuine rejection the other way round
 * — {@code {"status":"requires_id_category_or_id_company_or_uuid","http_code":400,"data":[]}}, an
 * empty <em>array</em> under a non-2xx code. Errors carry {@code []}; empty successes carry {@code
 * null}. Two permanently empty item categories (12 {@code Clothing/Jumpsuits} and 69 {@code
 * Consumable/Consumable}) therefore booked two bogus {@code
 * basetool_external_fetch_errors_total{source=uex}} increments on <em>every</em> sync run, which is
 * what fired {@code ExternalFetchErrors} in production on 2026-08-03: the per-run baseline of 2 is
 * under the rule's {@code > 3} threshold, but the counter resets on every backend restart and
 * {@code increase()} adds the pre-reset segment back, so two restarts inside the 6 h window summed
 * to 4. {@link #unwrapEnvelope} consequently keys the audit off {@code status}, the field UEX
 * actually uses to self-report, and treats an absent {@code data} under an {@code ok} status as
 * zero rows.
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
   * @return the commodities plus the {@code 304 Not Modified} outcome; {@code data} is empty on
   *     error / 304 / empty-200
   */
  public FetchResult<UexCommodityDto> getCommodities() {
    return fetchListWithOutcome(
        uexProperties.getCommoditiesEndpoint(),
        new ParameterizedTypeReference<>() {},
        "commodities");
  }

  /**
   * Fetches the full UEX commodity-price matrix (every commodity × every terminal). This is the
   * largest UEX payload by far ({@literal >}1 MB) — see the {@code maxInMemorySize} in {@link
   * #initClient()}.
   *
   * @return the commodity prices plus the {@code 304 Not Modified} outcome; {@code data} is empty
   *     on error / 304 / empty-200
   */
  public FetchResult<UexCommodityPriceDto> getCommoditiesPricesAll() {
    return fetchListWithOutcome(
        uexProperties.getCommoditiesPricesEndpoint(),
        new ParameterizedTypeReference<>() {},
        "commodities prices");
  }

  /**
   * Fetches the full UEX item-price matrix ({@code /items_prices_all}, ~24 000 rows, {@literal >}1
   * MB — covered by the {@code maxInMemorySize} in {@link #initClient()}). Drives the R7 {@code
   * UexItemPriceSyncService}, which is feature-flagged off by default.
   *
   * @return the item prices plus the {@code 304 Not Modified} outcome; {@code data} is empty on
   *     error / 304 / empty-200
   */
  public FetchResult<UexItemPriceDto> getItemPrices() {
    return fetchListWithOutcome(
        uexProperties.getItemsPricesEndpoint(),
        new ParameterizedTypeReference<>() {},
        "item prices");
  }

  /**
   * Returns all star systems plus the {@code 304 Not Modified} outcome.
   *
   * @return the star systems plus the {@code 304 Not Modified} outcome; {@code data} is empty on
   *     error / 304 / empty-200
   */
  public FetchResult<UexStarSystemDto> getStarSystems() {
    return fetchListWithOutcome(
        uexProperties.getStarSystemsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "star systems");
  }

  /**
   * Returns all companies (in-universe manufacturers) plus the {@code 304 Not Modified} outcome.
   *
   * @return the companies plus the {@code 304 Not Modified} outcome; {@code data} is empty on error
   *     / 304 / empty-200
   */
  public FetchResult<UexCompanyDto> getCompanies() {
    return fetchListWithOutcome(
        uexProperties.getCompaniesEndpoint(), new ParameterizedTypeReference<>() {}, "companies");
  }

  /**
   * Returns all vehicles (ships and ground vehicles) plus the {@code 304 Not Modified} outcome.
   *
   * @return the vehicles plus the {@code 304 Not Modified} outcome; {@code data} is empty on error
   *     / 304 / empty-200
   */
  public FetchResult<UexVehicleDto> getVehicles() {
    return fetchListWithOutcome(
        uexProperties.getVehiclesEndpoint(), new ParameterizedTypeReference<>() {}, "vehicles");
  }

  /**
   * Returns all cities plus the {@code 304 Not Modified} outcome.
   *
   * @return the cities plus the {@code 304 Not Modified} outcome; {@code data} is empty on error /
   *     304 / empty-200
   */
  public FetchResult<UexCityDto> getCities() {
    return fetchListWithOutcome(
        uexProperties.getCitiesEndpoint(), new ParameterizedTypeReference<>() {}, "cities");
  }

  /**
   * Returns all in-universe factions plus the {@code 304 Not Modified} outcome.
   *
   * @return the factions plus the {@code 304 Not Modified} outcome; {@code data} is empty on error
   *     / 304 / empty-200
   */
  public FetchResult<UexFactionDto> getFactions() {
    return fetchListWithOutcome(
        uexProperties.getFactionsEndpoint(), new ParameterizedTypeReference<>() {}, "factions");
  }

  /**
   * Returns all jurisdictions (legal authorities covering a system or region) plus the {@code 304
   * Not Modified} outcome.
   *
   * @return the jurisdictions plus the {@code 304 Not Modified} outcome; {@code data} is empty on
   *     error / 304 / empty-200
   */
  public FetchResult<UexJurisdictionDto> getJurisdictions() {
    return fetchListWithOutcome(
        uexProperties.getJurisdictionsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "jurisdictions");
  }

  /**
   * Returns all moons plus the {@code 304 Not Modified} outcome.
   *
   * @return the moons plus the {@code 304 Not Modified} outcome; {@code data} is empty on error /
   *     304 / empty-200
   */
  public FetchResult<UexMoonDto> getMoons() {
    return fetchListWithOutcome(
        uexProperties.getMoonsEndpoint(), new ParameterizedTypeReference<>() {}, "moons");
  }

  /**
   * Returns all orbital locations plus the {@code 304 Not Modified} outcome.
   *
   * @return the orbits plus the {@code 304 Not Modified} outcome; {@code data} is empty on error /
   *     304 / empty-200
   */
  public FetchResult<UexOrbitDto> getOrbits() {
    return fetchListWithOutcome(
        uexProperties.getOrbitsEndpoint(), new ParameterizedTypeReference<>() {}, "orbits");
  }

  /**
   * Returns all outposts plus the {@code 304 Not Modified} outcome.
   *
   * @return the outposts plus the {@code 304 Not Modified} outcome; {@code data} is empty on error
   *     / 304 / empty-200
   */
  public FetchResult<UexOutpostDto> getOutposts() {
    return fetchListWithOutcome(
        uexProperties.getOutpostsEndpoint(), new ParameterizedTypeReference<>() {}, "outposts");
  }

  /**
   * Returns all planets plus the {@code 304 Not Modified} outcome.
   *
   * @return the planets plus the {@code 304 Not Modified} outcome; {@code data} is empty on error /
   *     304 / empty-200
   */
  public FetchResult<UexPlanetDto> getPlanets() {
    return fetchListWithOutcome(
        uexProperties.getPlanetsEndpoint(), new ParameterizedTypeReference<>() {}, "planets");
  }

  /**
   * Returns all points of interest (Lagrange points, derelicts, anomalies) plus the {@code 304 Not
   * Modified} outcome.
   *
   * @return the points of interest plus the {@code 304 Not Modified} outcome; {@code data} is empty
   *     on error / 304 / empty-200
   */
  public FetchResult<UexPoiDto> getPoi() {
    return fetchListWithOutcome(
        uexProperties.getPoiEndpoint(), new ParameterizedTypeReference<>() {}, "pois");
  }

  /**
   * Returns all space stations plus the {@code 304 Not Modified} outcome.
   *
   * @return the space stations plus the {@code 304 Not Modified} outcome; {@code data} is empty on
   *     error / 304 / empty-200
   */
  public FetchResult<UexSpaceStationDto> getSpaceStations() {
    return fetchListWithOutcome(
        uexProperties.getSpaceStationsEndpoint(),
        new ParameterizedTypeReference<>() {},
        "spacestations");
  }

  /**
   * Returns all terminals (trade kiosks at any location type) plus the {@code 304 Not Modified}
   * outcome.
   *
   * @return the terminals plus the {@code 304 Not Modified} outcome; {@code data} is empty on error
   *     / 304 / empty-200
   */
  public FetchResult<UexTerminalDto> getTerminals() {
    return fetchListWithOutcome(
        uexProperties.getTerminalsEndpoint(), new ParameterizedTypeReference<>() {}, "terminals");
  }

  /**
   * Fetches all refining methods (e.g. {@code Cormack}, {@code Pyrometric}, …). Drives the local
   * refining-method catalog used by the refinery-order pricing.
   *
   * <p>Still returns a bare list: {@code UexRefinerySyncService} has not been migrated to the
   * {@link FetchResult} outcome, so an unchanged (304) refinery feed still reads as "no data" at
   * its call site. The envelope-level WARNs added in {@link #unwrapEnvelope} do fire for it.
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
   * <p>Bare-list caveat as for {@link #getRefineriesMethods()}.
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
   * @return the categories plus the {@code 304 Not Modified} outcome; {@code data} is empty on
   *     error / 304 / empty-200
   */
  public FetchResult<UexCategoryDto> getCategories() {
    return fetchListWithOutcome(
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
   * <p>Unlike the other endpoints this returns the full {@link FetchResult} rather than a bare
   * list: {@code UexItemSyncService} needs the {@link FetchResult#notModified() notModified} flag
   * to tell a healthy unchanged catalogue (empty {@code data}, served as {@code 304}) apart from an
   * empty-200 catalogue outage (empty {@code data}, {@code notModified = false}). Both produce the
   * same zero-upsert run, but only the outage must trip the {@code SyncZeroItems} alert (#1041 item
   * 2).
   *
   * @param categoryId UEX integer category id (from {@code /categories[].id})
   * @return the items in this category plus the {@code 304} outcome; {@code data} is empty on error
   *     / 304 / empty-200
   */
  public FetchResult<UexItemDto> getItemsForCategory(int categoryId) {
    String endpoint = uexProperties.getItemsEndpoint() + "?id_category=" + categoryId;
    return fetchListWithOutcome(
        endpoint, new ParameterizedTypeReference<>() {}, "items (category=" + categoryId + ")");
  }

  /**
   * Thin {@code List}-returning wrapper over {@link #fetchListWithOutcome} for the two refinery
   * endpoints, whose caller still consumes a bare list. See that method for the full
   * conditional-GET and error/empty-list fallback contract. Note that discarding the outcome is
   * exactly what made a healthy {@code 304} indistinguishable from a broken feed at the call site
   * (H6) — do not route new endpoints through here.
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
    return fetchListWithOutcome(endpoint, typeRef, resourceLabel).data();
  }

  /**
   * Shared request pipeline for every UEX list endpoint. Implements the conditional GET (M-5)
   * behaviour and the unified error / empty-list fallback, returning both the parsed rows and
   * whether the upstream answered {@code 304 Not Modified}.
   *
   * <ol>
   *   <li>If we have a previous ETag for {@code endpoint}, attach it as {@code If-None-Match}.
   *   <li>If the response is {@code 304 Not Modified}, log the unchanged feed at INFO and return an
   *       empty list flagged {@code notModified = true} — sync services treat this as "skip this
   *       run" but must NOT treat it as an empty catalogue (the unchanged feed is healthy, not an
   *       outage). INFO, not DEBUG: DEBUG is off in production, which is precisely why an all-304
   *       night left nothing but the callers' alarming "no data" WARNs behind.
   *   <li>On {@code 2xx}, store the response's ETag (if any) keyed by endpoint URL for the next
   *       call, then deserialise the body into {@code UexResponseDto<T>} and hand it to {@link
   *       #unwrapEnvelope} for the row count / status audit ({@code notModified = false}).
   *   <li>On non-2xx (and non-304) responses, propagate the {@link
   *       org.springframework.web.reactive.function.client.WebClientResponseException} through
   *       {@code .createError()} into the unified {@code onErrorResume} fallback.
   *   <li>On any error (timeout, decoding failure, server error), log at WARN, count it on {@link
   *       MetricNames#EXTERNAL_FETCH_ERRORS} and return an empty list flagged {@code notModified =
   *       false} — the caller MUST treat the result as "skip this run", not as "the table is
   *       empty".
   * </ol>
   *
   * @param <T> the per-row payload type inside {@code UexResponseDto.data}
   * @param endpoint UEX endpoint path (e.g. {@code /commodities}); also serves as the cache key
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel human-readable label for log messages (singular/plural to taste)
   * @return the parsed rows plus the {@code 304} outcome; {@code data} is empty on error / 304 /
   *     empty-200
   */
  private <T> FetchResult<T> fetchListWithOutcome(
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
                log.info(
                    "Fetched 0 {} from UEX API: unchanged since the last sync (304 Not Modified) —"
                        + " nothing to re-import.",
                    resourceLabel);
                return Mono.just(FetchResult.<T>unchanged());
              }
              if (!response.statusCode().is2xxSuccessful()) {
                return response.createError();
              }
              String etag = response.headers().asHttpHeaders().getETag();
              if (etag != null && !etag.isBlank()) {
                etagByEndpoint.put(endpoint, etag);
              }
              return response.bodyToMono(typeRef).map(body -> unwrapEnvelope(body, resourceLabel));
            })
        .timeout(CALL_TIMEOUT)
        .onErrorResume(
            e -> {
              log.warn("Failed to fetch {} from UEX API", resourceLabel, e);
              recordFetchError();
              return Mono.just(FetchResult.<T>partial(Collections.emptyList()));
            })
        .blockOptional()
        .orElse(FetchResult.partial(Collections.emptyList()));
  }

  /**
   * Audits a {@code 2xx} envelope and turns it into a {@link FetchResult}, emitting exactly one log
   * line for the outcome (REQ-OBS-001): WARN plus a {@link MetricNames#EXTERNAL_FETCH_ERRORS}
   * increment when the envelope declares a non-{@code ok} {@code status}, INFO otherwise — with the
   * row count on a populated payload, and a distinct "no matches at all" line when {@code data} was
   * absent.
   *
   * <p>The self-declared failure used to be invisible: {@code status} had no reader anywhere, so an
   * upstream that admitted a problem in the envelope was simply parsed and discarded. It now warns
   * and counts. The rows are still returned — this client is fail-soft, and the sync services never
   * wipe local tables from a short payload.
   *
   * <p><b>An absent {@code data} is not an anomaly</b>, because UEX uses exactly that to express an
   * empty result set ({@code {"status":"ok","http_code":200,"data":null}}) while expressing a
   * genuine rejection as an empty <em>array</em> under a non-2xx code — which never reaches this
   * method, since {@link #fetchListWithOutcome} routes every non-2xx into {@code createError()} and
   * the counting fallback. Counting {@code null} data as a fetch error therefore only ever booked
   * false positives: see the class Javadoc for the two permanently empty item categories that made
   * {@code ExternalFetchErrors} fire on 2026-08-03. The catalogue-wide failure this branch was
   * meant to catch — upstream renaming or dropping the field on every endpoint — is covered by
   * {@code SyncZeroItems}, which trips when successful runs process zero items, and unlike this
   * branch it cannot be fooled by a single legitimately empty category.
   *
   * <p>A {@code null} / blank status is likewise deliberately <em>not</em> an anomaly. No code read
   * the field before, so we cannot claim to know that every one of the ~20 endpoints populates it;
   * treating its absence as a fault would risk a WARN on every endpoint of every sweep — a log
   * flood — to report something we have never observed.
   *
   * @param <T> the per-row payload type inside {@code UexResponseDto.data}
   * @param body the decoded envelope
   * @param resourceLabel human-readable label for log messages
   * @return the envelope's rows (empty when {@code data} was absent), flagged {@code notModified =
   *     false}
   */
  private <T> FetchResult<T> unwrapEnvelope(UexResponseDto<T> body, String resourceLabel) {
    String status = LogSafe.text(body.status(), MAX_STATUS_LOG_LENGTH);
    // Absent data is UEX's empty result set, so it cannot carry the verdict — normalise it away and
    // let `status`, the only field the upstream uses to self-report, decide whether this is a
    // fault.
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
      // Self-reported trouble: the rows are handed on so upserts still run, but the result cannot
      // vouch for the endpoint's full contents, so no caller may sweep on it (REQ-DATA-014).
      return FetchResult.partial(rows);
    }
    if (body.data() == null) {
      log.info(
          "Fetched 0 {} from UEX API: the upstream reported no matches at all (envelope status"
              + " '{}', data null) — an empty result set, not a failure; the local catalogue is"
              + " left untouched.",
          resourceLabel,
          status);
      // A `data: null` under an ok status is UEX saying "nothing matches" — an answer, so this is
      // a COMPLETE result whose row list happens to be empty (two item categories are really
      // empty). Only a failure to get an answer at all is incomplete.
      return FetchResult.of(rows);
    }
    log.info(
        "Fetched {} {} from UEX API (envelope status '{}').", rows.size(), resourceLabel, status);
    return FetchResult.of(rows);
  }

  /**
   * Increments {@link MetricNames#EXTERNAL_FETCH_ERRORS} for the {@code uex} source. Called from
   * every branch that swallows an upstream transport, decode or envelope failure into an empty /
   * short list, so a sustained UEX problem is visible even though the sync services treat the
   * payload as a skip and the scheduled job still records a success (REQ-OBS-011).
   */
  private void recordFetchError() {
    meterRegistry
        .counter(MetricNames.EXTERNAL_FETCH_ERRORS, MetricNames.TAG_SOURCE, MetricNames.SOURCE_UEX)
        .increment();
  }

  /**
   * Outcome of a single UEX list fetch: the parsed rows, whether the upstream answered {@code 304
   * Not Modified} (the feed is unchanged since the last sync, served from the {@link
   * #etagByEndpoint} conditional-GET cache), and whether this call can vouch for what it returned.
   *
   * <p>The {@code notModified} flag lets a caller tell a healthy unchanged catalogue ({@code data}
   * empty, {@code notModified = true}) apart from a genuine empty-200 outage or a swallowed fetch
   * error ({@code data} empty, {@code notModified = false}) — the distinction that keeps the {@code
   * SyncZeroItems} alert from firing on a fully-cached UEX item sync.
   *
   * <p>{@link #complete()} answers the separate, stricter question: <em>did this call actually get
   * an answer?</em> It is {@code false} on a transport / non-2xx / decode failure, on an envelope
   * whose {@code status} is not {@code ok}, and on a {@code 304} (which enumerates nothing). It
   * matters because every one of those degrades to an <b>empty list</b>, which is exactly what a
   * legitimately empty endpoint returns — and two of the UEX item categories really are empty. A
   * caller that sweeps rows "the feed no longer returns" therefore cannot use the row list alone:
   * before this flag existed, a single 5xx on one of the ~50 per-category item calls made the whole
   * category look deleted and soft-deleted every one of its rows until the next healthy run
   * (REQ-DATA-014). Mirrors {@code ScWikiClient.FetchResult.complete()}.
   *
   * @param <T> the per-row payload type
   * @param data the parsed rows, or an empty list on {@code 304} / empty-200 / error
   * @param notModified {@code true} only when the upstream returned {@code 304 Not Modified}
   * @param complete {@code true} iff this call got a usable answer from the upstream and its rows
   *     may therefore drive a tombstone sweep
   */
  public record FetchResult<T>(List<T> data, boolean notModified, boolean complete) {

    /**
     * The healthy outcome: a {@code 2xx} whose envelope did not report a problem. A genuinely empty
     * {@code data} belongs here too — "the endpoint has no rows" is an answer.
     *
     * @param <T> the per-row payload type
     * @param data the parsed rows (possibly empty)
     * @return a result carrying {@code data} with {@code notModified == false, complete == true}
     */
    public static <T> FetchResult<T> of(List<T> data) {
      return new FetchResult<>(data, false, true);
    }

    /**
     * The outcome of a call that could not vouch for its result — a transport / non-2xx / decode
     * failure (empty rows) or an envelope that self-reported a non-{@code ok} status (whatever rows
     * came with it). The rows are still returned so upserts can proceed; only sweeps stand down.
     *
     * @param <T> the per-row payload type
     * @param data whatever rows arrived (possibly empty)
     * @return a result carrying {@code data} with {@code notModified == false, complete == false}
     */
    public static <T> FetchResult<T> partial(List<T> data) {
      return new FetchResult<>(data, false, false);
    }

    /**
     * The {@code 304 Not Modified} outcome: empty rows, {@code notModified == true}. Not complete —
     * a conditional-GET hit enumerates nothing, so a caller that ignored {@link #notModified()}
     * still cannot sweep on it.
     *
     * @param <T> the per-row payload type
     * @return an unchanged, incomplete result with an empty row list
     */
    public static <T> FetchResult<T> unchanged() {
      return new FetchResult<>(List.of(), true, false);
    }
  }
}
