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
import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemCatalogueMetadata;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiIdentityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiMetaDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiRow;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.ResponseSizeLimitInterceptor;
import de.greluc.krt.profit.basetool.backend.platform.api.RestClientConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Reads complete, version-pinned SC Wiki catalogue snapshots with bounded HTTP retries. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConcurrencyLimit(ScWikiClient.MAX_CONCURRENT_CALLS)
public class ScWikiClient {

  /** Item relationships included in both list and detail metadata snapshots. */
  public static final String ITEM_METADATA_INCLUDES = "related_items,variants,vehicles";

  /** Largest item page supported by the Wiki. */
  private static final int MAX_ITEM_PAGE_SIZE = 200;

  /** Largest vehicle page supported by the Wiki. */
  private static final int MAX_VEHICLE_PAGE_SIZE = 50;

  /**
   * Most calls in flight at once through this client; further callers wait. Equal to the SC Wiki
   * executor's maximum pool size.
   */
  static final int MAX_CONCURRENT_CALLS = 2;

  /**
   * Largest response body one Wiki call may deliver (16 MiB), the ceiling the reactive codec used
   * to enforce. See {@link #initClient()} for why it is a backstop and not a comfortable margin.
   */
  static final long MAX_RESPONSE_BYTES = 16L * 1024 * 1024;

  /**
   * A fresh, observed builder for external hosts from {@code RestClientConfig} (prototype-scoped),
   * so its base URL remains local to this client. Each HTTP attempt has the external request
   * factory's 30-second read timeout; retries have a separately bounded attempt and delay budget.
   */
  @Qualifier(RestClientConfig.EXTERNAL_REST_CLIENT_BUILDER)
  private final RestClient.Builder restClientBuilder;

  /** The {@code app.scwiki.*} configuration: base URL, page sizes, pacing and game version. */
  private final ScWikiProperties properties;

  /**
   * Micrometer registry for the swallowed-fetch-error counter ({@link
   * CatalogueMetricNames#EXTERNAL_FETCH_ERRORS}). Injected so a network or parse failure — which
   * the fetch helpers below map to {@code null} / empty — still leaves a metric trail
   * (REQ-OBS-011).
   */
  private final MeterRegistry meterRegistry;

  /** Durable counters for the active catalogue run. */
  private final SyncRunProgress syncRunProgress;

  /** Reusable client bound to the Wiki base URL. Built once after dependency injection. */
  private RestClient client;

  /** Version selected once for the current thread's sync run. */
  private final ThreadLocal<String> runVersion = new ThreadLocal<>();

  /** Shared request pacing and retry policy. */
  private ScWikiRequestExecutor requests;

  /**
   * Jackson mapper that binds pages and details. It reads an empty JSON array as an absent object,
   * because the Wiki serialises an empty object field such as {@code quality_range} as {@code []}.
   */
  private final ObjectMapper objectMapper =
      JsonMapper.builder().enable(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT).build();

  /**
   * Builds the {@link RestClient} once after dependency injection, with the {@link
   * #MAX_RESPONSE_BYTES} response-body cap. A response over the cap fails the decode and yields an
   * empty result.
   */
  @PostConstruct
  void initClient() {
    this.client =
        restClientBuilder
            .baseUrl(properties.apiUrl())
            .requestInterceptor(new ResponseSizeLimitInterceptor(MAX_RESPONSE_BYTES))
            .build();
    requests = new ScWikiRequestExecutor(client, properties);
  }

  /**
   * Records a transport, decode or status failure on {@link
   * CatalogueMetricNames#EXTERNAL_FETCH_ERRORS} with {@link CatalogueMetricNames#REASON_FETCH}, at
   * most once per fetch (REQ-OBS-011).
   *
   * @param latch the current fetch's latch; only the first call through it records
   */
  private void recordFetchErrorOnce(FetchErrorLatch latch) {
    recordOnce(latch, CatalogueMetricNames.REASON_FETCH);
  }

