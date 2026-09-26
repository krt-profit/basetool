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
 * Pins the status an anonymous caller gets from each path the API vhost allow-lists (REQ-SEC-037).
 *
 * <p>A path refused by the filter chain answers {@code 401}; one dispatched under a {@code
 * permitAll} stem and refused at the method seam answers {@code 403}. The two {@code GET}-scoped
 * anonymous reads answer {@code 200}, their {@code HEAD} {@code 401}.
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
   * The Lager tree, the Auftrag queue and the org bank reads are refused without a token.
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
   * The me-scoped and org-scoped hangar reads are refused without a token.
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
   * The announcement read is refused without a token through the {@code
   * anyRequest().authenticated()} catch-all, although it has no {@code @PreAuthorize}.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousAnnouncementWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/announcement")).andExpect(status().isUnauthorized());
  }

  /**
   * The notification inbox, badge count and push stream are refused without a token.
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
   * The two me-scoped Beförderung reads are refused without a token (REQ-SEC-037).
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
   * The Raffinerie reads and booking are refused without a token (REQ-SEC-037).
   *
   * @param path the allow-listed refinery path
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/refinery-orders/my-orders",
        "/api/v1/refinery-orders/all",
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
   * Creating a refinery order is refused without a token with {@code 401}, before the body is
   * parsed.
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
   * The four Materialbörse reads are refused without a token (REQ-SEC-037).
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
   * The served-version floor answers {@code 200} without a token, so an outdated app can still
   * learn that it must update.
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
   * A {@code HEAD} on the version floor is refused, because the anonymous rule is {@code
   * GET}-scoped (REQ-SEC-032).
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAHeadOnTheVersionPolicy() throws Exception {
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(
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
   * The four Operationen reads answer {@code 401}: no chain matcher names them, so the
   * authenticated catch-all refuses them before dispatch.
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
   * The anonymous status of each game-data catalogue the vhost admits; these are {@code permitAll}
   * master data.
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
   * The refining-method list answers {@code 200} anonymously: {@code permitAll} master data without
   * member or org-unit content.
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
   * The four participant writes are refused without a token with {@code 401} at the entry point.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousParticipationWritesOnAnAbsentRow() throws Exception {
    String participant =
        "/api/v1/missions/" + ABSENT_OPERATION + "/participants/" + ABSENT_OPERATION;
    mockMvc
        .perform(delete(participant + "/slim").with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(post(participant + "/check-in/slim").with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(post(participant + "/check-out/slim").with(csrf()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put(participant + "/payout-preference/slim")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preference\":\"PAYOUT\"}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * Booking an Einsatz expense and confirming a payout are refused without a token.
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
   * The account-settings reads and writes are refused without a token.
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
   * The job order's assignee and status changes are refused without a token.
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
   * The four form pickers are refused without a token.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousPickerReadsWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/orders/lookup")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/operations/lookup")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/missions/lookup")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/job-types")).andExpect(status().isUnauthorized());
  }

  /**
   * The two edit paths are refused without a token.
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
   * The blueprint, Fleetview-import and settings paths: the settings read answers anonymously with
   * the two job-order age thresholds, everything else is refused without a token.
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
    mockMvc
        .perform(get("/api/v1/settings/job_order.age_yellow_days"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/settings/job_order.age_red_days"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The Handel paths without a token: the price screens under the {@code permitAll} materials
   * prefix are refused at the method seam, the Materialbörse and terminal catalogue at the entry
   * point.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousTradeFamily() throws Exception {
    mockMvc.perform(get("/api/v1/materials/prices-overview")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/materials/matrix")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/materials/profit-calculation"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/materials/" + ABSENT_MISSION + "/prices"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/materials/" + ABSENT_MISSION))
        .andExpect(status().isUnauthorized());
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
   * The Einsatz planning writes are refused without a token with {@code 401} at the entry point.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousMissionPlanningWrites() throws Exception {
    String mission = "/api/v1/missions/" + ABSENT_MISSION;
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
            post(mission + "/participants/by-id/slim")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + ABSENT_OPERATION + "\"}"))
        .andExpect(status().isUnauthorized());
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
   * The one Einsatz planning read, dispatched under the {@code permitAll} missions prefix, is
   * refused by its method guard.
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
   * The bulk book-out, bulk transfer and stock earmark writes are refused without a token.
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
            post("/api/v1/inventory/bulk-org-unit")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[]}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/inventory/" + ABSENT_OPERATION + "/org-unit")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
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
   * The nine job-order paths (claims, stock lines and handover-related reads and writes) are
   * refused without a token with {@code 401}.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousJobOrderFamilyWithUnauthorized() throws Exception {
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
    mockMvc
        .perform(put("/api/v1/orders/" + ABSENT_OPERATION + "/priority?priority=1").with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The member's own two settings and the Aushang read marker are refused without a token, for both
   * read and write.
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
    mockMvc.perform(get("/api/v1/users/me/rsi-handle")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/v1/users/me/rsi-handle")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rsiHandle\":\"Some_Handle\",\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(put("/api/v1/users/me/read-announcement/" + ABSENT_OPERATION).with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The approval-limit paths are refused without a token, for both {@code PUT} and {@code DELETE}.
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
   * The bank's direct booking paths are refused without a token with {@code 401}, which shows the
   * vhost admits them.
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
   * The material-collection overview, its unlinks, the delivered patch and the crew removal are
   * refused without a token.
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
   * The home-location list is refused with {@code 403}: dispatched under the {@code permitAll}
   * locations prefix and refused by its method guard.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousHomeLocationsWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/locations/home-locations")).andExpect(status().isUnauthorized());
  }

  /**
   * The refinery-location list is refused with {@code 403}, for the same reason as the
   * home-location list.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @WithAnonymousUser
  void shouldRefuseAnonymousRefineryLocationsWithUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/locations/refineries")).andExpect(status().isUnauthorized());
  }

  /**
   * The hangar's own-ship writes are refused without a token.
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
   * The first writes the vhost admits answer {@code 401} without a token, like the reads.
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
