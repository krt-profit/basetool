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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEvent;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.identity.api.MembershipDeltaRequest.SpecialCommandChange;
import de.greluc.krt.profit.basetool.backend.identity.api.MembershipDeltaRequest.SpecialCommandChange.Action;
import de.greluc.krt.profit.basetool.backend.identity.api.MembershipDeltaRequest.StaffelChange;
import de.greluc.krt.profit.basetool.backend.identity.api.MembershipOperations;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Differential test of the identity-owned {@link MembershipOperations} seam: the membership-delta
 * paths of {@link UserService#applyMembershipDelta} go through the seam, and the same steps,
 * written as the direct calls {@code UserService} made before the seam existed, run on a second
 * user. Both must leave the same membership rows (kind, role, flags, row version), as the database
 * holds them straight after the call as well as after an explicit flush,, write the same audit rows
 * and fail with the same exception type and message.
 *
 * <p>Runs against the real schema inside one rolled-back transaction, so the after-commit
 * notification fan-out is not exercised; the seam adds no transaction and no side effect of its
 * own, which {@code OrgUnitMembershipOperationsTest} pins separately.
 */
@SpringBootTest
@Transactional
class UserServiceMembershipSeamDifferentialTest {

  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  @Autowired private UserService userService;
  @Autowired private MembershipOperations membershipOperations;
  @Autowired private OrgUnitMembershipService membershipService;
  @Autowired private OrgUnitMembershipQueryService membershipQueryService;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbc;

  /** The org units one scenario runs against. */
  private record Units(UUID sqA, UUID sqB, UUID sqC, UUID sk1, UUID sk2) {}

  /** A delta builder: the units and the current row version of the user's membership in sk1. */
  private interface Step extends BiFunction<Units, Long, MembershipDeltaRequest> {}

  /** What one step did: the user's rows afterwards, or the exception it ended with. */
  private record Observed(
      List<String> storedRows, List<String> rows, List<UUID> staffelIds, String failure) {}

  @Test
  @DisplayName("a Staffel set that grows and shrinks")
  void staffelSetGrowsAndShrinks() {
    assertSameOutcome(
        List.of(
            (u, v) ->
                new MembershipDeltaRequest(
                    List.of(
                        new StaffelChange(u.sqA(), true, false),
                        new StaffelChange(u.sqB(), null, null)),
                    null),
            (u, v) ->
                new MembershipDeltaRequest(List.of(new StaffelChange(u.sqA(), false, true)), null),
            (u, v) -> new MembershipDeltaRequest(List.of(), null)));
  }

  @Test
  @DisplayName("a Spezialkommando is added with flags, patched and removed")
  void specialCommandAddPatchRemove() {
    assertSameOutcome(
        List.of(
            (u, v) ->
                new MembershipDeltaRequest(
                    null, List.of(new SpecialCommandChange(u.sk1(), Action.ADD, true, true, null))),
            (u, v) ->
                new MembershipDeltaRequest(
                    null, List.of(new SpecialCommandChange(u.sk1(), Action.PATCH, false, null, v))),
            (u, v) ->
                new MembershipDeltaRequest(
                    null,
                    List.of(new SpecialCommandChange(u.sk1(), Action.REMOVE, null, null, null)))));
  }

  @Test
  @DisplayName("one delta that changes the Staffeln and two Spezialkommandos in request order")
  void mixedDelta() {
    assertSameOutcome(
        List.of(
            (u, v) ->
                new MembershipDeltaRequest(
                    List.of(new StaffelChange(u.sqA(), null, true)),
                    List.of(
                        new SpecialCommandChange(u.sk1(), Action.ADD, null, true, null),
                        new SpecialCommandChange(u.sk2(), Action.ADD, true, null, null)))));
  }

  @Test
  @DisplayName("an empty delta and an unknown user")
  void emptyDelta() {
    assertSameOutcome(List.of((u, v) -> new MembershipDeltaRequest(null, null)));
  }

  @Test
  @DisplayName("the same Staffel twice and a third Staffel are refused alike")
  void staffelRefusals() {
    assertSameOutcome(
        List.of(
            (u, v) ->
                new MembershipDeltaRequest(
                    List.of(
                        new StaffelChange(u.sqA(), null, null),
                        new StaffelChange(u.sqA(), null, null)),
                    null)));
    assertSameOutcome(
        List.of(
            (u, v) ->
                new MembershipDeltaRequest(
                    List.of(
                        new StaffelChange(u.sqA(), null, null),
                        new StaffelChange(u.sqB(), null, null),
                        new StaffelChange(u.sqC(), null, null)),
                    null)));
  }

  @Test
  @DisplayName("an unknown Staffel and an unknown Spezialkommando are refused alike")
  void unknownUnits() {
    assertSameOutcome(
        List.of(
            (u, v) ->
                new MembershipDeltaRequest(
                    List.of(new StaffelChange(UUID.randomUUID(), null, null)), null)));
    assertSameOutcome(
        List.of(
            (u, v) ->
                new MembershipDeltaRequest(
                    null,
                    List.of(
                        new SpecialCommandChange(
                            UUID.randomUUID(), Action.ADD, null, null, null)))));
  }

  @Test
  @DisplayName("adding a member twice, removing a non-member and a stale patch fail alike")
  void membershipRefusals() {
    assertSameOutcome(
        List.of(
            (u, v) ->
                new MembershipDeltaRequest(
                    null, List.of(new SpecialCommandChange(u.sk1(), Action.ADD, null, null, null))),
            (u, v) ->
                new MembershipDeltaRequest(
                    null,
                    List.of(new SpecialCommandChange(u.sk1(), Action.ADD, null, null, null)))));
    assertSameOutcome(
        List.of(
            (u, v) ->
                new MembershipDeltaRequest(
                    null,
                    List.of(new SpecialCommandChange(u.sk2(), Action.REMOVE, null, null, null)))));
    assertSameOutcome(
        List.of(
            (u, v) ->
                new MembershipDeltaRequest(
                    null, List.of(new SpecialCommandChange(u.sk1(), Action.ADD, null, null, null))),
            (u, v) ->
                new MembershipDeltaRequest(
                    null,
                    List.of(new SpecialCommandChange(u.sk1(), Action.PATCH, true, null, v + 7)))));
  }

  @Test
  @DisplayName("an unknown user fails alike")
  void unknownUser() {
    UUID ghost = UUID.randomUUID();
    MembershipDeltaRequest delta = new MembershipDeltaRequest(null, null);

    String viaSeam = failureOf(() -> userService.applyMembershipDelta(ghost, delta));
    String direct = failureOf(() -> legacyApply(ghost, delta));

    assertThat(viaSeam).startsWith("NoSuchElementException:");
    assertThat(viaSeam).isEqualTo(direct);
  }

  private void assertSameOutcome(List<Step> steps) {
    Units units = newUnits();
    User viaSeamUser = newUser("seam");
    User directUser = newUser("direct");

    List<Observed> viaSeam = new ArrayList<>();
    for (Step step : steps) {
      viaSeam.add(
          run(
              viaSeamUser.getId(),
              step.apply(units, versionOf(viaSeamUser.getId(), units.sk1())),
              true));
    }
    List<Observed> direct = new ArrayList<>();
    for (Step step : steps) {
      direct.add(
          run(
              directUser.getId(),
              step.apply(units, versionOf(directUser.getId(), units.sk1())),
              false));
    }

    assertThat(direct).as("rows, staffel ids and failures of every step").isEqualTo(viaSeam);
    assertThat(auditOf(directUser.getId()))
        .as("audit rows in order")
        .isEqualTo(auditOf(viaSeamUser.getId()));
  }

  private Observed run(UUID userId, MembershipDeltaRequest delta, boolean viaSeam) {
    String failure =
        failureOf(
            () -> {
              if (viaSeam) {
                userService.applyMembershipDelta(userId, delta);
              } else {
                legacyApply(userId, delta);
              }
            });
    List<String> storedRows = storedRows(userId);
    entityManager.flush();
    entityManager.clear();
    List<String> rows =
        membershipRepository.findAllByIdUserId(userId).stream()
            .map(this::describe)
            .sorted()
            .toList();
    List<UUID> staffelIds =
        viaSeam
            ? membershipOperations.findStaffelMembershipOrgUnitIds(userId)
            : membershipQueryService.findStaffelMembershipOrgUnitIds(userId);
    return new Observed(storedRows, rows, staffelIds, failure);
  }

  /**
   * The user's rows as the database holds them right now, read over the transaction's own JDBC
   * connection before any explicit flush: a row appears here only if the operation itself flushed,
   * so this pins where the flush happens.
   */
  private List<String> storedRows(UUID userId) {
    return jdbc
        .query(
            "SELECT org_unit_id, role, is_logistician, is_mission_manager, version"
                + " FROM org_unit_membership WHERE user_id = ?",
            (rs, n) ->
                rs.getString(1)
                    + "|"
                    + rs.getString(2)
                    + "|"
                    + rs.getBoolean(3)
                    + "|"
                    + rs.getBoolean(4)
                    + "|v"
                    + rs.getLong(5),
            userId)
        .stream()
        .sorted()
        .toList();
  }

  /** The direct calls {@code UserService} made before the seam existed. */
  private List<OrgUnitMembership> legacyApply(UUID userId, MembershipDeltaRequest delta) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NoSuchElementException("User not found with id: " + userId));
    if (delta.staffeln() != null) {
      membershipService.reconcileStaffelMemberships(user, delta.staffeln());
    }
    if (delta.specialCommands() != null) {
      for (SpecialCommandChange change : delta.specialCommands()) {
        switch (change.action()) {
          case ADD -> {
            OrgUnitMembership fresh = membershipService.addMember(change.orgUnitId(), userId);
            if (Boolean.TRUE.equals(change.isLogistician())) {
              fresh.setLogistician(true);
            }
            if (Boolean.TRUE.equals(change.isMissionManager())) {
              fresh.setMissionManager(true);
            }
          }
          case REMOVE -> membershipService.removeMember(change.orgUnitId(), userId);
          case PATCH ->
              membershipService.patchFlags(
                  change.orgUnitId(),
                  userId,
                  new MembershipFlagsPatchRequest(
                      change.isLogistician(), change.isMissionManager(), change.version()));
        }
      }
    }
    return membershipQueryService.findAllMembershipsForUser(userId);
  }

  private String failureOf(Runnable action) {
    try {
      action.run();
      return null;
    } catch (RuntimeException e) {
      return e.getClass().getSimpleName()
          + ": "
          + UUID_TEXT.matcher(String.valueOf(e.getMessage())).replaceAll("<id>");
    }
  }

  private long versionOf(UUID userId, UUID skId) {
    entityManager.flush();
    entityManager.clear();
    return membershipRepository
        .findById(
            new de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId(userId, skId))
        .map(OrgUnitMembership::getVersion)
        .orElse(0L);
  }

  private String describe(OrgUnitMembership m) {
    String unit = orgUnitRepository.findById(m.getId().getOrgUnitId()).orElseThrow().getShorthand();
    return unit
        + "|"
        + m.getKind()
        + "|"
        + m.getRole()
        + "|"
        + m.isLogistician()
        + "|"
        + m.isMissionManager()
        + "|v"
        + m.getVersion();
  }

  private List<String> auditOf(UUID userId) {
    return auditEventRepository.findAll().stream()
        .filter(e -> userId.equals(e.getTargetUserId()))
        .sorted(Comparator.comparing(AuditEvent::getOccurredAt))
        .map(
            e ->
                e.getDomain()
                    + "|"
                    + e.getEventType()
                    + "|"
                    + e.getSubjectLabel()
                    + "|"
                    + UUID_TEXT.matcher(String.valueOf(e.getDetails())).replaceAll("<id>"))
        .toList();
  }

  private User newUser(String tag) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("seam-" + tag + "-" + user.getId());
    return userRepository.saveAndFlush(user);
  }

  private Units newUnits() {
    String tag = UUID.randomUUID().toString().substring(0, 6);
    return new Units(
        squadron("Seam-A-" + tag, "A" + tag).getId(),
        squadron("Seam-B-" + tag, "B" + tag).getId(),
        squadron("Seam-C-" + tag, "C" + tag).getId(),
        specialCommand("Seam-S1-" + tag, "S1" + tag).getId(),
        specialCommand("Seam-S2-" + tag, "S2" + tag).getId());
  }

  private Squadron squadron(String name, String shorthand) {
    Squadron s = new Squadron();
    s.setName(name);
    s.setShorthand(shorthand);
    s.setProfitEligible(true);
    return squadronRepository.save(s);
  }

  private SpecialCommand specialCommand(String name, String shorthand) {
    SpecialCommand sc = new SpecialCommand();
    sc.setName(name);
    sc.setShorthand(shorthand);
    sc.setProfitEligible(true);
    return specialCommandRepository.save(sc);
  }
}