  /**
   * Records an incomplete page-walk census on {@link CatalogueMetricNames#EXTERNAL_FETCH_ERRORS}
   * with {@link CatalogueMetricNames#REASON_CENSUS}, at most once per fetch and not at all after a
   * fetch failure the same walk already recorded (REQ-OBS-011).
   *
   * @param latch the current fetch's latch; only the first call through it records
   */
  private void recordCensusIncompleteOnce(FetchErrorLatch latch) {
    recordOnce(latch, CatalogueMetricNames.REASON_CENSUS);
  }

  /**
   * Claims the latch and increments the counter under the given {@code reason}.
   *
   * @param latch the current fetch's latch
   * @param reason the {@link MetricNames#TAG_REASON} value to record
   */
  private void recordOnce(FetchErrorLatch latch, String reason) {
    if (!latch.claim()) {
      return;
    }
    meterRegistry
        .counter(
            CatalogueMetricNames.EXTERNAL_FETCH_ERRORS,
            CatalogueMetricNames.TAG_SOURCE,
            CatalogueMetricNames.SOURCE_SCWIKI,
            MetricNames.TAG_REASON,
            reason)
        .increment();
  }

  /**
   * Overload of {@link #fetchAllPages(String, ParameterizedTypeReference, String, String)} without
   * an {@code include=} parameter.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/commodities"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @return merged rows across all pages, or an empty list on error
   */
  public <T extends ScWikiRow> List<T> fetchAllPages(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel) {
    return fetchAllPages(endpoint, typeRef, resourceLabel, null);
  }

  /**
   * Overload of {@link #fetchAllPages(String, ParameterizedTypeReference, String, String, Map)}
   * without query filters.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/commodities"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @param include optional {@code ?include=…} value; {@code null} or blank means none
   * @return merged rows across all pages, or an empty list on error
   */
  public <T extends ScWikiRow> List<T> fetchAllPages(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      @Nullable String include) {
    return fetchAllPages(endpoint, typeRef, resourceLabel, include, null);
  }

  /**
   * Returns only the merged rows of {@link #fetchAllPagesResult(String, ParameterizedTypeReference,
   * String, String, Map)}, dropping the census-completeness verdict.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @param include optional {@code ?include=…} value; {@code null} or blank means none
   * @param filters optional {@code filter[<key>]=<value>} pairs; {@code null} or empty means none
   * @return merged rows across all pages, or an empty list on error
   */
  public <T extends ScWikiRow> List<T> fetchAllPages(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      @Nullable String include,
      @Nullable Map<String, String> filters) {
    return fetchAllPagesResult(endpoint, typeRef, resourceLabel, include, filters).data();
  }

  /**
   * Overload of {@link #fetchAllPagesResult(String, ParameterizedTypeReference, String, String,
   * Map)} without {@code include} and without filters.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/commodities"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @return the merged rows plus the census-completeness verdict
   */
  public <T extends ScWikiRow> FetchResult<T> fetchAllPagesResult(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel) {
    return fetchAllPagesResult(endpoint, typeRef, resourceLabel, null, null);
  }

  /**
   * Walks every page of a paginated Wiki endpoint and returns the merged {@code data[]} with a
   * {@code complete} census flag.
   *
   * <p>Every request is paced and fetched unconditionally; an error returns the rows gathered so
   * far. The result is incomplete when a page fails, pagination metadata is missing on a full page
   * 1, a row repeats, the distinct rows fall short of {@code meta.total}, or the page count grows
   * during the walk; a surplus over {@code meta.total} is not a failure. Each non-blank filter is
   * sent as {@code filter[<key>]=<value>}.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/armor"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @param include optional {@code ?include=…} value; {@code null} or blank means none
   * @param filters optional {@code filter[<key>]=<value>} pairs in a stable iteration order; {@code
   *     null} or empty means none, blank values are skipped
   * @return the merged rows plus the census-completeness verdict
   */
  public <T extends ScWikiRow> FetchResult<T> fetchAllPagesResult(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      @Nullable String include,
      @Nullable Map<String, String> filters) {
    return fetchAllPagesResult(endpoint, typeRef, resourceLabel, include, filters, null);
  }

