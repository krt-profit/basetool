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

package de.greluc.krt.profit.basetool.frontend.logging;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * Relays the caller's active OrgUnit selection from the frontend's Spring Session to the backend
 * via the {@code X-Active-Org-Unit-Id} request header on every outbound {@code WebClient} call.
 *
 * <p>The state lives on the frontend because backend REST calls do not relay session cookies (the
 * frontend's {@code BackendApiClient} only attaches the OAuth2 bearer token), so a backend-side
 * {@code HttpSession} would be lost between calls. The active OrgUnit is snapshotted onto a
 * thread-local by {@link ActiveSquadronContextFilter} at the start of every servlet request and
 * read here on the WebClient pipeline. Reactor's automatic context propagation (enabled by Spring
 * Boot 4) carries the thread-local across the hop to the Netty reactor thread that actually issues
 * the I/O.
 *
 * <p>R5.e widening: the filter no longer special-cases admin callers — it relays a pinned selection
 * for any authenticated user with &gt;1 membership. The backend independently re-validates
 * non-admin pins against the caller's actual memberships (see {@link
 * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#currentScopePredicate()}), so a
 * spoofed thread-local cannot widen visibility past what the user's memberships permit.
 *
 * <p>Failure modes degrade silently: no thread-local bound (background task / scheduled job) and no
 * active OrgUnit set both yield "no header added" — the backend then falls through to its default
 * behaviour (admin sees all OrgUnits, members see the union of their memberships). "Silently" used
 * to be literal: the branch had no log statement at all, so a pin that was set on the servlet
 * thread but lost before the exchange (context propagation not applied on this hop, a call issued
 * from a pool thread) was indistinguishable from a caller who simply has no pin — and the
 * user-visible symptom of both is the same wrong-Staffel listing. The branch now leaves a DEBUG
 * line; DEBUG rather than anything higher because it is the normal case for every anonymous and
 * every unpinned request, i.e. the majority of outbound calls.
 */
@Slf4j
@Component
public class ActiveSquadronRelayFilter {

  /**
   * HTTP header name carrying the caller's active OrgUnit selection to the backend. {@link
   * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService} reads this name to scope
   * staffel-scoped queries for the current request.
   */
  public static final String ACTIVE_ORG_UNIT_HEADER = "X-Active-Org-Unit-Id";

  /**
   * Returns the filter function that adds the {@code X-Active-Org-Unit-Id} header to outbound
   * requests when the caller has an OrgUnit selected in the frontend session. No header is added
   * for callers without an active selection — the backend then falls through to its default
   * behaviour (admin sees all OrgUnits, non-admin sees the union of memberships).
   *
   * @return filter function for the WebClient pipeline; never {@code null}.
   */
  @NotNull
  public ExchangeFilterFunction relayActiveSquadron() {
    return (request, next) -> {
      UUID active = ActiveSquadronContext.get();
      if (active == null) {
        // Only the method and the path — never the query string, which carries user-typed
        // search terms on the catalog/type-ahead proxies (REQ-OBS-004).
        log.debug(
            "No active OrgUnit bound on this thread; relaying {} {} without the {} header",
            request.method(),
            request.url().getPath(),
            ACTIVE_ORG_UNIT_HEADER);
        return next.exchange(request);
      }
      return next.exchange(
          ClientRequest.from(request).header(ACTIVE_ORG_UNIT_HEADER, active.toString()).build());
    };
  }
}
