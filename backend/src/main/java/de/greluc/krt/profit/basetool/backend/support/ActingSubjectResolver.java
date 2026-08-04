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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves <em>who an authenticated call acts for</em>, which is normally the token's own subject
 * and, for the ingest gateway alone, the member named in {@value #ON_BEHALF_OF_HEADER} (ADR-0129).
 *
 * <p>The gateway stopped relaying the caller's token, so its bearer identifies the
 * <em>gateway</em>. Without this, every ingest upload would be attributed to the service account
 * rather than to the member who sent it.
 *
 * <p><strong>This is a trust boundary, so it is deliberately narrow.</strong>
 *
 * <ul>
 *   <li>The header is honoured only for a caller whose {@code azp} is on the configured gateway
 *       allowlist. An empty allowlist honours nothing, so an unconfigured deployment cannot be
 *       impersonated into.
 *   <li>A header from anyone else is <strong>refused</strong>, never ignored. Ignoring it would let
 *       a probe write under its own identity while believing it wrote under someone else's, and
 *       would leave no trace of the attempt.
 *   <li>It carries a subject and nothing else. It cannot grant a role, widen a scope or select an
 *       org unit — those come from the DB and from a separate header, so a forged value cannot
 *       escalate beyond what the named member could already do.
 * </ul>
 */
@Slf4j
@Component
public class ActingSubjectResolver {

  /**
   * Names the member the ingest gateway is acting for.
   *
   * <p>Must stay identical to {@code BackendImportClient.ON_BEHALF_OF_HEADER} in the ingest module.
   * The two are separate modules with no shared code, so the literal is duplicated on purpose and
   * pinned by {@code OnBehalfOfHeaderParityTest} — a rename on one side alone would not fail, it
   * would silently attribute every ingest write to the service account.
   */
  public static final String ON_BEHALF_OF_HEADER = "X-Ingest-On-Behalf-Of";

  private final IngestGatewayProperties properties;
  private final MeterRegistry meterRegistry;

  /**
   * Creates the resolver.
   *
   * @param properties supplies the {@code azp} values allowed to act for another member
   * @param meterRegistry counts refused attempts, which are a security signal rather than noise
   */
  public ActingSubjectResolver(
      @NotNull IngestGatewayProperties properties, @NotNull MeterRegistry meterRegistry) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Returns the subject this call acts for.
   *
   * @param jwt the authenticated caller's token
   * @param request the current request, read only for {@value #ON_BEHALF_OF_HEADER}
   * @return the acting member's {@code sub}
   * @throws AccessDeniedException when the header is present but the caller may not use it, or when
   *     its value is not a well-formed subject
   */
  public @NotNull String resolve(@NotNull Jwt jwt, @Nullable HttpServletRequest request) {
    String onBehalfOf = request == null ? null : request.getHeader(ON_BEHALF_OF_HEADER);
    if (onBehalfOf == null || onBehalfOf.isBlank()) {
      return jwt.getSubject();
    }
    if (!properties.isGatewayClient(jwt.getClaimAsString("azp"))) {
      // The client id, not the subject: naming who tried is the point, and azp is a bounded
      // registered value rather than a person (REQ-OBS-004).
      log.warn("Refused an on-behalf-of header from a caller that is not an ingest gateway");
      refuse(MetricNames.ON_BEHALF_OF_NOT_A_GATEWAY);
      throw new AccessDeniedException("This caller may not act for another member.");
    }
    try {
      UUID.fromString(onBehalfOf);
    } catch (IllegalArgumentException malformed) {
      log.warn("The ingest gateway named a subject that is not a UUID; refusing the call");
      refuse(MetricNames.ON_BEHALF_OF_MALFORMED);
      throw new AccessDeniedException("The named member is not a valid subject.");
    }
    return onBehalfOf;
  }

  /**
   * Same as {@link #resolve(Jwt, HttpServletRequest)}, but as the {@link UUID} the persistence
   * layer wants, and fail-closed on a subject that is not one.
   *
   * <p>Mirrors {@code UserService#getUserIdFromJwt} deliberately, including its choice of {@link
   * AccessDeniedException} over an argument exception: a caller whose {@code sub} is not a UUID is
   * a service account or a malformed token, which is an authorization answer (403), not a
   * bad-request one (400). Letting {@code UUID.fromString} throw here would quietly reclassify it.
   *
   * @param jwt the authenticated caller's token
   * @param request the current request, read only for {@value #ON_BEHALF_OF_HEADER}
   * @return the acting member's id
   * @throws AccessDeniedException when the acting subject is missing or not a UUID
   */
  public @NotNull UUID resolveUserId(@NotNull Jwt jwt, @Nullable HttpServletRequest request) {
    String subject = resolve(jwt, request);
    if (subject == null || subject.isBlank()) {
      throw new AccessDeniedException("The token carries no subject.");
    }
    try {
      return UUID.fromString(subject);
    } catch (IllegalArgumentException malformed) {
      throw new AccessDeniedException("The acting subject is not a valid user id.");
    }
  }

  /**
   * Counts one refused attempt.
   *
   * @param reason one of the bounded {@code MetricNames.ON_BEHALF_OF_*} literals
   */
  private void refuse(@NotNull String reason) {
    meterRegistry
        .counter(MetricNames.ON_BEHALF_OF_REFUSED, MetricNames.TAG_REASON, reason)
        .increment();
  }
}