  /**
   * Variant of {@link #fetchAllPagesResult(String, ParameterizedTypeReference, String, String,
   * Map)} with a per-endpoint {@code page[size]} override for endpoints with heavy rows.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @param include optional {@code ?include=…} value; {@code null} or blank means none
   * @param filters optional {@code filter[<key>]=<value>} pairs; {@code null} or empty means none
   * @param pageSizeOverride rows per page, or {@code null} / non-positive for the configured
   *     default
   * @return the merged rows plus the census-completeness verdict
   */
  public <T extends ScWikiRow> FetchResult<T> fetchAllPagesResult(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      @Nullable String include,
      @Nullable Map<String, String> filters,
      @Nullable Integer pageSizeOverride) {
    try (SyncRun _ = openSyncRun()) {
      return fetchPagesWithinRun(
          endpoint, typeRef, resourceLabel, include, filters, pageSizeOverride);
    }
  }

  /**
   * Enumerates one endpoint under the calling run's immutable game version.
   *
   * @param <T> row type
   * @param endpoint endpoint path
   * @param typeRef paginated response type
   * @param resourceLabel log label
   * @param include eager-loaded relationships
   * @param filters endpoint filters
   * @param pageSizeOverride optional page size
   * @return rows and census completeness
   */
  private <T extends ScWikiRow> FetchResult<T> fetchPagesWithinRun(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      @Nullable String include,
      @Nullable Map<String, String> filters,
      @Nullable Integer pageSizeOverride) {
    log.info("Fetching all {} from SC Wiki API (paginated)", resourceLabel);

    int pageSize = effectivePageSize(pageSizeOverride);
    String firstPageUri = buildPagedUri(endpoint, 1, include, filters, pageSize);
    FetchErrorLatch errorLatch = new FetchErrorLatch();

    ScWikiResponseDto<T> first =
        fetchSinglePage(firstPageUri, typeRef, resourceLabel, include, errorLatch);
    if (first == null) {
      syncRunProgress.fetched(0, 0, false);
      return FetchResult.partial(Collections.emptyList());
    }

    List<T> accumulated = new ArrayList<>();
    if (first.data() != null) {
      accumulated.addAll(first.data());
    }

    ScWikiMetaDto meta = first.meta();
    ScWikiMetaDto freshestMeta = meta;
    boolean complete = true;
    boolean walkAbandoned = false;
    int lastPage = 1;
    if (meta == null || meta.lastPage() == null) {
      if (isFullPage(accumulated.size(), pageSize)) {
        log.warn(
            "SC Wiki {} returned no pagination metadata (meta.last_page absent) while page 1 came"
                + " back full at {} row(s) — treating the page walk as INCOMPLETE; later pages were"
                + " never requested and must not be mistaken for deleted rows.",
            resourceLabel,
            accumulated.size());
        recordCensusIncompleteOnce(errorLatch);
        complete = false;
      }
    } else {
      lastPage = Math.max(1, meta.lastPage());
    }

    int pagesFetched = 1;
    for (int page = 2; page <= lastPage; page++) {
      String pageUri = buildPagedUri(endpoint, page, include, filters, pageSize);
      ScWikiResponseDto<T> next =
          fetchSinglePage(pageUri, typeRef, resourceLabel, include, errorLatch);
      if (next == null) {
        log.warn(
            "Page {} of {} failed mid-pagination; returning an INCOMPLETE result of {} row(s).",
            page,
            resourceLabel,
            accumulated.size());
        recordFetchErrorOnce(errorLatch);
        complete = false;
        walkAbandoned = true;
        break;
      }
      pagesFetched++;
      if (next.meta() != null) {
        freshestMeta = next.meta();
      }
      if (next.data() != null) {
        accumulated.addAll(next.data());
      }
    }

    int distinctRows = countDistinctRows(accumulated);
    int repeatedRows = accumulated.size() - distinctRows;
    if (repeatedRows > 0) {
      log.warn(
          "SC Wiki {} page walk merged {} row(s) but only {} distinct one(s) — the feed was"
              + " re-paginated mid-walk, so {} row(s) came back twice and an unknown number never"
              + " came back at all; treating the result as INCOMPLETE.",
          resourceLabel,
          accumulated.size(),
          distinctRows,
          repeatedRows);
      recordCensusIncompleteOnce(errorLatch);
      complete = false;
    }

    Integer announcedTotal = meta == null ? null : meta.total();
    if (announcedTotal != null && distinctRows < announcedTotal) {
      log.warn(
          "SC Wiki {} page walk enumerated {} distinct row(s) but meta.total reports {} — {} row(s)"
              + " are unaccounted for; treating the result as INCOMPLETE, the accumulated rows are"
              + " not a full census of the feed.",
          resourceLabel,
          distinctRows,
          announcedTotal,
          announcedTotal - distinctRows);
      recordCensusIncompleteOnce(errorLatch);
      complete = false;
    } else if (announcedTotal != null && distinctRows > announcedTotal) {
      log.info(
          "SC Wiki {} page walk enumerated {} distinct row(s) while meta.total reports {} — every"
              + " announced page was fetched and no row came back twice, so the surplus is an"
              + " upstream count under-reporting its own feed (or rows added mid-walk), not a gap;"
              + " the census stands.",
          resourceLabel,
          distinctRows,
          announcedTotal);
    }

    if (!walkAbandoned
        && freshestMeta != null
        && freshestMeta.lastPage() != null
        && freshestMeta.lastPage() > lastPage) {
      log.warn(
          "SC Wiki {} announced {} page(s) on page 1 but {} by the end of the walk — the {} page(s)"
              + " past the original bound were never requested; treating the result as INCOMPLETE.",
          resourceLabel,
          lastPage,
          freshestMeta.lastPage(),
          freshestMeta.lastPage() - lastPage);
      recordCensusIncompleteOnce(errorLatch);
      complete = false;
    }

    long validRows =
        accumulated.stream()
            .filter(row -> row != null && row.uuid() != null)
            .map(ScWikiRow::uuid)
            .distinct()
            .count();
    if (validRows < distinctRows) {
      recordCensusIncompleteOnce(errorLatch);
      complete = false;
    }
    log.info(
        "Fetched {} {} ({} distinct) from SC Wiki API across {} of {} announced page(s)"
            + " (complete={}).",
        accumulated.size(),
        resourceLabel,
        distinctRows,
        pagesFetched,
        lastPage,
        complete);

    syncRunProgress.fetched(accumulated.size(), validRows, complete);
    return complete ? FetchResult.of(accumulated) : FetchResult.partial(accumulated);
  }

