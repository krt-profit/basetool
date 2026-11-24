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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.ExchangeJobOrderChangeLog;
import de.greluc.krt.profit.basetool.backend.exchange.ExchangeJobOrderSeed;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportService;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The nightly job-order window run against PostgreSQL (REQ-OXCH-003, REQ-OXCH-010): an order whose
 * 7 days after closing ended is announced where it was last announced, so a feed page resolves it
 * to a tombstone, and an order outside the client set loses its creating member and key and so
 * leaves the creator's Art. 15 export (REQ-SEC-058). Time passing is simulated by moving {@code
 * closed_at} back after the fingerprint was written inside the window, and every statement commits
 * on its own.
 */
@SpringBootTest
class JobOrderWindowRunIntegrationTest {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String CLIENT = "window-client";
  private static final String LEFT_KEY = "00000000-0000-4000-8000-000000003649";
  private static final String INSIDE_KEY = "00000000-0000-4000-8000-000000003650";
  private static final String SETTLING_KEY = "00000000-0000-4000-8000-000000003651";
  private static final String CLIENT_CREATES_SECTION = "jobOrdersCreatedThroughClients";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JobOrderFingerprintRecomputation recomputation;
  @Autowired private JobOrderWindowRun window;
  @Autowired private DataExportService exports;

  private ExchangeJobOrderSeed seed;
  private ExchangeJobOrderChangeLog changes;
  private MockMvc mvc;
  private UUID requesting;
  private UUID responsible;
  private UUID assignee;
  private UUID creatingMember;
  private UUID writer;

  @BeforeEach
  void setUp() {
    seed = new ExchangeJobOrderSeed(context);
    changes = new ExchangeJobOrderChangeLog(jdbc);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    requesting = seed.squadron("Window requesting", null);
    responsible = seed.squadron("Window responsible", seed.getProfit());
    assignee = seed.user("window-assignee", false);
    creatingMember = seed.user("window-creator", false);
    writer = seed.user("window-writer", false);
  }

  @AfterEach
  void clean() {
    seed.close();
  }

  @Test
  void anOrderWhoseSevenDaysEndedIsAnnouncedWhereItWasLastAnnounced() {
    UUID order = closedOrder();
    closedAgo(order, "7 days 1 minute");
    long before = changes.position();

    window.ageOut();

    assertThat(changes.unitsAnnounced(order, before))
        .containsExactlyInAnyOrder(requesting, responsible);
    assertThat(changes.membersAnnounced(order, before)).containsExactly(assignee);
  }

  @Test
  void anOrderClosedJustUnderSevenDaysAgoStaysInTheSet() {
    UUID order = closedOrder();
    closedAgo(order, "6 days 23 hours 59 minutes");
    long before = changes.position();

    window.ageOut();

    assertThat(changes.unitsAnnounced(order, before)).isEmpty();
    assertThat(changes.membersAnnounced(order, before)).isEmpty();
  }

  @Test
  void aRerunAnnouncesNothingAgain() {
    UUID order = closedOrder();
    closedAgo(order, "8 days");
    window.ageOut();
    long before = changes.position();

    window.ageOut();

    assertThat(changes.unitsAnnounced(order, before)).isEmpty();
    assertThat(changes.membersAnnounced(order, before)).isEmpty();
  }

  @Test
  void aReopenedOrderLeavesAgainOnceItsNewSevenDaysEnded() {
    UUID order = closedOrder();
    closedAgo(order, "8 days");
    window.ageOut();
    jdbc.update(
        "UPDATE job_order SET status = 'OPEN', priority = 1, closed_at = NULL,"
            + " lifecycle_version = lifecycle_version + 1 WHERE id = ?",
        order);
    JobOrderFingerprintRuns.drain(jdbc, recomputation, order);
    close(order);
    closedAgo(order, "8 days");
    long before = changes.position();

    window.ageOut();

    assertThat(changes.unitsAnnounced(order, before))
        .containsExactlyInAnyOrder(requesting, responsible);
  }

