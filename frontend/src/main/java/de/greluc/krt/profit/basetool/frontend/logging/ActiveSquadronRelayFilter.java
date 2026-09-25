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
 * Relays the caller's active OrgUnit selection from {@link ActiveSquadronContext} to the backend as
 * the {@code X-Active-Org-Unit-Id} header on every outbound {@code WebClient} call.
 *
 * <p>The backend re-validates the pin against the caller's memberships. Without a pin no header is
 * added and a DEBUG line is logged; the backend then applies its default scope.
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
