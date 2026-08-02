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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.controller.MeFrontendController;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link ActiveSquadronContextFilter}'s MDC contract (REQ-OBS-001, audit finding
 * M1): every frontend request must carry the {@code orgUnitId} correlation field, it must render
 * the pin's UUID (never the OrgUnit name), it must render the {@code none} sentinel when the caller
 * has no pin, and it must be gone again once the request completes so it cannot bleed onto the next
 * request served by the same pooled thread.
 */
class ActiveSquadronContextFilterTest {

  private final ActiveSquadronContextFilter filter = new ActiveSquadronContextFilter();

  @AfterEach
  void cleanUp() {
    MDC.clear();
    ActiveSquadronContext.clear();
  }

  @Test
  void bindsPinnedOrgUnitUuidIntoMdcForTheDurationOfTheRequest() throws Exception {
    UUID pinned = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.getSession().setAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY, pinned);
    AtomicReference<String> seenInsideChain = new AtomicReference<>();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, res) ->
            seenInsideChain.set(MDC.get(ActiveSquadronContextFilter.ORG_UNIT_ID_MDC_KEY)));

    assertThat(seenInsideChain.get()).isEqualTo(pinned.toString());
    assertThat(MDC.get(ActiveSquadronContextFilter.ORG_UNIT_ID_MDC_KEY)).isNull();
  }

  @Test
  void bindsNoneSentinelWhenTheCallerHasNoPin() throws Exception {
    AtomicReference<String> seenInsideChain = new AtomicReference<>();

    filter.doFilter(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        (req, res) ->
            seenInsideChain.set(MDC.get(ActiveSquadronContextFilter.ORG_UNIT_ID_MDC_KEY)));

    assertThat(seenInsideChain.get()).isEqualTo(ActiveSquadronContextFilter.NO_ACTIVE_ORG_UNIT);
  }

  @Test
  void clearsMdcEvenWhenTheChainThrows() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request
        .getSession()
        .setAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY, UUID.randomUUID());

    try {
      filter.doFilter(
          request,
          new MockHttpServletResponse(),
          (req, res) -> {
            throw new IllegalStateException("boom");
          });
    } catch (Exception expected) {
      // The filter must not swallow the failure; only the cleanup is under test here.
      assertThat(expected).isInstanceOf(IllegalStateException.class);
    }

    assertThat(MDC.get(ActiveSquadronContextFilter.ORG_UNIT_ID_MDC_KEY)).isNull();
    assertThat(ActiveSquadronContext.get()).isNull();
  }

  /**
   * The MDC binding only works because this filter runs <em>after</em> {@code CorrelationIdFilter}:
   * the pin is read from the session here, so binding it one notch earlier would have recorded
   * {@code null} forever. Pinning the two orders keeps a future reorder from silently reintroducing
   * that.
   */
  @Test
  void runsOneNotchAfterTheCorrelationIdFilterSoThePinIsAlreadyResolvable() {
    assertThat(filter.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 99);
  }
}