  @Test
  void anOrderFirstFingerprintedAfterItsSevenDaysIsNeverAnnounced() {
    UUID order = order();
    jdbc.update(
        "UPDATE job_order SET status = 'COMPLETED', priority = NULL,"
            + " closed_at = now() - INTERVAL '30 days' WHERE id = ?",
        order);
    JobOrderFingerprintRuns.drain(jdbc, recomputation, order);
    long before = changes.position();

    window.ageOut();

    assertThat(changes.unitsAnnounced(order, before)).isEmpty();
  }

  @Test
  void anAuecOrderStaysWhileItsSettlementIsOpenAndLeavesThroughItsFingerprintOnceItCloses() {
    UUID order = auecOrder();
    close(order);
    jdbc.update(
        "UPDATE exchange_job_order_fingerprint SET settlement_open = TRUE WHERE job_order_id = ?",
        order);
    closedAgo(order, "8 days");
    long open = changes.position();

    window.ageOut();

    assertThat(changes.unitsAnnounced(order, open)).isEmpty();

    long settling = changes.position();
    jdbc.update(
        "UPDATE exchange_job_order_fingerprint SET fingerprint = ?, changed_at = now(),"
            + " settlement_open = FALSE WHERE job_order_id = ?",
        hash(),
        order);

    assertThat(changes.unitsAnnounced(order, settling))
        .containsExactlyInAnyOrder(requesting, responsible);

    long settled = changes.position();
    window.ageOut();

    assertThat(changes.unitsAnnounced(order, settled)).isEmpty();
  }

  @Test
  void theCreatorAndTheKeyAreClearedOnceTheOrderLeftTheSetAndTheClientStays() {
    UUID left = clientCreated(auecOrder(), LEFT_KEY);
    close(left);
    closedAgo(left, "8 days");
    UUID inside = clientCreated(auecOrder(), INSIDE_KEY);
    close(inside);
    UUID settling = clientCreated(auecOrder(), SETTLING_KEY);
    close(settling);
    jdbc.update(
        "UPDATE exchange_job_order_fingerprint SET settlement_open = TRUE WHERE job_order_id = ?",
        settling);
    closedAgo(settling, "8 days");

    window.ageOut();

    assertThat(creator(left)).isEqualTo(new Creator(CLIENT, null, null));
    assertThat(creator(inside)).isEqualTo(new Creator(CLIENT, creatingMember, INSIDE_KEY));
    assertThat(creator(settling)).isEqualTo(new Creator(CLIENT, creatingMember, SETTLING_KEY));

    jdbc.update(
        "UPDATE exchange_job_order_fingerprint SET fingerprint = ?, changed_at = now(),"
            + " settlement_open = FALSE WHERE job_order_id = ?",
        hash(),
        settling);
    window.ageOut();

    assertThat(creator(settling)).isEqualTo(new Creator(CLIENT, null, null));
  }

  @Test
  void anOrderTheWebReopensAfterTheClearingStaysWithoutItsCreator() throws Exception {
    UUID order = clientCreated(auecOrder(), LEFT_KEY);
    jdbc.update(
        "UPDATE job_order SET status = 'CANCELLED', priority = NULL,"
            + " closed_at = now() - INTERVAL '1 day' WHERE id = ?",
        order);
    JobOrderFingerprintRuns.drain(jdbc, recomputation, order);
    closedAgo(order, "8 days");
    window.ageOut();
    long lifecycle =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT lifecycle_version FROM job_order WHERE id = ?", Long.class, order));

    mvc.perform(
            put("/api/v1/orders/{id}/status", order)
                .with(responsibleWriter())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OPEN\",\"version\":" + lifecycle + "}"))
        .andExpect(status().isOk());

