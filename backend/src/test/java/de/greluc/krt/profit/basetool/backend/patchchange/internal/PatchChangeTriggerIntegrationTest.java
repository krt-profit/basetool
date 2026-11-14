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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCaller;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderFeedService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangePatchChangeRefDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderPageDto;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchKind;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Triggers a patch change against PostgreSQL (REQ-PATCH-005, -006, -007): the record, the refinery
 * cancellation, the audit trail and the exchange feed commit together and name the patch change.
 *
 * <p>Its live times lie in 1999 to 2001, before every other test's rows, so the merge cutoff and
 * the cancellation never reach another test's data; every patch change is removed afterwards.
 */
@SpringBootTest
class PatchChangeTriggerIntegrationTest {

  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("1999-06-01T00:00:00Z");
  private static final Instant AFTER = Instant.parse("2001-06-01T00:00:00Z");

  @Autowired private PatchChangeTrigger trigger;
  @Autowired private PatchChangeLabelCorrection labelCorrection;
  @Autowired private PatchChangeHistory history;
  @Autowired private ExchangeRefineryOrderFeedService refineryFeed;
  @Autowired private RefineryOrderRepository orderRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  private UUID admin;
  private UUID owner;
  private UUID location;

  @BeforeEach
  void setUp() {
    admin = persistUser("patch-admin-");
    owner = persistUser("patch-owner-");
    location = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, location, "Patch refinery " + location);
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject(admin.toString())
            .claim("azp", "basetool-frontend")
            .issuedAt(Instant.now())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    jdbc.update("DELETE FROM patch_change");
    jdbc.update("DELETE FROM refinery_order WHERE owner_id = ?", owner);
    jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", owner, admin);
  }

  @Test
  void theTriggerCancelsTheRunningOrdersStartedBeforeTheLiveTimeAndRecordsWhy() {
    UUID openBefore = persistOrder(RefineryOrderStatus.OPEN, BEFORE);
    UUID runningWithoutStart = persistOrder(RefineryOrderStatus.IN_PROGRESS, null);
    jdbc.update(
        "UPDATE refinery_order SET created_at = ? WHERE id = ?", ts(BEFORE), runningWithoutStart);
    UUID undated = persistOrder(RefineryOrderStatus.OPEN, null);
    jdbc.update("UPDATE refinery_order SET created_at = NULL WHERE id = ?", undated);
    UUID openAfter = persistOrder(RefineryOrderStatus.OPEN, AFTER);
    UUID completedBefore = persistOrder(RefineryOrderStatus.COMPLETED, BEFORE);
    ExchangeCaller caller = new ExchangeCaller(owner, "versekit", "k".repeat(43));
    String cursor = refineryFeed.page(caller, null, 100).nextCursor();

    PatchChangeDto recorded = trigger.trigger(request("0.1", LIVE_AT));

    assertThat(countOf(recorded.counts(), PatchCount.REFINERY_ORDERS_CANCELED))
        .as("undated orders other tests left behind are cancelled too")
        .isGreaterThanOrEqualTo(3);
    assertThat(recorded.counts())
        .extracting(PatchCountDto::kind)
        .contains(PatchCount.MEMBERS_ASKED);
    assertThat(statusOf(openBefore)).isEqualTo(RefineryOrderStatus.CANCELED);
    assertThat(statusOf(runningWithoutStart)).isEqualTo(RefineryOrderStatus.CANCELED);
    assertThat(statusOf(undated)).isEqualTo(RefineryOrderStatus.CANCELED);
    assertThat(reasonOf(undated)).isEqualTo(recorded.id());
    assertThat(statusOf(openAfter)).isEqualTo(RefineryOrderStatus.OPEN);
    assertThat(statusOf(completedBefore)).isEqualTo(RefineryOrderStatus.COMPLETED);
    assertThat(reasonOf(openBefore)).isEqualTo(recorded.id());
    assertThat(reasonOf(openAfter)).isNull();
    assertThat(gameDataVersionOf(openBefore)).isEqualTo(1L);
    assertThat(
            jdbc.queryForList(
                "SELECT entity_key FROM exchange_change WHERE user_id = ?"
                    + " AND source_channel = 'patch' AND source_patch_change = ?",
                String.class,
                owner,
                recorded.id()))
        .containsExactlyInAnyOrder(
            openBefore.toString(), runningWithoutStart.toString(), undated.toString());
    assertThat(auditCount("PATCH_CHANGE_TRIGGERED", recorded.id())).isEqualTo(1L);
    assertThat(auditCount("REFINERY_ORDERS_CANCELED_BY_PATCH", recorded.id())).isEqualTo(1L);

    ExchangeRefineryOrderPageDto feed = refineryFeed.page(caller, cursor, 100);
    assertThat(feed.items())
        .filteredOn(item -> item.id().equals(openBefore.toString()))
        .extracting(ExchangeRefineryOrderDto::canceledByPatch)
        .containsExactly(new ExchangePatchChangeRefDto(recorded.id(), "0.1"));
  }

  @Test
  void aWrongConfirmationRecordsNothing() {
    UUID openBefore = persistOrder(RefineryOrderStatus.OPEN, BEFORE);
    TriggerPatchChangeRequest typo =
        new TriggerPatchChangeRequest(
            "0.1",
            PatchKind.REBUILD,
            LIVE_AT,
            PatchChangeOptions.presetFor(PatchKind.REBUILD),
            "0.2");

    assertThatThrownBy(() -> trigger.trigger(typo))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.patch_change.confirmation_mismatch");
    assertThat(history.history()).isEmpty();
    assertThat(statusOf(openBefore)).isEqualTo(RefineryOrderStatus.OPEN);
  }

  @Test
  void aSecondPatchChangeNeedsAHigherLabelAndALaterLiveTime() {
    trigger.trigger(request("0.2", LIVE_AT));

    assertThatThrownBy(() -> trigger.trigger(request("0.1", AFTER)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.patch_change.label_not_higher");
    assertThatThrownBy(() -> trigger.trigger(request("0.3", BEFORE)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.patch_change.live_at_not_after_previous");
    assertThat(history.history()).extracting(PatchChangeDto::label).containsExactly("0.2");
  }

  @Test
  void anOptionSwitchedOffLeavesItsAreaAlone() {
    UUID openBefore = persistOrder(RefineryOrderStatus.OPEN, BEFORE);
    PatchChangeOptions withoutRefinery = new PatchChangeOptions(Set.of(), false, false, true);

    PatchChangeDto recorded =
        trigger.trigger(
            new TriggerPatchChangeRequest(
                "0.1", PatchKind.REBUILD, LIVE_AT, withoutRefinery, "0.1"));

    assertThat(recorded.counts())
        .extracting(PatchCountDto::kind)
        .containsExactly(PatchCount.MEMBERS_ASKED);
    assertThat(recorded.options()).isEqualTo(withoutRefinery);
    assertThat(statusOf(openBefore)).isEqualTo(RefineryOrderStatus.OPEN);
  }

  @Test
  void theLabelIsCorrectedAndTheFeedNamesTheCorrectedLabel() {
    UUID openBefore = persistOrder(RefineryOrderStatus.OPEN, BEFORE);
    ExchangeCaller caller = new ExchangeCaller(owner, "versekit", "k".repeat(43));
    String cursor = refineryFeed.page(caller, null, 100).nextCursor();
    PatchChangeDto recorded = trigger.trigger(request("0.1", LIVE_AT));

    PatchChangeDto corrected =
        labelCorrection.correct(
            recorded.id(), new CorrectPatchChangeLabelRequest("0.1.1", recorded.version()));

    assertThat(corrected.label()).isEqualTo("0.1.1");
    assertThat(corrected.liveAt()).isEqualTo(LIVE_AT);
    assertThat(auditCount("PATCH_CHANGE_LABEL_CORRECTED", recorded.id())).isEqualTo(1L);
    assertThat(refineryFeed.page(caller, cursor, 100).items())
        .filteredOn(item -> item.id().equals(openBefore.toString()))
        .extracting(ExchangeRefineryOrderDto::canceledByPatch)
        .containsExactly(new ExchangePatchChangeRefDto(recorded.id(), "0.1.1"));
  }

  @Test
  void aStaleVersionCannotCorrectTheLabel() {
    PatchChangeDto recorded = trigger.trigger(request("0.1", LIVE_AT));
    labelCorrection.correct(
        recorded.id(), new CorrectPatchChangeLabelRequest("0.1.1", recorded.version()));

    assertThatThrownBy(() ->
            labelCorrection.correct(
                recorded.id(), new CorrectPatchChangeLabelRequest("0.1.2", recorded.version())))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void thePreviewCountsWithoutWriting() {
    UUID openBefore = persistOrder(RefineryOrderStatus.OPEN, BEFORE);

    PatchChangePreviewDto preview =
        trigger.preview(
            new PatchChangePreviewRequest(
                LIVE_AT, PatchChangeOptions.presetFor(PatchKind.FULL_WIPE)));

    assertThat(countOf(preview.counts(), PatchCount.REFINERY_ORDERS_CANCELED))
        .isGreaterThanOrEqualTo(1);
    assertThat(preview.counts())
        .extracting(PatchCountDto::kind)
        .contains(PatchCount.MEMBERS_ASKED);
    assertThat(statusOf(openBefore)).isEqualTo(RefineryOrderStatus.OPEN);
    assertThat(history.history()).isEmpty();
  }

  /**
   * Builds a rebuild trigger with its preset and a matching confirmation.
   *
   * @param label the label
   * @param liveAt the live time
   * @return the request
   */
  private static TriggerPatchChangeRequest request(String label, Instant liveAt) {
    return new TriggerPatchChangeRequest(
        label, PatchKind.REBUILD, liveAt, PatchChangeOptions.presetFor(PatchKind.REBUILD), label);
  }

  /**
   * Reads one kind's count out of a trigger's or preview's counts.
   *
   * @param counts the counts
   * @param kind the kind
   * @return the kind's count, {@code 0} when it is missing
   */
  private static long countOf(@NotNull List<PatchCountDto> counts, @NotNull PatchCount kind) {
    return counts.stream()
        .filter(count -> count.kind() == kind)
        .mapToLong(PatchCountDto::value)
        .sum();
  }

  /**
   * Persists an active member.
   *
   * @param prefix the username's prefix
   * @return the member's id
   */
  private @NotNull UUID persistUser(@NotNull String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user).getId();
  }

  /**
   * Persists one of the owner's refinery orders at the test refinery.
   *
   * @param status the status
   * @param startedAt the start, or {@code null}
   * @return the order's id
   */
  private @NotNull UUID persistOrder(
      @NotNull RefineryOrderStatus status, @Nullable Instant startedAt) {
    return new TransactionTemplate(transactionManager)
        .execute(_ -> {
          RefineryOrder order = new RefineryOrder();
          order.setOwner(userRepository.getReferenceById(owner));
          order.setLocation(locationRepository.getReferenceById(location));
          order.setStatus(status);
          order.setStartedAt(startedAt);
          return orderRepository.saveAndFlush(order).getId();
        });
  }

  /**
   * Reads an order's status.
   *
   * @param order the order
   * @return its status
   */
  private RefineryOrderStatus statusOf(@NotNull UUID order) {
    return RefineryOrderStatus.valueOf(
        jdbc.queryForObject("SELECT status FROM refinery_order WHERE id = ?", String.class, order));
  }

  /**
   * Reads an order's patch-change cancel reason.
   *
   * @param order the order
   * @return the patch change, or {@code null}
   */
  private @Nullable UUID reasonOf(@NotNull UUID order) {
    return jdbc.queryForObject(
        "SELECT canceled_by_patch_change FROM refinery_order WHERE id = ?", UUID.class, order);
  }

  /**
   * Reads an order's game-data counter.
   *
   * @param order the order
   * @return the counter
   */
  private long gameDataVersionOf(@NotNull UUID order) {
    Long version =
        jdbc.queryForObject(
            "SELECT game_data_version FROM refinery_order WHERE id = ?", Long.class, order);
    return version == null ? -1 : version;
  }

  /**
   * Counts the audit events of a type about a subject.
   *
   * @param type the event type
   * @param subject the subject
   * @return the count
   */
  private long auditCount(@NotNull String type, @NotNull UUID subject) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE event_type = ? AND subject_id = ?",
            Long.class,
            type,
            subject);
    return count == null ? 0 : count;
  }

  /**
   * Converts an instant for a JDBC timestamp parameter.
   *
   * @param instant the instant
   * @return the timestamp
   */
  private static Timestamp ts(@NotNull Instant instant) {
    return Timestamp.from(instant);
  }
}