  /**
   * Reports whether page 1 filled the requested page size, which makes missing pagination metadata
   * suspicious.
   *
   * @param rowCount the number of rows page 1 carried
   * @param pageSize the page size the walk requested
   * @return {@code true} when the page size is positive and fully used
   */
  private boolean isFullPage(int rowCount, int pageSize) {
    return pageSize > 0 && rowCount >= pageSize;
  }

  /**
   * Resolves the {@code page[size]} a walk requests: a positive override, else the configured
   * default, else 200.
   *
   * @param pageSizeOverride the per-call override, or {@code null}
   * @return the page size to request
   */
  private int effectivePageSize(@Nullable Integer pageSizeOverride) {
    if (pageSizeOverride != null && pageSizeOverride > 0) {
      return pageSizeOverride;
    }
    Integer configured = properties.pageSize();
    return configured != null && configured > 0 ? configured : 200;
  }

  /**
   * Counts the distinct rows a page walk enumerated, keyed on {@link ScWikiRow#uuid()}; each row
   * without a UUID counts as its own row.
   *
   * @param <T> per-row payload type
   * @param rows the merged rows across every fetched page
   * @return the number of distinct UUIDs plus the number of rows without a UUID
   */
  private static <T extends ScWikiRow> int countDistinctRows(@NotNull List<T> rows) {
    Set<UUID> seenIds = HashSet.newHashSet(rows.size());
    int idlessRows = 0;
    for (T row : rows) {
      UUID id = row == null ? null : row.uuid();
      if (id == null) {
        idlessRows++;
      } else {
        seenIds.add(id);
      }
    }
    return seenIds.size() + idlessRows;
  }