    assertThat(
            jdbc.queryForObject("SELECT status FROM job_order WHERE id = ?", String.class, order))
        .isEqualTo("OPEN");
    assertThat(creator(order)).isEqualTo(new Creator(CLIENT, null, null));
  }

  @Test
  void theCreatorsExportListsAClientCreateOnlyWhileTheOrderKeepsItsCreator() {
    UUID left = clientCreated(auecOrder(), LEFT_KEY);
    close(left);
    UUID inside = clientCreated(auecOrder(), INSIDE_KEY);
    close(inside);

    assertThat(exportedClientCreates())
        .containsExactlyInAnyOrder(displayId(left), displayId(inside));

    closedAgo(left, "8 days");
    window.ageOut();

    assertThat(exportedClientCreates()).containsExactly(displayId(inside));
  }

  /**
   * Reads the orders the creating member's Art. 15 export lists as created through a client.
   *
   * @return the display numbers of those orders, as text
   */
  private List<String> exportedClientCreates() {
    return exports.export(creatingMember).sections().stream()
        .filter(section -> CLIENT_CREATES_SECTION.equals(section.key()))
        .flatMap(section -> section.rows().stream())
        .map(row -> String.valueOf(row.get("job_order")))
        .toList();
  }

  private String displayId(UUID order) {
    return String.valueOf(
        jdbc.queryForObject("SELECT display_id FROM job_order WHERE id = ?", Object.class, order));
  }

  /**
   * Creates an order with an assignee, closes it a day ago and lets the fingerprint worker announce
   * it inside the window.
   *
   * @return the order
   */
  private UUID closedOrder() {
    UUID order = order();
    close(order);
    return order;
  }

  /**
   * Closes an order a day ago and lets the fingerprint worker announce it inside the window.
   *
   * @param order the order
   */
  private void close(UUID order) {
    jdbc.update(
        "UPDATE job_order SET status = 'COMPLETED', priority = NULL,"
            + " closed_at = now() - INTERVAL '1 day' WHERE id = ?",
        order);
    JobOrderFingerprintRuns.drain(jdbc, recomputation, order);
  }

  /**
   * Creates an open aUEC order with a target and an assignee.
   *
   * @return the order
   */
  private UUID auecOrder() {
    return withAssignee(
        seed.order().requesting(requesting).responsible(responsible).auec(1000).create());
  }

  private static byte[] hash() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  /**
   * Records that the fixture client created an order for the creating member under a key.
   *
   * @param order the order
   * @param key the create's {@code Idempotency-Key}
   * @return the order
   */
  private UUID clientCreated(UUID order, String key) {
    jdbc.update(
        "UPDATE job_order SET created_by_client_id = ?, created_by_member_id = ?,"
            + " create_idempotency_key = ? WHERE id = ?",
        CLIENT,
        creatingMember,
        key,
        order);
    return order;
  }

  private Creator creator(UUID order) {
    return jdbc.queryForObject(
        "SELECT created_by_client_id, created_by_member_id, create_idempotency_key"
            + " FROM job_order WHERE id = ?",
        (rs, _) -> new Creator(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3)),
        order);
  }

  private RequestPostProcessor responsibleWriter() {
    return jwt()
        .jwt(token -> token.subject(writer.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, responsible));
  }

  /**
   * What an order stores about its client create.
   *
   * @param clientId the creating client
   * @param memberId the creating member
   * @param key the create's {@code Idempotency-Key}
   */
  private record Creator(
      @Nullable String clientId, @Nullable UUID memberId, @Nullable String key) {}

  private void closedAgo(UUID order, String ago) {
    jdbc.update(
        "UPDATE job_order SET closed_at = now() - CAST(? AS INTERVAL) WHERE id = ?", ago, order);
  }

  private UUID order() {
    return withAssignee(seed.order().requesting(requesting).responsible(responsible).create());
  }

  private UUID withAssignee(UUID order) {
    seed.assignee(order, assignee, null);
    return order;
  }
}
