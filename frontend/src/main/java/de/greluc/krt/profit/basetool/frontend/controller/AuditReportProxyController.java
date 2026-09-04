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

import de.greluc.krt.profit.basetool.frontend.support.AuditDomains;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Frontend proxy for the unified audit-log PDF exports (REQ-AUDIT-001, ADR-0037). One seam for
 * every tab: {@code BANK} routes to the bank admin export, the generic areas to {@code
 * /api/v1/audit/{domain}/export}. Streams the backend's PDF bytes back to the browser via the
 * authenticated {@link WebClient} (OAuth2 token attached automatically) and forwards the caller's
 * IANA time zone so the documents render local timestamps. Authorization (ADMIN) is decided by the
 * backend gates; this seam only requires authentication.
 */
@RestController
@RequestMapping("/api/proxy/audit")
@RequiredArgsConstructor
@Slf4j
public class AuditReportProxyController {

  /**
   * The known audit tabs. The raw {@code domain} path segment is validated against this allowlist
   * before it is concatenated into the backend URI, so an unknown or crafted value (e.g. one
   * containing {@code ..}) never reaches the URI builder. Defense-in-depth: the backend also
   * re-authorizes every path and rejects unknown {@code AuditDomain} enums with 400.
   *
   * <p>Shared with the page controller through {@link AuditDomains} rather than duplicated. The
   * duplicate had drifted: it never gained {@code MARKET}, so the Materialbörse tab rendered but
   * its export and purge buttons answered {@code 400}.
   */
  private static final List<String> ALLOWED_DOMAINS = AuditDomains.ALL;

  private final WebClient webClient;

  /**
   * Rejects any {@code domain} path segment that is not a known audit tab, so a crafted or unknown
   * value never reaches the backend URI builder (defense-in-depth, REQ-AUDIT-002).
   *
   * @param domain the raw path segment
   * @throws ResponseStatusException {@code 400 BAD_REQUEST} when the domain is not a known tab
   */
  private static void requireKnownDomain(@NotNull String domain) {
    if (!ALLOWED_DOMAINS.contains(domain)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown audit domain: " + domain);
    }
  }

  /**
   * Proxies one area's audit-log PDF export for a caller-chosen period.
   *
   * @param domain the area tab ({@code BANK} or a generic {@code AuditDomain} name)
   * @param from period start; bound as an instant so the relayed value cannot carry URI syntax
   * @param to period end; bound as an instant so the relayed value cannot carry URI syntax
   * @param userTimeZone the caller's IANA time zone; optional
   * @return the PDF with attachment headers
   */
  @GetMapping("/{domain}/export")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> downloadAuditLog(
      @PathVariable @NotNull String domain,
      @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestHeader(value = "X-User-Time-Zone", required = false) String userTimeZone) {
    requireKnownDomain(domain);
    String backendBase =
        "BANK".equals(domain)
            ? "/api/v1/bank/admin/audit/export"
            : "/api/v1/audit/" + domain + "/export";
    String uri =
        UriComponentsBuilder.fromPath(backendBase)
            .queryParam("from", from)
            .queryParam("to", to)
            .toUriString();
    String filename = "audit-" + domain.toLowerCase(Locale.ROOT) + ".pdf";
    return fetchAttachment(uri, userTimeZone, filename, MediaType.APPLICATION_PDF);
  }

  /**
   * Proxies one area's audit-log JSON export for a caller-chosen period (REQ-AUDIT-003). JSON
   * carries UTC instants verbatim, so no time-zone header is forwarded.
   *
   * @param domain the area tab ({@code BANK} or a generic {@code AuditDomain} name)
   * @param from period start; bound as an instant so the relayed value cannot carry URI syntax
   * @param to period end; bound as an instant so the relayed value cannot carry URI syntax
   * @return the JSON with attachment headers
   */
  @GetMapping("/{domain}/export.json")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> downloadAuditLogJson(
      @PathVariable @NotNull String domain,
      @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
    requireKnownDomain(domain);
    String backendBase =
        "BANK".equals(domain)
            ? "/api/v1/bank/admin/audit/export.json"
            : "/api/v1/audit/" + domain + "/export.json";
    String uri =
        UriComponentsBuilder.fromPath(backendBase)
            .queryParam("from", from)
            .queryParam("to", to)
            .toUriString();
    String filename = "audit-" + domain.toLowerCase(Locale.ROOT) + ".json";
    return fetchAttachment(uri, null, filename, MediaType.APPLICATION_JSON);
  }

  /**
   * Proxies one area's audit-log retention purge (REQ-AUDIT-004): deletes the backend's audit rows
   * older than the cutoff and relays the JSON result ({@code {deletedCount}}) back to the page so
   * it can report how many entries were removed. {@code BANK} routes to the bank admin purge.
   *
   * @param domain the area tab ({@code BANK} or a generic {@code AuditDomain} name)
   * @param before the exclusive cutoff; bound as an instant so the relayed value cannot carry URI
   *     syntax
   * @return the backend's JSON purge result
   */
  @DeleteMapping("/{domain}")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> purgeAuditLog(
      @PathVariable @NotNull String domain,
      @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before) {
    requireKnownDomain(domain);
    String backendBase =
        "BANK".equals(domain) ? "/api/v1/bank/admin/audit" : "/api/v1/audit/" + domain;
    String uri =
        UriComponentsBuilder.fromPath(backendBase).queryParam("before", before).toUriString();
    try {
      byte[] body = webClient.delete().uri(uri).retrieve().bodyToMono(byte[].class).block();
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      return ResponseEntity.ok().headers(headers).body(body);
    } catch (WebClientResponseException e) {
      log.warn("Audit purge proxy: backend returned {} for {}", e.getStatusCode(), uri);
      throw new ResponseStatusException(e.getStatusCode(), e.getMessage());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("Audit purge proxy: unexpected error for {}", uri, e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while purging the audit log.");
    }
  }

  /**
   * Fetches one backend export document and re-wraps it with attachment headers; backend errors
   * propagate with their original status so the page can surface 400/403 distinctly.
   *
   * @param uri the backend URI incl. query
   * @param userTimeZone the zone header to forward; may be {@code null}
   * @param filename the download filename
   * @param mediaType the response content type (PDF or JSON)
   * @return the proxied attachment response
   */
  private ResponseEntity<byte[]> fetchAttachment(
      @NotNull String uri,
      String userTimeZone,
      @NotNull String filename,
      @NotNull MediaType mediaType) {
    try {
      byte[] pdf =
          webClient
              .get()
              .uri(uri)
              .headers(
                  h -> {
                    if (userTimeZone != null && !userTimeZone.isBlank()) {
                      h.set("X-User-Time-Zone", userTimeZone);
                    }
                  })
              .retrieve()
              .bodyToMono(byte[].class)
              .block();

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(mediaType);
      headers.setContentDispositionFormData("attachment", filename);
      return ResponseEntity.ok().headers(headers).body(pdf);
    } catch (WebClientResponseException e) {
      log.warn("Audit report proxy: backend returned {} for {}", e.getStatusCode(), uri);
      throw new ResponseStatusException(e.getStatusCode(), e.getMessage());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("Audit report proxy: unexpected error for {}", uri, e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while generating the audit log report.");
    }
  }
}