  /**
   * Fetches a single Wiki resource and binds it to {@code type}, unwrapping a top-level {@code
   * data} envelope when present. No ETag caching.
   *
   * <p>The id is expanded into a {@code /{id}} template variable, so the {@code uri} tag of {@code
   * http.client.requests} stays the endpoint template instead of one value per resource.
   *
   * @param <T> the target DTO type
   * @param endpoint the Wiki endpoint path (e.g. {@code "/api/items"})
   * @param id the resource id appended as the last path segment
   * @param type the DTO class to bind the payload to
   * @param resourceLabel label for log lines
   * @return the parsed DTO, or {@code null} on 404 / error / unparseable body
   * @throws SourceIdentityMismatchException when a UUID request receives another or no UUID
   */
  @Nullable
  public <T> T fetchOne(String endpoint, Object id, Class<T> type, String resourceLabel) {
    return fetchOneResult(endpoint, id, type, resourceLabel).data();
  }

  /**
   * Fetches one version-pinned detail, distinguishing a missing asset from an unavailable response.
   *
   * @param <T> decoded resource type
   * @param endpoint source endpoint
   * @param id UUID or source slug appended to the endpoint
   * @param type decoded payload type
   * @param resourceLabel bounded diagnostic label
   * @return source outcome and its validated detail when found
   * @throws SourceIdentityMismatchException when a UUID request receives another or no UUID
   */
  public <T> DetailResult<T> fetchOneResult(
      String endpoint, Object id, Class<T> type, String resourceLabel) {
    FetchErrorLatch errorLatch = new FetchErrorLatch();
    long decodedRows = 0;
    try (SyncRun run = openSyncRun(errorLatch)) {
      String include = type == ScWikiItemDto.class ? ITEM_METADATA_INCLUDES : null;
      ScWikiRequestExecutor.Response response =
          include == null
              ? requests.getResult(endpoint + "/{id}?version={version}", id, run.version())
              : requests.getResult(
                  endpoint + "/{id}?version={version}&include={include}",
                  id,
                  run.version(),
                  include);
      if (response.missing()) {
        recordFetchErrorOnce(errorLatch);
        syncRunProgress.fetched(0, 0, false);
        syncRunProgress.failed(1);
        return new DetailResult<>(DetailOutcome.MISSING, null);
      }
      String rawBody = response.body();
      if (rawBody == null || rawBody.isBlank()) {
        throw new IllegalStateException("SC Wiki returned no detail resource");
      }
      JsonNode body = objectMapper.readTree(rawBody);
      JsonNode payload = body.has("data") ? body.get("data") : body;
      validateVersion(payload);
      T decoded = objectMapper.treeToValue(payload, type);
      if (decoded == null) {
        throw new IllegalStateException("SC Wiki detail decoded to no resource");
      }
      decodedRows = 1;
      if (id instanceof UUID requested
          && decoded instanceof ScWikiRow row
          && !requested.equals(row.uuid())) {
        recordFetchErrorOnce(errorLatch);
        syncRunProgress.fetched(1, 0, false);
        syncRunProgress.invalidateCensus();
        throw new SourceIdentityMismatchException(requested, row.uuid());
      }
      T result = type.cast(withRequestedIncludes(decoded, include));
      long validRows = decoded instanceof ScWikiRow row && row.uuid() != null ? 1 : 0;
      syncRunProgress.fetched(1, validRows, true);
      return new DetailResult<>(DetailOutcome.FOUND, result);
    } catch (SourceIdentityMismatchException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn("Failed to fetch {} from SC Wiki API", resourceLabel, e);
      recordFetchErrorOnce(errorLatch);
      syncRunProgress.fetched(decodedRows, 0, false);
      syncRunProgress.failed(1);
      return new DetailResult<>(DetailOutcome.UNAVAILABLE, null);
    }
  }

