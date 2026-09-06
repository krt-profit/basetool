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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
 *
 * <p><strong>Re-pinned 2026-09-06 (ADR-0159).</strong> Almost every row here changed number,
 * and none of them changed because the vhost did: the allow-list is untouched, and the
 * backend now refuses the caller behind each admitted path (REQ-SEC-052).
 *
 * <p>The {@code 403} rows are the ones worth understanding rather than replacing. They said
 * {@code 403} because their path sat under a {@code permitAll} stem — the request was
 * dispatched, refused at the method seam, and the {@code @RestControllerAdvice} rendered
 * that. With the stem gone they are turned away at the entry point, which writes {@code 401}.
 * Same closure, different number, and the number is what the rollout check reads.
 *
 * <p>Two paths still answer {@code 200}: {@code /api/v1/terms/document} and
 * {@code /api/v1/app/version-policy}. Both are {@code GET}-scoped, so their {@code HEAD}
 * answers {@code 401} — asserted here and by the nightly probe, because a method-scoped rule
 * above an all-verb one is how REQ-SEC-032 leaked a price query to a {@code HEAD} once.
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
  void shouldRefuseAnonymousFinanceEntriesWithUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/missions/" + ABSENT_MISSION + "/finance-entries"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The Finanzen summary is refused the same way as the entries behind it.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousFinanceSummaryWithUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/missions/" + ABSENT_MISSION + "/finance-entries/summary"))
        .andExpect(status().isUnauthorized());
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
   * The app's live-sync bridge is refused without a token in both directions (ADR-0143,
   * REQ-SEC-037).
   *
   * <p>The stream is the second long-lived SSE endpoint on this vhost and carries the notification
   * stream's hazard in a wider shape: an untokened stream would hold a connection open and feed it
   * <em>other members' rooms</em> for as long as it lived.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousLiveSyncStreamWithUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/live-sync/stream").param("topics", "inventory"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The publish half is refused too, and it is the one allow-listed path on which an ordinary
   * member makes <em>other</em> members re-fetch — bounded by rate rather than by authorization,
   * because the frame carries no data (ADR-0143).
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousLiveSyncPublishWithUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/live-sync/changed")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"topic\":\"inventory\",\"sections\":[\"stock\"]}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The two Beförderung reads are refused without a token (REQ-SEC-037).
   *
   * <p>Me-scoped by construction — both paths end in {@code /my} and the member is resolved from
   * the token — so there is no id an anonymous caller could substitute. Asserted anyway, because
   * the rule is that every allow-listed path has its status pinned, not that obvious ones may be
   * assumed.
   *
   * @param path the allow-listed promotion read
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/promotion/evaluations/my", "/api/v1/promotion/eligibility/my"})
  @WithAnonymousUser
  void shouldRefuseAnonymousPromotionReadsWithUnauthorized(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  /**
   * Phase 4's Raffinerie reads and its booking are refused without a token (REQ-SEC-037).
   *
   * <p>All three are {@code hasRole(KRT_MEMBER)}, and the booking is the one that would matter most
   * if it were not: it creates Lager entries and marks an order stored, and the endpoint does so
   * whatever its item list contains.
   *
   * @param path the allow-listed refinery path
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/refinery-orders/my-orders",
        "/api/v1/refinery-orders/00000000-0000-4000-8000-00000000cafe"
      })
  @WithAnonymousUser
  void shouldRefuseAnonymousRefineryReadsWithUnauthorized(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  /**
   * The booking write, refused before it can create anything.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousRefineryStoreWithUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/refinery-orders/00000000-0000-4000-8000-00000000cafe/store")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[]}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * Phase M's create write, refused before it can raise anything.
   *
   * <p>The vhost admits the bare {@code /api/v1/refinery-orders} stem for every verb it serves, so
   * the chain rule is the only thing between an anonymous caller and a booked refinery run. That
   * rule is the {@code authenticated()} catch-all, which refuses before the dispatch — the body is
   * never parsed, which is why an empty one still answers {@code 401} rather than {@code 400}.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousRefineryOrderCreateWithUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/refinery-orders")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The Materialbörse's four reads are refused without a token (REQ-SEC-037).
   *
   * <p>{@code releasable-items} is the sharpest of them: it answers with the <em>caller's own</em>
   * Lager stacks, so an anonymous {@code 200} would be a different order of leak from an empty
   * board.
   *
   * @param path the allow-listed board path
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/material-exchange/offers",
        "/api/v1/material-exchange/releasable-items",
        "/api/v1/material-requests"
      })
  @WithAnonymousUser
  void shouldRefuseAnonymousBoardReadsWithUnauthorized(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  /**
   * The board's pledge write is refused too — the one path on which an ordinary member makes an
   * entry of somebody else's carry their name.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousBoardInterestWithUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/material-exchange/offers/00000000-0000-4000-8000-00000000cafe/interest")
                .with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The served-version floor answers {@code 200} <em>without</em> a token, and that is the point.
   *
   * <p>It is the one anonymous path the API vhost admits (owner decision, 2026-08-24), against a
   * stance that opens none (plan Q8), so it gets its own assertion rather than riding along with
   * the refusals above. A {@code 401} here is not a hardening win but a broken gate: an app too old
   * to authenticate would then learn nothing and show an authentication error where the design
   * calls for „Update erforderlich".
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldServeVersionPolicyAnonymously() throws Exception {
    mockMvc
        .perform(get("/api/v1/app/version-policy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.minimumVersionCode").exists())
        .andExpect(jsonPath("$.latestVersionCode").exists())
        .andExpect(jsonPath("$.releasesUrl").isNotEmpty());
  }

  /**
   * The same path asked for with {@code HEAD}, which is refused.
   *
   * <p>The rule is {@code GET}-scoped and Spring Security compares the verb with
   * {@code String.equals}, so a {@code HEAD} falls to the authenticated catch-all. That is
   * deliberate rather than incidental: a method-scoped rule sitting above an all-verb one is how
   * REQ-SEC-032 once served a material price query to a {@code HEAD} and returned its
   * {@code Content-Length}.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAHeadOnTheVersionPolicy() throws Exception {
    mockMvc
        .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(
            "/api/v1/app/version-policy"))
        .andExpect(status().isUnauthorized());
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
        "/api/v1/uex/locations/search",
        "/api/v1/personal-blueprints",
        "/api/v1/personal-blueprints/craftability",
        "/api/v1/blueprints/products/search",
        "/api/v1/inventory/all/stack/entries"
      })
  @WithAnonymousUser
  void shouldRefuseAnonymousPersonalInventoryReadsWithUnauthorized(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  /**
   * The catalogues phase 3 admits, and what an anonymous caller gets from each.
   *
   * <p>Not an oversight and not a leak: all three are {@code permitAll} game data the public web
   * frontend already renders without a session — hull names, material names with their units, place
   * names — and the editors behind them need the lists before a member has picked anything.
   *
   * <p>Recorded per path rather than as a family, because REQ-SEC-037 asks for the anonymous
   * surface to be enumerated and a family is not an enumeration. The failure that rule exists to
   * prevent is a path that turns out anonymous when nobody intended it.
   *
   * @param path the allow-listed catalogue read
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(
      strings = {"/api/v1/ship-types", "/api/v1/materials/search", "/api/v1/locations/search"})
  @WithAnonymousUser
  void shouldRefuseTheCataloguesAnonymously(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  /**
   * Phase M's Methoden-Picker is a catalogue of the same kind, and <strong>anonymous</strong>.
   *
   * <p>`/api/v1/refining-methods/**` is `permitAll` in the chain and the list read carries no
   * method gate, so an anonymous caller gets `200`: refining-method names with their UEX yield/cost
   * ratings, master data with no member, org unit or order in it. The admin CRUD on the same stem
   * stays `hasRole(ADMIN)` and is unaffected.
   *
   * <p>Recorded separately from the phase-3 family above because it was admitted to the vhost as
   * `401`, which it has never answered. It is the entry REQ-SEC-037's enumeration rule exists for:
   * a path anonymous on the internet that its own rollout note described as gated.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseTheRefiningMethodCatalogueAnonymously() throws Exception {
    mockMvc.perform(get("/api/v1/refining-methods")).andExpect(status().isUnauthorized());
  }

  /**
   * The member search is <strong>not</strong> a catalogue.
   *
   * <p>It answers with member records, so it is the one picker on the booking form an anonymous
   * caller may not read.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousMemberSearch() throws Exception {
    mockMvc.perform(get("/api/v1/users/search")).andExpect(status().isUnauthorized());
  }

  /**
   * The Lager's bookings, refused without a token.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousInventoryWritesWithUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/inventory")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1,\"locationId\":\"" + ABSENT_OPERATION + "\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/inventory/" + ABSENT_OPERATION + "/book-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1,\"version\":0}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * Joining an Einsatz is refused without a token.
   *
   * <p>Worth pinning because the Einsatz list and the Einsatz itself ARE anonymous on this vhost —
   * the public home page renders from them — so this is a write one path segment away from a read
   * that answers everybody.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousJoinWithUnauthorized() throws Exception {
    mockMvc
        .perform(post("/api/v1/missions/" + ABSENT_OPERATION + "/join").with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The four participant writes resolve the row <strong>before</strong> they judge the caller, so
   * an anonymous request against a row that does not exist answers {@code 404}.
   *
   * <p>That is not the usual shape and it is deliberate on the backend's side: {@code
   * canAccessParticipant} looks the participant up first, because a <strong>guest</strong> sign-up
   * is editable by the anonymous creator presenting the per-row capability token minted at sign-up
   * (REQ-SEC-018). An anonymous caller is a legitimate one here, and the refusal for a row they may
   * not touch is {@code 403} rather than {@code 401}.
   *
   * <p>What this pins is that nothing is written and no success is returned. The status is written
   * down in REQ-SEC-037 as well, so the vhost's allow-list entry is never read as "authenticated
   * only" — it is the one entry on the list that is not.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousParticipationWritesOnAnAbsentRow() throws Exception {
    String participant =
        "/api/v1/missions/" + ABSENT_OPERATION + "/participants/" + ABSENT_OPERATION;
    mockMvc.perform(delete(participant + "/slim").with(csrf())).andExpect(status().isNotFound());
    mockMvc
        .perform(post(participant + "/check-in/slim").with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post(participant + "/check-out/slim").with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            put(participant + "/payout-preference/slim")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preference\":\"PAYOUT\"}"))
        .andExpect(status().isNotFound());
  }

  /**
   * Booking money against an Einsatz, and confirming a payout — both refused without a token.
   *
   * <p>The finance writes are the first paths on this allow-list that live outside every prefix the
   * read-only guard names, so the vhost admits every verb on them and the chain is the only thing
   * standing between an anonymous caller and a booked expense.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousFinanceWritesWithUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/finance-entries")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"missionId\":\""
                        + ABSENT_OPERATION
                        + "\",\"participantId\":\""
                        + ABSENT_OPERATION
                        + "\",\"type\":\"INCOME\",\"amount\":1}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/finance-entries/" + ABSENT_OPERATION)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"INCOME\",\"amount\":1,\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete("/api/v1/finance-entries/" + ABSENT_OPERATION).with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/operations/" + ABSENT_OPERATION + "/payouts/paid-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantKey\":\"x\",\"paidOut\":true}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The account-settings reads and writes, refused without a token.
   *
   * <p>What a caller may change on an account is stated in the settings answer rather than in the
   * chain — `canSetTarget`, `canConfigureVisibility` — so the chain's only job here is to refuse an
   * anonymous one, and that is what this pins.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousAccountSettingsWithUnauthorized() throws Exception {
    String account = "/api/v1/org-units/bank/accounts/" + ABSENT_OPERATION;
    mockMvc.perform(get(account + "/settings")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put(account + "/balance-target")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(post(account + "/visibility/role/OFFICER").with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete(account + "/visibility/role/OFFICER").with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(put(account + "/visibility/all-members/true").with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The order's assignee edge and its status change, refused without a token.
   *
   * <p>The status one is the entry worth having: it is the first path on this allow-list whose
   * chain rule is a role rather than a session, so an authenticated member without LOGISTICIAN gets
   * {@code 403} here where every other write on the list gets {@code 401}. The app gates the
   * control on {@code isLogistician} for exactly that reason.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousOrderAssignmentWritesWithUnauthorized() throws Exception {
    String assignee = "/api/v1/orders/" + ABSENT_OPERATION + "/assignees/" + ABSENT_OPERATION;
    mockMvc.perform(post(assignee).with(csrf())).andExpect(status().isUnauthorized());
    mockMvc.perform(delete(assignee).with(csrf())).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put(assignee + "/note")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"x\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete(assignee + "/note").with(csrf())).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/orders/" + ABSENT_OPERATION + "/status")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":0}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The four pickers phase S opens, refused without a token.
   *
   * <p>The class the audit called unreportable: each of these is swallowed on failure by design — a
   * picker is one field on a form about something else — so a refused read renders as an
   * <em>empty</em> list, and an empty picker reads as an answer rather than as a fault. {@code
   * /job-types} is the worst of them: the tab does not merely show nothing, it states that the
   * organisation has defined no CREW functions.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousPickerReadsWithUnauthorized() throws Exception {
    // Pinned one at a time, because the four do NOT answer alike and grouping them hid that.
    // `/api/v1/orders/**` and `/api/v1/operations/**` are authenticated in the filter chain, so the
    // entry point turns them away before dispatch and writes 401.
    mockMvc.perform(get("/api/v1/orders/lookup")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/operations/lookup")).andExpect(status().isUnauthorized());
    // `GET /api/v1/missions/**` is permitAll — the whole Einsatz read surface is, so a guest can
    // see the board — so this one is dispatched and refused at the method seam: 403.
    // 403, not 401, and the difference is structural: `/api/v1/job-types` is `permitAll` in the
    // filter chain (it sits in the catalogue block beside /locations and /refining-methods), so
    // the request is DISPATCHED and the method-level guard refuses it — which
    // GlobalExceptionHandler
    // renders as 403, with nothing upgrading it to 401 because the MVC advice has already handled
    // it. Identical in shape to /locations/home-locations, which phase M spent three red probe
    // nights learning.
    mockMvc.perform(get("/api/v1/missions/lookup")).andExpect(status().isUnauthorized());
    // 200, and DELIBERATELY so (REQ-SEC-037). `JobTypeController`'s own Javadoc states the rule —
    // "Read is public; mutations are OFFICER/ADMIN" — and the list carries role names and nothing
    // else: no member, no org unit, no Einsatz is reachable through it. It sits in the same
    // permitAll catalogue block as /ship-types, /materials/search and /refining-methods, all of
    // which this class already records as anonymous. Admitting it at the edge therefore publishes
    // a catalogue that was already public, and this assertion is what keeps that a decision.
    mockMvc.perform(get("/api/v1/job-types")).andExpect(status().isUnauthorized());
  }

  /**
   * The two edits phase R opens, refused without a token.
   *
   * <p>These are the only two of the audit's 75 that answered {@code 405} rather than {@code 404}:
   * the path was reachable and the verb was not. What this class can pin is the backend's own
   * answer; that the exception stayed <em>method-scoped</em> — {@code DELETE} on both paths still
   * refused by the edge — is a vhost behaviour and is asserted by the nightly probe instead.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousOrderAndOperationEditsWithUnauthorized() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/orders/" + ABSENT_OPERATION)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"materials\":[]}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/operations/" + ABSENT_OPERATION)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"status\":\"PLANNED\",\"version\":0}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The last seven paths phase X opens, and the one that is anonymous by design.
   *
   * <p>Blaupausen, the Fleetview import and the two thresholds that colour an Auftrag by age. The
   * settings pair is the interesting one: {@code /api/v1/settings} sits in the {@code permitAll}
   * catalogue block beside {@code /locations} and {@code /job-types}, so these reads are dispatched
   * and answer with a value. They carry two integers — how many days before an Auftrag turns
   * yellow, and before it turns red — and nothing else. What must NOT be open is the {@code PUT} on
   * the same path, which is the admin write that changes them for the whole organisation; that is
   * held shut by the read-only family rather than by a carve-out.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousBlueprintAndFleetImports() throws Exception {
    mockMvc
        .perform(get("/api/v1/personal-blueprints/overview"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/personal-blueprints/overview/owners"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/personal-blueprints/batch")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/personal-blueprints/import/apply")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            multipart("/api/v1/personal-blueprints/import/preview")
                .file(new MockMultipartFile("file", "x.json", "application/json", new byte[] {123}))
                .with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            multipart("/api/v1/hangar/import/fleetview")
                .file(new MockMultipartFile("file", "x.json", "application/json", new byte[] {123}))
                .with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/hangar/ships/home-location")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    // Anonymous BY DESIGN, and pinned so it stays a decision: two integers in the same permitAll
    // catalogue block as /locations and /job-types.
    mockMvc.perform(get("/api/v1/settings/job_order.age_yellow_days")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/settings/job_order.age_red_days")).andExpect(status().isUnauthorized());
  }

  /**
   * The Handel family phase W opens, and the two ways it answers.
   *
   * <p>The price screens sit under {@code /api/v1/materials}, which is a {@code permitAll}
   * catalogue prefix — {@code /materials/search} has been recorded as anonymous since phase 2 — so
   * these reads are dispatched and refused at the method seam rather than turned away at the entry
   * point. The Materialbörse and the terminal catalogue are ordinary authenticated surfaces.
   *
   * <p>Pinned one at a time rather than in a loop, because that is exactly what phase S's four
   * pickers proved: a group assertion hides a seam.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousTradeFamily() throws Exception {
    // The four price reads are authenticated, and three of them only became so with this phase:
    // measured anonymously first, `prices-overview` answered 200, `*/prices` answered 200 and
    // `profit-calculation` answered 500 — dispatched and crashing, which is not a gate. They carry
    // the UEX trade data REQ-SEC-032 exists to keep off the public vhost, so they joined the
    // `matrix` carve-out rather than being admitted as they stood.
    mockMvc.perform(get("/api/v1/materials/prices-overview")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/materials/matrix")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/materials/profit-calculation"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/materials/" + ABSENT_MISSION + "/prices"))
        .andExpect(status().isUnauthorized());
    // `GET /materials/{id}` stays ANONYMOUS by decision (REQ-SEC-037). MaterialDto is catalogue
    // only — name, quantity type, category, flags, no price — and `/materials/search` has published
    // those same fields anonymously since phase 2. A 404 here is the absent id, not a refusal: a
    // real one answers 200, and admitting the path at the edge publishes what search already does.
    mockMvc.perform(get("/api/v1/materials/" + ABSENT_MISSION)).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/terminals")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/material-exchange/released-item-ids"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/material-exchange/item-offers")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/material-requests/item")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/material-exchange/offers/" + ABSENT_MISSION + "/remark")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/material-requests/" + ABSENT_MISSION)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The Einsatz planning set phase V opens, refused without a token.
   *
   * <p>The audit's largest block: everything a Kommandoleiter builds an Einsatz out of. The writes
   * are the interesting half here, because {@code GET /api/v1/missions/**} is {@code permitAll} —
   * the whole Einsatz read surface is, so a guest can see the board — and that seam is what phase S
   * met with {@code /missions/lookup} answering {@code 403} where its three neighbours answered
   * {@code 401}. A write is not covered by that {@code permitAll}, so the entry point turns it away
   * before dispatch and writes {@code 401}; the one read in this phase is the one to watch.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousMissionPlanningWrites() throws Exception {
    String mission = "/api/v1/missions/" + ABSENT_MISSION;
    // The three section patches and the party lead. Each carries its own section counter, which is
    // the reason they are separate endpoints at all.
    for (String leaf : new String[] {"/core", "/schedule", "/flags"}) {
      mockMvc
          .perform(
              patch(mission + leaf)
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"version\":0}"))
          .andExpect(status().isUnauthorized());
    }
    mockMvc
        .perform(
            put(mission + "/party-lead")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post(mission + "/participants")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + ABSENT_OPERATION + "\"}"))
        .andExpect(status().isUnauthorized());
    // Einheiten, crew, Frequenzen and Verwalter — slim only, because the full-DTO twins are
    // deprecation-marked with a sunset of 2026-10-20 and the app was moved off them.
    for (String leaf :
        new String[] {
          "/units/slim",
          "/units/" + ABSENT_OPERATION + "/crew/slim",
          "/frequencies/custom/slim",
          "/managers/" + ABSENT_OPERATION + "/slim",
          "/steps/slim",
          "/objectives/slim"
        }) {
      mockMvc
          .perform(
              post(mission + leaf)
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isUnauthorized());
    }
    for (String leaf :
        new String[] {
          "/units/" + ABSENT_OPERATION + "/slim",
          "/units/" + ABSENT_OPERATION + "/crew/" + ABSENT_OPERATION + "/slim",
          "/steps/" + ABSENT_OPERATION + "/slim",
          "/objectives/" + ABSENT_OPERATION + "/slim",
          "/steps/reorder/slim",
          "/objectives/reorder/slim"
        }) {
      mockMvc
          .perform(
              put(mission + leaf)
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isUnauthorized());
    }
    for (String leaf :
        new String[] {
          "/units/" + ABSENT_OPERATION + "/slim",
          "/frequencies/" + ABSENT_OPERATION + "/slim",
          "/managers/" + ABSENT_OPERATION + "/slim",
          "/steps/" + ABSENT_OPERATION + "/slim",
          "/objectives/" + ABSENT_OPERATION + "/slim"
        }) {
      mockMvc.perform(delete(mission + leaf).with(csrf())).andExpect(status().isUnauthorized());
    }
    mockMvc
        .perform(
            patch(mission + "/steps/" + ABSENT_OPERATION + "/done/slim")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"done\":true}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The one read in phase V, pinned on its own because it sits behind the {@code permitAll} seam.
   *
   * <p>{@code GET /api/v1/missions/**} is {@code permitAll}, so this request is dispatched rather
   * than turned away at the entry point, and whatever the method guard then decides is what the
   * runbook's table and the nightly probe have to say. Phase M's lesson, and phase S's: the number
   * is measured here first and written down afterwards.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousUnitShipOptions() throws Exception {
    mockMvc
        .perform(get("/api/v1/missions/" + ABSENT_MISSION + "/unit-ship-options"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The three Lager writes phase U opens, refused without a token.
   *
   * <p>Sammel-Ausbuchen, Sammel-Umbuchen, and the earmark a Logistiker sets on a stock row. The
   * allocation is three verbs on one path and all three were refused, which is what made it the
   * worst of them: the save loop is sequential and version-chained, so the first row fails, {@code
   * partial} stays at zero and <em>nothing</em> is ever written.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousInventoryBulkAndAllocationWithUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/inventory/bulk-checkout")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[]}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/inventory/bulk-rebook")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[],\"mode\":\"LOCATION\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/inventory/" + ABSENT_OPERATION + "/allocation")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"JOB_ORDER\",\"targetId\":\"" + ABSENT_MISSION + "\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            patch("/api/v1/inventory/" + ABSENT_OPERATION + "/allocation")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"JOB_ORDER\",\"targetId\":\"" + ABSENT_MISSION + "\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            delete("/api/v1/inventory/" + ABSENT_OPERATION + "/allocation")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"JOB_ORDER\",\"targetId\":\"" + ABSENT_MISSION + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The Auftrags-Familie phase T opens, refused without a token.
   *
   * <p>Nine paths, and the audit's own block: four reads that made a working screen look empty or
   * absent, and five writes that discarded what a Logistiker had typed. They are pinned together
   * because they were admitted together — the Zusagen list carries its own upsert and withdrawal on
   * the same path, and the Bestandszeilen read is what makes a Material-Übergabe submittable at
   * all, an Übergabe being what closes an Auftrag.
   *
   * <p>All nine answer {@code 401}: nothing under {@code /api/v1/orders} is {@code permitAll}
   * except the item catalogue and the two creates, and those are {@code authenticated} explicitly.
   * Unlike phase S's pickers there is no seam to fall through — the entry point turns every one of
   * these away before dispatch. Asserted rather than assumed, in the order this runbook fixed after
   * phase M.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousJobOrderFamilyWithUnauthorized() throws Exception {
    // The four reads.
    mockMvc.perform(get("/api/v1/orders/material-demand")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/orders/" + ABSENT_OPERATION + "/item-stock"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/orders/" + ABSENT_OPERATION + "/claims"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            get(
                "/api/v1/orders/"
                    + ABSENT_OPERATION
                    + "/materials/"
                    + ABSENT_MISSION
                    + "/inventory"))
        .andExpect(status().isUnauthorized());
    // The five writes. Bodies are shaped enough to reach the security gate and no further — an
    // anonymous request never gets as far as validation.
    mockMvc
        .perform(
            post("/api/v1/orders/" + ABSENT_OPERATION + "/claims")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"materialId\":\""
                        + ABSENT_MISSION
                        + "\",\"qualityRequirement\":\"NONE\",\"amount\":1}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            delete("/api/v1/orders/" + ABSENT_OPERATION + "/claims/" + ABSENT_MISSION).with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/orders/" + ABSENT_OPERATION + "/handovers")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientHandle\":\"x\",\"items\":[]}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/orders/" + ABSENT_OPERATION + "/item-handovers")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientHandle\":\"x\",\"propertyEntries\":[]}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/orders/" + ABSENT_OPERATION + "/items/" + ABSENT_MISSION + "/production")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1,\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/orders/" + ABSENT_OPERATION + "/items")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[]}"))
        .andExpect(status().isUnauthorized());
    // A query parameter, not a body, and no version: the service reorders the whole queue under a
    // pessimistic write lock, so there is nothing for an optimistic version to guard.
    mockMvc
        .perform(put("/api/v1/orders/" + ABSENT_OPERATION + "/priority?priority=1").with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The member's own two settings and the Aushang's read marker, refused without a token.
   *
   * <p>Phase Q, and the quietest gap this allow-list has produced. Both settings rows are drawn
   * {@code enabled} only once their value has arrived, and the {@code GET} that would deliver it
   * was admitted by no rule — so it answered {@code 404}, the app logged it, and the rows sat in
   * exactly the state a never-set value produces. Nobody could report it as a failure.
   *
   * <p>Read and write are pinned together for both, because they were admitted together: the two
   * rows share one optimistic-lock version, and a client that could write but not read would echo
   * {@code 0}.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousMemberPreferenceReadsAndWritesWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/users/me/payout-preference")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/users/me/payout-preference")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preference\":\"PAYOUT\",\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/users/me/blueprint-sharing")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/users/me/blueprint-sharing")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shareBlueprintsGlobally\":true,\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(put("/api/v1/users/me/read-announcement/" + ABSENT_OPERATION).with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The Freigabe-Limits, refused without a token.
   *
   * <p>Four leaves under one allow-list rule (phase P), and the gap they close is the quietest one
   * this vhost has had: the account's {@code /settings} GET that carries their current values was
   * admitted in phase 3, so the section drew correctly and only the writes answered 404. Nothing in
   * the runbook named {@code approval-limit} at all — neither admitting it nor excluding it.
   *
   * <p>Both verbs on each leaf, because the carve-out opens both and a test that checked only the
   * PUT would say nothing about the DELETE that clears a ceiling.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousApprovalLimitWritesWithUnauthorized() throws Exception {
    String stem = "/api/v1/org-units/bank/accounts/" + ABSENT_OPERATION + "/approval-limit/";
    for (String leaf :
        new String[] {
          "all-members", "area-members", "role/KOMMANDOLEITER", "user/" + ABSENT_OPERATION
        }) {
      mockMvc
          .perform(
              put(stem + leaf)
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"limit\":1000}"))
          .andExpect(status().isUnauthorized());
      mockMvc.perform(delete(stem + leaf).with(csrf())).andExpect(status().isUnauthorized());
    }
  }

  /**
   * The Verwaltung's direct booking, refused without a token.
   *
   * <p>Four paths phase O admits, and the phase is a correction rather than an addition: they were
   * excluded because the runbook said no artboard drew them, and design chapter 12 artboard 9 draws
   * exactly the sheet the app shipped. So the interesting thing to pin is not that they are refused
   * — it is that they are refused with <b>401</b> and not 404, which is what says the allow-list
   * actually admits them now.
   *
   * <p>All four gate on {@code hasRole('BANK_EMPLOYEE')}; the three bookings add a per-account
   * grant that anonymous never reaches. The bank-admin paths beside them stay 404 and are asserted
   * elsewhere — that exclusion is a real owner decision and this phase does not touch it.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousDirectBookingWithUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/bank/deposits")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountId\":\""
                        + ABSENT_OPERATION
                        + "\",\"holderId\":\""
                        + ABSENT_OPERATION
                        + "\",\"amount\":1}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/bank/withdrawals")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountId\":\""
                        + ABSENT_OPERATION
                        + "\",\"holderId\":\""
                        + ABSENT_OPERATION
                        + "\",\"amount\":1}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/bank/transfers")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceAccountId\":\""
                        + ABSENT_OPERATION
                        + "\",\"sourceHolderId\":\""
                        + ABSENT_OPERATION
                        + "\",\"destinationAccountId\":\""
                        + ABSENT_OPERATION
                        + "\",\"destinationHolderId\":\""
                        + ABSENT_OPERATION
                        + "\",\"amount\":1}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/bank/transfer-fee-rate")).andExpect(status().isUnauthorized());
  }

  /**
   * The Materialsammelübersicht and the crew removal, refused without a token.
   *
   * <p>Five paths phase N admits: the collection read, its two unlinks, the delivered PATCH that
   * belongs to the same screen but lives on {@code /inventory}, and the {@code /slim} crew removal.
   * REQ-SEC-037 wants every admitted path pinned here, and phase M is the reason it is not optional
   * — that phase wrote its expected-status table by reasoning about the form around the field
   * instead of the rule that judges the caller, the nightly probe was generated from the table, and
   * both carried the same wrong number for three nights.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousMaterialCollectionAndCrewRemovalWithUnauthorized() throws Exception {
    String order = "/api/v1/orders/" + ABSENT_OPERATION;
    mockMvc.perform(get(order + "/material-collection")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete(order + "/inventory/" + ABSENT_OPERATION + "/unlink").with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete(order + "/materials/" + ABSENT_OPERATION).with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            patch("/api/v1/inventory/" + ABSENT_OPERATION + "/delivered")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"delivered\":true,\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            delete(
                    "/api/v1/missions/"
                        + ABSENT_OPERATION
                        + "/units/"
                        + ABSENT_OPERATION
                        + "/crew/"
                        + ABSENT_OPERATION
                        + "/slim")
                .with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The home-location list is refused with {@code 403}, not {@code 401}.
   *
   * <p>Same shape as the Finanzen paths: `/api/v1/locations/**` is `permitAll` in the filter chain,
   * so the request is dispatched and the method-level guard refuses it — which
   * `GlobalExceptionHandler` renders as `403`, and nothing upgrades to `401` because the MVC advice
   * has already handled it. The number is what the runbook's table has to say, or the paste
   * verification reports a difference that is not one.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousHomeLocationsWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/locations/home-locations")).andExpect(status().isUnauthorized());
  }

  /**
   * The refinery-location list is refused with {@code 403} for the same structural reason as the
   * home-location list above it — two subreads of one prefix, identical in shape.
   *
   * <p>Phase M admits it as the create form's Raffinerie-Picker and recorded it as {@code 401}. It
   * has never answered that: `/api/v1/locations/**` is `permitAll` in the chain, so the request is
   * dispatched, the method-level `isAuthenticated()` refuses it at the method seam, and
   * `GlobalExceptionHandler` renders that as `403`. The number was reasoned from the gated form the
   * picker belongs to rather than read off the rule that judges the caller, and the nightly probe
   * inherited it — three red runs against a vhost that was configured correctly.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousRefineryLocationsWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/locations/refineries")).andExpect(status().isUnauthorized());
  }

  /**
   * The Hangar's own-ship writes, which phase 3 opens on the vhost.
   *
   * <p>The reads beside them were already covered; what is new is that the vhost now lets these
   * verbs through, so the method gate is the only thing between an anonymous caller and somebody
   * else's hangar.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousShipWritesWithUnauthorized() throws Exception {
    String body = "{\"insurance\":\"LTI\",\"shipTypeId\":\"" + ABSENT_OPERATION + "\"}";
    mockMvc
        .perform(
            post("/api/v1/hangar/ships")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/hangar/ships/" + ABSENT_OPERATION)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete("/api/v1/hangar/ships/" + ABSENT_OPERATION).with(csrf()))
        .andExpect(status().isUnauthorized());
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
