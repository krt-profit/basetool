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

package de.greluc.krt.profit.basetool.backend.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the status an anonymous caller gets from the paths the API vhost allow-lists (REQ-SEC-037).
 *
 * <p><strong>Why a test and not a table in a document.</strong> The operator verifies the vhost by
 * curling those paths from outside and comparing the status against the table in {@code
 * API_VHOST_ROLLOUT_RUNBOOK.md} § D.3a. A number that is wrong there is not a documentation defect
 * — it is a false alarm during a production rollout, or worse, a real finding read as noise. The
 * table said {@code 401} for the Finanzen paths on reasoning from their {@code @PreAuthorize}
 * alone; production answers {@code 403}. These assertions are the reason it cannot say the wrong
 * thing again.
 *
 * <p><strong>Why the two differ.</strong> The me-scoped paths are {@code authenticated()} in {@link
 * SecurityConfig}'s matcher list, so Spring Security refuses them before the dispatch and the entry
 * point writes {@code 401}. The Finanzen paths sit under {@code GET /api/v1/missions/**}, which is
 * {@code permitAll} in that same list — the request is dispatched, {@code @PreAuthorize} refuses it
 * at the method seam, and the {@code @RestControllerAdvice} renders that refusal as {@code 403}.
 * Nothing upgrades it: {@code ExceptionTranslationFilter}, which would substitute the entry point
 * for an anonymous caller, never sees an exception the MVC advice already handled. Both paths are
 * closed to the internet either way — this test is about the number, because the number is what the
 * rollout check reads.
 *
 * <p>The mission id is a constant that matches nothing. Method security runs before the controller
 * body, so the refusal never depends on the row existing — and a test that needed a seeded Einsatz
 * would assert the seed as much as the rule.
 */
@SpringBootTest
class ApiVhostAnonymousSurfaceTest {

  /** A well-formed id that matches no Einsatz; authorization is refused before the lookup. */
  private static final String ABSENT_MISSION = "00000000-0000-4000-8000-00000000dead";

  /** A well-formed id that matches no Operation, for the same reason. */
  private static final String ABSENT_OPERATION = "00000000-0000-4000-8000-00000000beef";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  /**
   * Builds MockMvc with the real security filter chain, so the matcher list is the one under test.
   */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * The Finanzen entries are refused with {@code 403}, not {@code 401}.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousFinanceEntriesWithForbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/missions/" + ABSENT_MISSION + "/finance-entries"))
        .andExpect(status().isForbidden());
  }

  /**
   * The Finanzen summary is refused the same way as the entries behind it.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousFinanceSummaryWithForbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/missions/" + ABSENT_MISSION + "/finance-entries/summary"))
        .andExpect(status().isForbidden());
  }

  /**
   * A me-scoped path is refused with {@code 401}, which is the contrast that makes the split above
   * a rule rather than an accident of one endpoint.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousMeScopedPathWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/me/capabilities")).andExpect(status().isUnauthorized());
  }

  /**
   * The membership read the org-unit switcher makes is me-scoped too, and answers {@code 401}.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousMembershipsWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/users/me/memberships")).andExpect(status().isUnauthorized());
  }

  /**
   * The last of phase 2: the Lager tree, the Auftrag queue and the org bank.
   *
   * <p>The bank rows are the ones that would matter most — balances and a transaction ledger with
   * member handles on it — and `/api/v1/orders` is the subtle one: the **same path** answers a
   * `POST` that is `permitAll` by design, so only the verb separates a public request form from a
   * queue read. The vhost's read-only guard is the second half of that, and this asserts the first.
   *
   * @param path the allow-listed read
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/inventory/aggregated",
        "/api/v1/inventory/all/grouped",
        "/api/v1/orders",
        "/api/v1/orders/00000000-0000-4000-8000-00000000cafe",
        "/api/v1/org-units/bank/balances",
        "/api/v1/org-units/bank/accounts/00000000-0000-4000-8000-00000000cafe",
        "/api/v1/org-units/bank/accounts/00000000-0000-4000-8000-00000000cafe/transactions"
      })
  @WithAnonymousUser
  void shouldRefuseAnonymousRemainingPhaseTwoReadsWithUnauthorized(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  /**
   * The two hangar reads are me-scoped and org-scoped respectively. The first is the one that would
   * hurt: it answers with the caller's own ships **and** their user record, so an anonymous 200
   * would hand out an email address along with a fleet list.
   *
   * @param path the allow-listed hangar read
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hangar/my-ships", "/api/v1/hangar/squadron-overview"})
  @WithAnonymousUser
  void shouldRefuseAnonymousHangarReadsWithUnauthorized(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  /**
   * The announcement is not anonymous either, despite having no {@code @PreAuthorize} of its own:
   * nothing in the matcher list names it, so it falls through to {@code
   * anyRequest().authenticated()}. Worth pinning precisely because the absence of an annotation
   * reads like "public" to anyone auditing the controller alone.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousAnnouncementWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/announcement")).andExpect(status().isUnauthorized());
  }

  /**
   * The inbox, its badge count and its push stream are me-scoped, and the stream is the one worth
   * asserting: an SSE endpoint that answered an anonymous caller would hold a connection open and
   * feed it another member's events for as long as it lived.
   *
   * @param path the allow-listed notification read
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/notifications",
        "/api/v1/notifications/unread-count",
        "/api/v1/notifications/stream"
      })
  @WithAnonymousUser
  void shouldRefuseAnonymousNotificationReadsWithUnauthorized(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  /**
   * The caller's own record is me-scoped as well — and it is the one allow-listed path that carries
   * an email address, so an anonymous 200 here would be a different order of leak.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousOwnRecordWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
  }

  /**
   * The four Operationen reads answer {@code 401}, not the {@code 403} their Einsatz neighbours
   * give: no chain matcher names {@code /api/v1/operations/**}, so they fall through to {@code
   * anyRequest().authenticated()} and are refused before the dispatch. Same family, same phase,
   * different number — which is exactly why the runbook's table is per path.
   *
   * @param path the allow-listed Operationen read
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/operations/search",
        "/api/v1/operations/" + ABSENT_OPERATION,
        "/api/v1/operations/" + ABSENT_OPERATION + "/finance-summary",
        "/api/v1/operations/" + ABSENT_OPERATION + "/payouts"
      })
  @WithAnonymousUser
  void shouldRefuseAnonymousOperationReadsWithUnauthorized(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  /**
   * Phase 3's reads on the member's own stock, and the picker behind its editor.
   *
   * @param path the allow-listed personal-inventory read
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/personal-inventory",
        "/api/v1/personal-inventory/" + ABSENT_OPERATION,
        "/api/v1/uex/locations/search"
      })
  @WithAnonymousUser
  void shouldRefuseAnonymousPersonalInventoryReadsWithUnauthorized(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  /**
   * The first <em>writes</em> the vhost admits, refused the same way when nobody is signed in.
   *
   * <p>Worth its own case rather than folding into the read above: the allow-list opens these two
   * paths for every verb the backend serves, so the question "what does an anonymous POST get" is
   * now a real one. It must be the same {@code 401} — a write that answered anything softer would
   * mean the vhost had opened a path whose method-level guard does not hold.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousPersonalInventoryWritesWithUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/personal-inventory")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"x\",\"quantity\":1,\"locationUexId\":1,\"locationType\":\"CITY\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/personal-inventory/" + ABSENT_OPERATION)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"x\",\"quantity\":1,\"locationUexId\":1,\"locationType\":\"CITY\","
                        + "\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete("/api/v1/personal-inventory/" + ABSENT_OPERATION).with(csrf()))
        .andExpect(status().isUnauthorized());
  }
}