  /** Distinguishes a validated detail, explicit HTTP 404 and an unusable response. */
  public enum DetailOutcome {
    /** A decoded detail passed the request's identity and game-version checks. */
    FOUND,
    /** The upstream explicitly returned HTTP 404 for the requested resource. */
    MISSING,
    /** Transport, source-version or decoding failed without proving the asset absent. */
    UNAVAILABLE
  }

  /**
   * Carries a single-resource source outcome without treating temporary errors as asset absence.
   *
   * @param <T> detail payload type
   * @param outcome validated source outcome
   * @param data decoded detail, present only when the outcome is FOUND
   */
  public record DetailResult<T>(DetailOutcome outcome, @Nullable T data) {

    /** Rejects an outcome that disagrees with the presence of its detail. */
    public DetailResult {
      Objects.requireNonNull(outcome);
      if ((outcome == DetailOutcome.FOUND) != (data != null)) {
        throw new IllegalArgumentException("A found detail must carry exactly one payload");
      }
    }
  }

  /**
   * Fetches and validates a page without treating its validator as a collection validator.
   *
   * @param <T> row type
   * @param requestUri versioned page URI
   * @param typeRef response type
   * @param resourceLabel log label
   * @param include explicitly requested relationships
   * @param errorLatch enclosing walk's error counter latch
   * @return decoded page, or null after a request or contract failure
   */
  @Nullable
  private <T> ScWikiResponseDto<T> fetchSinglePage(
      String requestUri,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      @Nullable String include,
      FetchErrorLatch errorLatch) {
    try {
      String raw = requests.get(requestUri);
      if (raw == null || raw.isBlank()) {
        throw new IllegalStateException("SC Wiki returned no page body");
      }
      JsonNode body = objectMapper.readTree(raw);
      JsonNode rows = body.get("data");
      if (rows == null || !rows.isArray()) {
        throw new IllegalStateException("SC Wiki page has no data array");
      }
      for (JsonNode row : rows) {
        validateVersion(row);
      }
      ScWikiResponseDto<T> decoded =
          objectMapper.readValue(
              raw, objectMapper.getTypeFactory().constructType(typeRef.getType()));
      List<T> stamped =
          decoded.data().stream().map(row -> withRequestedIncludes(row, include)).toList();
      return new ScWikiResponseDto<>(stamped, decoded.meta(), decoded.links());
    } catch (RuntimeException e) {
      log.warn("Failed to fetch {} page from SC Wiki API", resourceLabel, e);
      syncRunProgress.failed(1);
      recordFetchErrorOnce(errorLatch);
      return null;
    }
  }

  /**
   * Carries the requested relationship set into an item's immutable source snapshot.
   *
   * @param <T> decoded resource type
   * @param row decoded resource
   * @param include relationships sent with the request
   * @return resource with its request context, preserving its runtime type
   */
  @SuppressWarnings("unchecked")
  private static <T> T withRequestedIncludes(T row, @Nullable String include) {
    if (!(row instanceof ScWikiItemDto item)) {
      return row;
    }
    Set<ItemCatalogueMetadata.Relationship> requested =
        EnumSet.noneOf(ItemCatalogueMetadata.Relationship.class);
    if (include != null) {
      for (String relationship : include.split(",", -1)) {
        ItemCatalogueMetadata.Relationship selected =
            switch (relationship.strip()) {
              case "variants" -> ItemCatalogueMetadata.Relationship.VARIANTS;
              case "related_items" -> ItemCatalogueMetadata.Relationship.RELATED_ITEMS;
              case "vehicles" -> ItemCatalogueMetadata.Relationship.VEHICLES;
              default -> null;
            };
        if (selected != null) {
          requested.add(selected);
        }
      }
    }
    return (T) item.withRequestedIncludes(Set.copyOf(requested));
  }

