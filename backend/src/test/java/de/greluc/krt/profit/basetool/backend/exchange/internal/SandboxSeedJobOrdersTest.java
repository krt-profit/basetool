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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCreateOptionsDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.AuecProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReadSet;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRelation;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

/**
 * The job-order part of the sandbox seed against the migrated schema (REQ-XCH-029): the seed runs
 * in the test's transaction, as {@code docker/sandbox/seed.sh} runs it in one, and is rolled back
 * afterwards. The shared test database may already hold a Profit area; the seed then hangs Sandbox
 * Squadron and its aUEC orders under that area instead of Sandbox Profit, as it does on any
 * database with a Profit area.
 */
@SpringBootTest
@Transactional
class SandboxSeedJobOrdersTest {

  private static final Path SEED = Path.of("../docker/sandbox/seed.sql");

  /** The psql variable {@code seed.sh} passes the Terms of Use version in. */
  private static final String TERMS_VERSION_VARIABLE = ":'terms_version'";

  private static final String CLIENT = "sandbox-client";

  /** The installation the caller relays from; no check of these tests reads it. */
  private static final String INSTALLATION = "sandbox-seed-test-installation";

  private static final UUID MEMBER = UUID.fromString("5a4d0000-0000-4000-8000-000000000001");
  private static final UUID MEMBER_2 = UUID.fromString("5a4d0000-0000-4000-8000-000000000002");
  private static final UUID LOGISTICIAN = UUID.fromString("5a4d0000-0000-4000-8000-000000000004");
  private static final UUID IRIDIUM = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SANDBOX_SQUADRON =
      UUID.fromString("5a4d0000-0000-4000-8000-000000000101");
  private static final UUID AUEC_ORDER = UUID.fromString("5a4d0000-0000-4000-8000-000000002204");
  private static final UUID CLOSED_ORDER = UUID.fromString("5a4d0000-0000-4000-8000-000000002205");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ExchangeJobOrderCreateService createOptions;
  @Autowired private ExchangeJobOrderChangeService changes;
  @Autowired private JobOrderExchangeReads reads;
  @Autowired private JobOrderExchangeReadSet readSet;

