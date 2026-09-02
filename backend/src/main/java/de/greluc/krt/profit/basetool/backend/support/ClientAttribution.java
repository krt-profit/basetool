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
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The one place that answers "which client software is this request coming through", bounded.
 *
 * <p>{@link AuthenticatedSubject#authorizedParty(Authentication)} returns whatever the token says
 * and deliberately refuses to bound it. Bounding is a separate decision, and it is the same
 * decision in both places that need it: the request counter's {@code client_id} label (REQ-OBS-018)
 * and the audit row's client column (REQ-AUDIT-005). They must agree — an operator who sees a burst
 * on {@code basetool_api_client_requests_total{client_id="basetool-android"}} and then filters the
 * audit log for the same client is joining two answers that only mean the same thing if one rule
 * produced both. Two copies of a four-line mapping is exactly how that stops being true, so the
 * rule lives here once.
 *
 * <p><b>Bounded, never verbatim.</b> {@code azp} is signed by Keycloak and unsettable by the
 * client, so its <em>content</em> is trustworthy; its <em>range</em> is not, because a client id
 * exists in the realm the moment somebody registers one. An unbounded value would grow a metric
 * label without limit (REQ-OBS-006) and, worse, write an unreviewed string into the audit trail —
 * the one table whose whole value rests on carrying no free text. So an unrecognised client is
 * recorded as {@link MetricNames#CLIENT_ID_OTHER}: "something else", never which something.
 *
 * <p><b>{@code none} is an answer, not an absence.</b> It covers a caller with no token at all — a
 * scheduled job writing its own audit row, whose {@code actorHandle} then reads {@code system} —
 * and a token carrying no {@code azp}, which would be a Keycloak mapper regression blinding the
 * attribution for every client at once. The audit row disambiguates the two by its actor; the
 * metric alerts on the second.
 */
@Component
@RequiredArgsConstructor
public class ClientAttribution {

  private final ApiClientMetricsProperties clientProperties;
  private final IngestGatewayProperties gatewayProperties;

  /**
   * The bounded client label for the current caller, read from their authentication.
   *
   * <p>Takes the authentication rather than reading it from the {@code SecurityContextHolder}: this
   * class lives in {@code support}, which is a dependency leaf and may not consult request-scoped
   * state, and a service caller already holds the authentication through {@code AuthHelperService}.
   *
   * @param authentication the current authentication, may be {@code null}
   * @return a known client id verbatim, else {@code other} / {@code none}
   */
  public @NotNull String labelOf(@Nullable Authentication authentication) {
    return label(AuthenticatedSubject.authorizedParty(authentication).orElse(null));
  }

  /**
   * Maps an {@code azp} claim onto a value that cannot grow without bound.
   *
   * <p>A configured ingest gateway counts as known without being listed twice: {@link
   * IngestGatewayProperties} already names the machine clients this deployment trusts, and
   * duplicating them into the metric allowlist would let the two drift until a gateway silently
   * started reading as {@code other}.
   *
   * @param authorizedParty the token's {@code azp}, may be {@code null} or blank
   * @return the claim itself for a known client, else {@link MetricNames#CLIENT_ID_NONE} for an
   *     absent claim or {@link MetricNames#CLIENT_ID_OTHER}
   */
  public @NotNull String label(@Nullable String authorizedParty) {
    if (authorizedParty == null || authorizedParty.isBlank()) {
      return MetricNames.CLIENT_ID_NONE;
    }
    if (clientProperties.isKnownClient(authorizedParty)
        || gatewayProperties.isGatewayClient(authorizedParty)) {
      return authorizedParty;
    }
    return MetricNames.CLIENT_ID_OTHER;
  }

  /**
   * Every value {@link #label(String)} can produce, for the audit viewer's filter dropdown.
   *
   * <p>Derived from the configured allowlists rather than hand-listed, so a deployment that renames
   * its clients gets a filter that still matches what the trail records — a hardcoded list would
   * offer options that select nothing and hide the client that is actually there. The two bounded
   * buckets come last because they are the residual, and {@code other} is the one an investigation
   * reaches for first.
   *
   * @return the known client ids in configuration order, then {@code other} and {@code none}
   */
  public @NotNull List<String> knownLabels() {
    List<String> labels = new ArrayList<>(clientProperties.getKnownClientIds());
    gatewayProperties.getClientIds().stream()
        .filter(id -> !labels.contains(id))
        .forEach(labels::add);
    labels.add(MetricNames.CLIENT_ID_OTHER);
    labels.add(MetricNames.CLIENT_ID_NONE);
    return List.copyOf(labels);
  }
}