  /**
   * Enumerates all upstream asset identities, including assets excluded from normal import.
   *
   * @param endpoint source item or vehicle endpoint
   * @param resourceLabel source label for diagnostics
   * @param pageSize requested page size, bounded by the endpoint's upstream limit
   * @return immutable identities and whether the all-asset census was complete
   */
  public ExistenceCensus fetchExistenceCensus(String endpoint, String resourceLabel, int pageSize) {
    int maximumPageSize =
        endpoint.equals(properties.vehiclesEndpoint()) ? MAX_VEHICLE_PAGE_SIZE : MAX_ITEM_PAGE_SIZE;
    int boundedPageSize = Math.max(1, Math.min(pageSize, maximumPageSize));
    FetchResult<ScWikiIdentityDto> result =
        fetchAllPagesResult(
            endpoint,
            new ParameterizedTypeReference<ScWikiResponseDto<ScWikiIdentityDto>>() {},
            resourceLabel + " existence",
            null,
            Map.of("include_irrelevant", "true"),
            boundedPageSize);
    Set<UUID> identities = new HashSet<>();
    for (ScWikiIdentityDto row : result.data()) {
      if (row != null && row.uuid() != null) {
        identities.add(row.uuid());
      }
    }
    return new ExistenceCensus(
        identities, result.complete() && identities.size() == result.data().size());
  }

  /**
   * Contains an all-asset identity census without any asset metadata eligible for import.
   *
   * @param uuids immutable source asset identities
   * @param complete whether every source row had a unique usable identity
   */
  public record ExistenceCensus(Set<UUID> uuids, boolean complete) {

    /** Copies the identity set before it can vouch for an absence-based sweep. */
    public ExistenceCensus {
      uuids = Set.copyOf(uuids);
    }
  }