  @BeforeEach
  void applySeed() throws IOException {
    EncodedResource seed =
        new EncodedResource(
            new ByteArrayResource(seedSql().getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8);
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              ScriptUtils.executeSqlScript(connection, seed);
              return null;
            });
  }

  @Test
  void theLogisticianCreatesForIridiumWithTheProfitUnitsOnOffer() {
    ExchangeJobOrderCreateOptionsDto options = createOptions.options(caller(LOGISTICIAN));

    UUID profitArea = profitArea();
    assertThat(options.requestingUnits())
        .extracting(ExchangeOrgUnitDto::id)
        .containsExactly(IRIDIUM);
    assertThat(options.responsibleUnits())
        .extracting(ExchangeOrgUnitDto::id)
        .contains(profitArea, SANDBOX_SQUADRON);
    assertThat(options.suggestedResponsible()).isEqualTo(profitArea);
    assertThat(options.writesEnabled()).isTrue();
  }

  @Test
  void theMembersMayCreateNoOrder() {
    for (UUID member : List.of(MEMBER, MEMBER_2)) {
      ExchangeJobOrderCreateOptionsDto options = createOptions.options(caller(member));

      assertThat(options.requestingUnits()).isEmpty();
      assertThat(options.responsibleUnits()).isEmpty();
    }
  }

  @Test
  void theAuecOrderReadsAPartialRequesterHandover() {
    JobOrderExchangeRead order = read(AUEC_ORDER);

    assertThat(order.kind()).isEqualTo(JobOrderType.AUEC);
    assertThat(order.status()).isEqualTo(JobOrderStatus.IN_PROGRESS);
    assertThat(order.requestingUnitId()).isEqualTo(IRIDIUM);
    assertThat(order.responsibleUnitId()).isEqualTo(profitArea());
    assertThat(order.responsibilityInvalid()).isFalse();
    assertThat(order.progress())
        .isInstanceOfSatisfying(
            AuecProgress.class,
            progress -> {
              assertThat(progress.target()).isEqualByComparingTo("250000");
              assertThat(progress.netHandedOver()).isEqualByComparingTo("100000");
              assertThat(progress.remaining()).isEqualByComparingTo("150000");
              assertThat(progress.overpaid()).isZero();
              assertThat(progress.settlementOpen()).isFalse();
            });
  }

  @Test
  void aCancelOfTheAuecOrderIsRefusedOnceMoneyMoved() {
    long lifecycle = read(AUEC_ORDER).versions().lifecycle();
    actAs(LOGISTICIAN);

    assertThatThrownBy(() -> changes.cancel(caller(LOGISTICIAN), AUEC_ORDER, lifecycle))
        .isInstanceOfSatisfying(
            ExchangeProblemException.class,
            refusal ->
                assertThat(refusal.code())
                    .isEqualTo(ExchangeProblemException.JOB_ORDER_MONEY_STARTED));
  }

  @Test
  void sandboxMemberWorksOnTheAuecOrderWithAnOwnNote() {
    assertThat(read(AUEC_ORDER).assigneeIds()).containsExactly(MEMBER);
    assertThat(readSet.relations(readSet.relatingMember(MEMBER), List.of(AUEC_ORDER)))
        .containsEntry(AUEC_ORDER, Set.of(JobOrderRelation.REQUESTING, JobOrderRelation.ASSIGNEE));
    assertThat(readSet.ownNotes(MEMBER, List.of(AUEC_ORDER))).containsOnlyKeys(AUEC_ORDER);
    assertThat(readSet.ownNotes(LOGISTICIAN, List.of(AUEC_ORDER))).isEmpty();
  }

  @Test
  void theCancelledOrderStaysInTheClientSet() {
    JobOrderExchangeRead order = read(CLOSED_ORDER);

    assertThat(order.kind()).isEqualTo(JobOrderType.AUEC);
    assertThat(order.status()).isEqualTo(JobOrderStatus.CANCELLED);
    assertThat(order.requestingUnitId()).isEqualTo(IRIDIUM);
    assertThat(
            jdbc.queryForObject(
                "SELECT exchange_job_order_in_client_set(?)", Boolean.class, CLOSED_ORDER))
        .isTrue();
  }

  @Test
  void aCommentOnTheCancelledOrderIsRefusedAsClosed() {
    long version = read(CLOSED_ORDER).versions().comment();
    actAs(LOGISTICIAN);

    assertThatThrownBy(
            () -> changes.comment(caller(LOGISTICIAN), CLOSED_ORDER, "Too late", version))
        .isInstanceOfSatisfying(
            ExchangeProblemException.class,
            refusal ->
                assertThat(refusal.code()).isEqualTo(ExchangeProblemException.JOB_ORDER_CLOSED));
  }

  @Test
  void aSecondRunChangesNoFigure() throws IOException {
    applySeed();

    assertThat(read(AUEC_ORDER).progress())
        .isInstanceOfSatisfying(
            AuecProgress.class,
            progress -> assertThat(progress.netHandedOver()).isEqualByComparingTo("100000"));
    assertThat(read(AUEC_ORDER).assigneeIds()).containsExactly(MEMBER);
    assertThat(createOptions.options(caller(LOGISTICIAN)).requestingUnits())
        .extracting(ExchangeOrgUnitDto::id)
        .containsExactly(IRIDIUM);
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private static @NotNull ExchangeCaller caller(@NotNull UUID member) {
    return new ExchangeCaller(member, CLIENT, INSTALLATION);
  }

  /**
   * Makes the member the authenticated subject, from which the writes' read check takes the acting
   * member.
   *
   * @param member the acting member
   */
  private static void actAs(@NotNull UUID member) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("sandbox-seed-test")
                    .header("alg", "none")
                    .subject(member.toString())
                    .build()));
  }

  private @NotNull JobOrderExchangeRead read(@NotNull UUID order) {
    return Objects.requireNonNull(reads.project(List.of(order)).get(order), "seeded order");
  }

  /**
   * Reads the active Profit area the seeded units hang under: Sandbox Profit on a fresh sandbox,
   * the test database's own area here when it has one.
   *
   * @return the area's id
   */
  private @NotNull UUID profitArea() {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT id FROM org_unit WHERE kind = 'BEREICH' AND department = 'PROFIT' AND active",
            UUID.class));
  }

  /**
   * Reads the seed with the Terms of Use version filled in from the backend's own properties, as
   * {@code seed.sh} passes it to psql.
   *
   * @return the seed's statements
   * @throws IOException when the seed or the properties cannot be read
   */
  private static @NotNull String seedSql() throws IOException {
    Properties terms = new Properties();
    try (InputStream in = new ClassPathResource("terms-version.properties").getInputStream()) {
      terms.load(in);
    }
    String version =
        Objects.requireNonNull(terms.getProperty("basetool.terms.version"), "terms version");
    return Files.readString(SEED).replace(TERMS_VERSION_VARIABLE, "'" + version + "'");
  }
}
