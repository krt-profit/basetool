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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderInformationCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderReadCheck;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderTargetCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrganisationsleitungRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import de.greluc.krt.profit.basetool.backend.support.StatementCounter;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The origin markers against Postgres: an order a client created names the client, a client's
 * target change and a client's comment write record the client, a web write of the comment clears
 * it, and a page of orders names its clients in one query (REQ-ORDERS-078).
 */
@SpringBootTest
@Transactional
class JobOrderClientOriginIntegrationTest {

  private static final String APP = "origin-app-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String APP_NAME = "Origin Kit";
  private static final String OTHER_APP =
      "origin-other-" + UUID.randomUUID().toString().substring(0, 8);

  @Autowired private JobOrderCreateCommand createCommand;
  @Autowired private JobOrderTargetCommand targetCommand;
  @Autowired private JobOrderInformationCommand informationCommand;
  @Autowired private JobOrderMoneyOrderChanges moneyOrderChanges;
  @Autowired private JobOrderMoneyQueries moneyQueries;
  @Autowired private JobOrderQueryService queries;
  @Autowired private JobOrderStockProjectionService projection;
  @Autowired private JobOrderRepository orders;
  @Autowired private JobOrderMoneyTargetRepository targets;
  @Autowired private OrgUnitRepository units;
  @Autowired private OrganisationsleitungRepository leitungen;
  @Autowired private UserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private WriterRuleFixtures fixtures;
  private WriterRuleFixtures.Units seeded;
  private User member;
  private User admin;

  @BeforeEach
  void seed() {
    fixtures = new WriterRuleFixtures(units, leitungen, users, roles, memberships, converter);
    seeded = fixtures.seedUnits();
    member = fixtures.member(true, false);
    fixtures.seat(member, seeded.staffel(), MembershipRole.MEMBER, true);
    admin = fixtures.member(true, true);
    jdbc.update(
        "INSERT INTO exchange_client (id, client_id, display_name, status, created_at)"
            + " VALUES (?, ?, ?, 'ACTIVE', now())",
        UUID.randomUUID(),
        APP,
        APP_NAME);
  }

  @AfterEach
  void signOut() {
    WriterRuleFixtures.signOut();
  }

  @Test
  void anOrderAClientCreatedNamesTheClientOnTheOrderAndOnItsComment() {
    UUID viaClient = createThroughClient(APP, "Bring it to Area18");

    JobOrderDto created = readAsAdministrator(viaClient);

    assertThat(created.createdClient()).isEqualTo(APP_NAME);
    assertThat(created.commentClient()).isEqualTo(APP_NAME);
  }

  @Test
  void aClientTheRegistryNoLongerHoldsIsNamedByItsId() {
    UUID viaClient = createThroughClient(OTHER_APP, null);

    JobOrderDto created = readAsAdministrator(viaClient);

    assertThat(created.createdClient()).isEqualTo(OTHER_APP);
    assertThat(created.commentClient()).isNull();
  }

  @Test
  void aClientsTargetChangeRecordsTheClientAndAWebChangeNone() {
    UUID order = createThroughClient(APP, null);
    WriterRuleFixtures.signInAsExchangeMember(member);
    targetCommand.changeTarget(
        order,
        new BigDecimal("300000"),
        0L,
        JobOrderWriterRule.DIRECT_LOGISTICIAN,
        JobOrderReadCheck.CONTEXTUAL,
        client(APP));
    fixtures.signInAsWebMember(member);
    entityManager.flush();
    long targetVersion = targets.findById(order).orElseThrow().getVersion();
    moneyOrderChanges.target(order, new UpdateJobOrderMoneyTargetDto("350000", targetVersion));

    WriterRuleFixtures.signInAsAdministrator(admin);
    List<JobOrderMoneyTargetChangeView> history = moneyQueries.getMoney(order).targetChanges();

    assertThat(history)
        .extracting(JobOrderMoneyTargetChangeView::target, JobOrderMoneyTargetChangeView::client)
        .containsExactly(tuple("300000", APP_NAME), tuple("350000", null));
  }