  /**
   * Rejects a resource carrying a patch different from the selected run.
   *
   * @param payload resource payload
   */
  private void validateVersion(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw new IllegalStateException("SC Wiki resource is not an object");
    }
    JsonNode version =
        payload.has("version") ? payload.get("version") : payload.get("game_version");
    if (version != null
        && !version.isNull()
        && (!version.isString() || !version.stringValue().equals(runVersion.get()))) {
      throw new IllegalStateException("SC Wiki resource belongs to a different game version");
    }
  }

  /**
   * Pins the configured or current default patch for a complete, reentrant sync run.
   *
   * @return thread-confined version scope
   * @throws IllegalStateException when no concrete game version can be resolved
   */
  public SyncRun openSyncRun() {
    return openSyncRun(new FetchErrorLatch());
  }

  /** Pins a run using the current operation's failure-metric latch. */
  private SyncRun openSyncRun(FetchErrorLatch errorLatch) {
    String previous = runVersion.get();
    String version = previous == null ? resolveGameVersion(errorLatch) : previous;
    runVersion.set(version);
    return new SyncRun(previous, version);
  }

  /**
   * Resolves an explicit patch before catalogue writes start.
   *
   * @param errorLatch failure metric latch of the enclosing operation
   * @return configured or upstream-default patch
   */
  private String resolveGameVersion(FetchErrorLatch errorLatch) {
    if (properties.gameVersion() != null && !properties.gameVersion().isBlank()) {
      return properties.gameVersion().strip();
    }
    try {
      String raw = requests.get("/api/game-versions/default");
      JsonNode body = raw == null ? null : objectMapper.readTree(raw);
      JsonNode code = body == null ? null : body.path("data").get("code");
      if (code == null || !code.isString() || code.stringValue().isBlank()) {
        throw new IllegalStateException("SC Wiki default game version is absent");
      }
      return code.stringValue();
    } catch (RuntimeException e) {
      recordFetchErrorOnce(errorLatch);
      throw new IllegalStateException("Cannot select a game version for the SC Wiki sync", e);
    }
  }

  /** Restores the enclosing version scope when a sync step finishes. */
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  public final class SyncRun implements AutoCloseable {

    /** Enclosing scope's version, or null at the outer run boundary. */
    @Nullable private final String previous;

    /** Immutable patch for this run. */
    private final String selected;

    /** Whether this scope has already restored its enclosing scope. */
    private boolean closed;

    /**
     * Returns the concrete patch selected for every request in this run.
     *
     * @return game-version code
     */
    public String version() {
      return selected;
    }

    /** Restores the enclosing run's version on the current thread. */
    @Override
    public void close() {
      if (closed) {
        return;
      }
      if (previous == null) {
        runVersion.remove();
      } else {
        runVersion.set(previous);
      }
      closed = true;
    }
  }

  /**
   * Builds the relative page URI with unencoded brackets, leaving encoding to the RestClient.
   *
   * <p>Params in order: {@code page[number]}, {@code page[size]}, {@code sort=class_name} (for
   * {@link ScWikiProperties#classNameSortedEndpoints()}), {@code include} (if non-blank), each
   * non-blank {@code filter[<key>]} in map order, {@code version} (if non-blank).
   *
   * @param endpoint Wiki endpoint path
   * @param pageNumber 1-based page index
   * @param include optional eager-load string, or {@code null} / blank
   * @param filters optional {@code filter[<key>]=<value>} pairs, or {@code null} / empty; entries
   *     with a blank key or value are skipped
   * @param pageSize the {@code page[size]} resolved by {@link #effectivePageSize}
   * @return relative URI string for {@code .uri(String)}
   */
  private String buildPagedUri(
      String endpoint,
      int pageNumber,
      @Nullable String include,
      @Nullable Map<String, String> filters,
      int pageSize) {
    StringBuilder sb = new StringBuilder(endpoint);
    sb.append("?page[number]=").append(pageNumber);
    sb.append("&page[size]=").append(pageSize);
    if (properties.classNameSortedEndpoints().contains(endpoint)) {
      sb.append("&sort=class_name");
    }
    if (include != null && !include.isBlank()) {
      sb.append("&include=").append(include);
    }
    if (filters != null) {
      for (Map.Entry<String, String> entry : filters.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
          sb.append("&filter[").append(key).append("]=").append(value);
        }
      }
    }
    if (runVersion.get() != null) {
      sb.append("&version=").append(runVersion.get());
    }
    return sb.toString();
  }

  /**
   * Contains the decoded rows and census completeness of an unconditional Wiki page walk.
   *
   * <p>Only a complete result may authorize an absence-based sweep.
   *
   * @param <T> decoded source row type
   * @param data decoded rows across fetched pages, possibly empty after a source failure
   * @param complete whether every source row had a usable unique identity and all pages were read
   */
  public record FetchResult<T>(List<T> data, boolean complete) {

    /**
     * Wraps rows from a verified complete page walk, including a valid empty source catalogue.
     *
     * @param <T> decoded source row type
     * @param data complete decoded source rows
     * @return rows eligible to establish the requested scope's source census
     */
    @NotNull
    public static <T> FetchResult<T> of(List<T> data) {
      return new FetchResult<>(data, true);
    }

    /**
     * Retains usable rows from an incomplete walk without authorizing absence-based cleanup.
     *
     * @param <T> decoded source row type
     * @param data rows decoded before the source failure or census conflict
     * @return rows eligible for individual writes but never a tombstone sweep
     */
    @NotNull
    public static <T> FetchResult<T> partial(List<T> data) {
      return new FetchResult<>(data, false);
    }
  }

  /**
   * One-shot claim for a single fetch's {@link CatalogueMetricNames#EXTERNAL_FETCH_ERRORS}
   * increment, created per {@link #fetchAllPagesResult} walk or {@link #fetchOne} call. Not
   * thread-safe; confined to the thread driving its fetch.
   */
  private static final class FetchErrorLatch {

    /** Whether this fetch has already contributed its single counter increment. */
    private boolean recorded;

    /**
     * Claims this fetch's one increment for the caller.
     *
     * @return {@code true} on the first invocation for this latch — the caller must record —, and
     *     {@code false} on every subsequent one, meaning the increment is already spent
     */
    private boolean claim() {
      if (recorded) {
        return false;
      }
      recorded = true;
      return true;
    }
  }
}
