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

package de.greluc.krt.profit.basetool.frontend.audit.client;

import de.greluc.krt.profit.basetool.frontend.model.dto.AuditEventDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAuditEventDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the audit domain: the bank trail and the generic per-area trails, their
 * exports and retention purges (REQ-AUDIT-001…005), over {@link BackendApiClient} (plan §5.9,
 * ADR-0032).
 *
 * <p>The area tab is always one of {@code AuditDomains.ALL}, checked by the caller; {@code BANK}
 * routes to the bank admin endpoints, every other area to {@code /api/v1/audit/{domain}}.
 */
@Service
@RequiredArgsConstructor
public class AuditBackendClient {

  /** The area tab served by the bank's own trail. */
  private static final String BANK = "BANK";

  private static final ParameterizedTypeReference<PageResponse<BankAuditEventDto>> BANK_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<AuditEventDto>> GENERIC_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<ExchangeClientDto>> EXCHANGE_CLIENT_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * The filters of one audit-trail page; every value is typed or already narrowed to the offered
   * options (REQ-SEC-051).
   *
   * @param page zero-based page index, never negative
   * @param size page size
   * @param from period start, or {@code null}
   * @param to period end, or {@code null}
   * @param actorUserId the actor's Keycloak {@code sub}, or {@code null}
   * @param eventType an event type of the tab, or {@code null}
   * @param clientId an offered originating client, or {@code null}
   */
  public record Filter(
      int page,
      int size,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable UUID actorUserId,
      @Nullable String eventType,
      @Nullable String clientId) {}

  /**
   * Reads one page of the bank audit trail.
   *
   * @param filter the page and its filters
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BankAuditEventDto> bankEvents(@NotNull Filter filter) {
    return backendApiClient.get(filtered("/api/v1/bank/admin/audit", filter), BANK_PAGE);
  }

  /**
   * Reads one page of a generic area's audit trail.
   *
   * @param domain the area tab, one of {@code AuditDomains.ALL} other than {@code BANK}
   * @param filter the page and its filters
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<AuditEventDto> areaEvents(@NotNull String domain, @NotNull Filter filter) {
    return backendApiClient.get(filtered("/api/v1/audit/{domain}", filter), GENERIC_PAGE, domain);
  }

  /**
   * Lists the exchange registry's clients, whose names the audit filter and rows show
   * (REQ-XCH-010).
   *
   * @return the registry, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<ExchangeClientDto> exchangeClients() {
    return backendApiClient.get("/api/v1/admin/exchange-clients", EXCHANGE_CLIENT_LIST);
  }

  /**
   * Downloads one area's audit-log PDF for a period, rendered in the caller's time zone.
   *
   * @param domain the area tab
   * @param from period start
   * @param to period end
   * @param userTimeZone the caller's IANA time zone, forwarded when not blank
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] exportPdf(
      @NotNull String domain,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable String userTimeZone) {
    String uri =
        UriComponentsBuilder.fromPath(
                BANK.equals(domain)
                    ? "/api/v1/bank/admin/audit/export"
                    : "/api/v1/audit/{domain}/export")
            .queryParam("from", from)
            .queryParam("to", to)
            .encode()
            .build()
            .toUriString();
    return backendApiClient.execute(
        HttpMethod.GET,
        uri,
        webClient ->
            webClient
                .get()
                .uri(uri, domain)
                .headers(
                    h -> {
                      if (userTimeZone != null && !userTimeZone.isBlank()) {
                        h.set("X-User-Time-Zone", userTimeZone);
                      }
                    }),
        spec -> spec.bodyToMono(byte[].class));
  }

  /**
   * Downloads one area's audit-log JSON export for a period (REQ-AUDIT-003).
   *
   * @param domain the area tab
   * @param from period start
   * @param to period end
   * @return the JSON bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] exportJson(
      @NotNull String domain, @NotNull Instant from, @NotNull Instant to) {
    String uri =
        UriComponentsBuilder.fromPath(
                BANK.equals(domain)
                    ? "/api/v1/bank/admin/audit/export.json"
                    : "/api/v1/audit/{domain}/export.json")
            .queryParam("from", from)
            .queryParam("to", to)
            .encode()
            .build()
            .toUriString();
    return backendApiClient.execute(
        HttpMethod.GET,
        uri,
        webClient -> webClient.get().uri(uri, domain),
        spec -> spec.bodyToMono(byte[].class));
  }

  /**
   * Purges one area's audit entries older than a cutoff (REQ-AUDIT-004).
   *
   * @param domain the area tab
   * @param before the exclusive cutoff
   * @return the backend's JSON purge result, or {@code null} when it sent no body
   */
  public byte @Nullable [] purge(@NotNull String domain, @NotNull Instant before) {
    String uri =
        UriComponentsBuilder.fromPath(
                BANK.equals(domain) ? "/api/v1/bank/admin/audit" : "/api/v1/audit/{domain}")
            .queryParam("before", before)
            .encode()
            .build()
            .toUriString();
    return backendApiClient.execute(
        HttpMethod.DELETE,
        uri,
        webClient -> webClient.delete().uri(uri, domain),
        spec -> spec.bodyToMono(byte[].class));
  }

  /**
   * Builds the URI template of an audit-trail page: the path template, the page and the present
   * filters, each value encoded once as the query parameter it is.
   *
   * @param path the trail's path template
   * @param filter the page and its filters
   * @return the URI template, with any path variable left for the WebClient to expand
   */
  @NotNull
  private static String filtered(@NotNull String path, @NotNull Filter filter) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath(path)
            .queryParam("page", filter.page())
            .queryParam("size", filter.size());
    appendIfPresent(uri, "from", filter.from());
    appendIfPresent(uri, "to", filter.to());
    appendIfPresent(uri, "actorUserId", filter.actorUserId());
    appendIfPresent(uri, "eventType", filter.eventType());
    appendIfPresent(uri, "clientId", filter.clientId());
    return uri.encode().build().toUriString();
  }

  /**
   * Appends a query parameter when the value is present and non-blank.
   *
   * @param uri the builder
   * @param name the parameter name
   * @param value the value, or {@code null}/blank to skip
   */
  private static void appendIfPresent(
      @NotNull UriComponentsBuilder uri, @NotNull String name, @Nullable Object value) {
    if (value == null || (value instanceof String s && s.isBlank())) {
      return;
    }
    uri.queryParam(name, value);
  }
}