  @Test
  void aClientsCommentShowsTheClientUntilAWebWriteOfTheComment() {
    UUID order = createThroughClient(APP, null);
    WriterRuleFixtures.signInAsExchangeMember(member);
    informationCommand.changeComment(
        order,
        "Deliver to Area18",
        0L,
        JobOrderWriterRule.DIRECT_LOGISTICIAN,
        JobOrderReadCheck.CONTEXTUAL,
        client(APP));

    assertThat(readAsAdministrator(order).commentClient()).isEqualTo(APP_NAME);

    fixtures.signInAsWebMember(member);
    moneyOrderChanges.information(
        order,
        new UpdateJobOrderMoneyInformationDto("Origin contact", "Deliver to Lorville", 0L, 1L));

    JobOrderDto written = readAsAdministrator(order);
    assertThat(written.comment()).isEqualTo("Deliver to Lorville");
    assertThat(written.commentClient()).isNull();
    assertThat(written.createdClient()).isEqualTo(APP_NAME);
  }

  @Test
  void aWebContactChangeNeitherConflictsWithAClientsCommentNorClearsItsMarker() {
    UUID order = createThroughClient(APP, null);
    WriterRuleFixtures.signInAsExchangeMember(member);
    changeCommentThroughClient(order, "Deliver to Area18", 0L);

    fixtures.signInAsWebMember(member);
    moneyOrderChanges.information(
        order, new UpdateJobOrderMoneyInformationDto("Web contact", "Deliver to Area18", 0L, 1L));
    assertThat(readAsAdministrator(order).commentClient()).isEqualTo(APP_NAME);

    WriterRuleFixtures.signInAsExchangeMember(member);
    changeCommentThroughClient(order, "Deliver to Lorville", 1L);

    JobOrderDto written = readAsAdministrator(order);
    assertThat(written.comment()).isEqualTo("Deliver to Lorville");
    assertThat(written.handle()).isEqualTo("Web contact");
    assertThat(written.commentClient()).isEqualTo(APP_NAME);
  }

  private void changeCommentThroughClient(UUID order, String comment, long commentVersion) {
    informationCommand.changeComment(
        order,
        comment,
        commentVersion,
        JobOrderWriterRule.DIRECT_LOGISTICIAN,
        JobOrderReadCheck.CONTEXTUAL,
        client(APP));
  }

  /**
   * The page's creators are set to clients no earlier step of the transaction named, since the
   * created notices already read the names of the clients the orders were created through.
   */
  @Test
  void aPageOfOrdersNamesItsClientsInOneQuery() throws Exception {
    List<UUID> clientOrders = new ArrayList<>();
    List<UUID> webOrders = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      clientOrders.add(createThroughClient(APP, null));
      webOrders.add(createThroughClient(APP, null));
    }
    entityManager.flush();
    jdbc.update(
        "UPDATE job_order SET created_by_client_id = NULL, created_by_member_id = NULL,"
            + " create_idempotency_key = NULL WHERE id IN (?, ?, ?)",
        webOrders.toArray());
    String first = "unnamed-a-" + UUID.randomUUID().toString().substring(0, 8);
    String second = "unnamed-b-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update(
        "UPDATE job_order SET created_by_client_id = CASE id WHEN ? THEN ? ELSE ? END"
            + " WHERE id IN (?, ?, ?)",
        clientOrders.get(1),
        second,
        first,
        clientOrders.get(0),
        clientOrders.get(1),
        clientOrders.get(2));
    WriterRuleFixtures.signInAsAdministrator(admin);

    long web = statementsToMap(webOrders);
    long named = statementsToMap(clientOrders);

    assertThat(named - web).isEqualTo(1L);
    assertThat(queries.getJobOrderById(clientOrders.get(1)).createdClient()).isEqualTo(second);
  }

  private long statementsToMap(List<UUID> ids) throws Exception {
    entityManager.flush();
    entityManager.clear();
    List<JobOrder> page = orders.findAllById(ids);
    return StatementCounter.preparedBy(() -> projection.mapPageWithStock(new PageImpl<>(page)));
  }

  private JobOrderDto readAsAdministrator(UUID order) {
    WriterRuleFixtures.signInAsAdministrator(admin);
    return queries.getJobOrderById(order);
  }

  private UUID createThroughClient(String clientId, String comment) {
    return createCommand.createAuec(
        new JobOrderCreateCommand.AuecOrder(
            seeded.profitStaffel().getId(),
            seeded.staffel().getId(),
            "Origin contact",
            comment,
            new BigDecimal("250000")),
        JobOrderWriterRule.DIRECT_LOGISTICIAN,
        client(clientId),
        "origin-key-" + UUID.randomUUID());
  }

  private JobOrderActor.Client client(String clientId) {
    return new JobOrderActor.Client(member.getId(), clientId);
  }
}
