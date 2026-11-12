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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the bank's area cascade check against the actor's direct seats (REQ-BANK-048). */
class OrgUnitBankAreaCascadeDifferentialTest {

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID BEREICH = UUID.randomUUID();
  private static final UUID CHILD_STAFFEL = UUID.randomUUID();
  private static final UUID CHILD_SK = UUID.randomUUID();
  private static final UUID OTHER_BEREICH = UUID.randomUUID();
  private static final UUID OTHER_STAFFEL = UUID.randomUUID();
  private static final UUID EMPTY_BEREICH = UUID.randomUUID();

  private static final Map<UUID, List<UUID>> CHILDREN =
      Map.of(
          BEREICH, List.of(CHILD_STAFFEL, CHILD_SK),
          OTHER_BEREICH, List.of(OTHER_STAFFEL),
          EMPTY_BEREICH, List.of(),
          CHILD_STAFFEL, List.of(),
          CHILD_SK, List.of(),
          OTHER_STAFFEL, List.of());

  private static final List<Set<UUID>> MEMBERSHIPS =
      List.of(
          Set.of(),
          Set.of(BEREICH),
          Set.of(CHILD_STAFFEL),
          Set.of(CHILD_SK),
          Set.of(OTHER_BEREICH),
          Set.of(OTHER_STAFFEL),
          Set.of(CHILD_STAFFEL, OTHER_STAFFEL),
          Set.of(OTHER_BEREICH, CHILD_SK));

  private static final List<UUID> TARGETS =
      List.of(BEREICH, OTHER_BEREICH, EMPTY_BEREICH, CHILD_STAFFEL, OTHER_STAFFEL);

  @Test
  void matchesTheCallerSeatsThatConferMembershipOverTheWholeMatrix() {
    for (boolean authenticated : List.of(true, false)) {
      for (boolean admin : List.of(false, true)) {
        for (boolean member : List.of(false, true)) {
          for (Set<UUID> memberships : MEMBERSHIPS) {
            BankActor actor = actor(authenticated, admin, member, memberships);
            for (UUID target : TARGETS) {
              List<UUID> children = CHILDREN.get(target);
              boolean expected =
                  authenticated
                      && member
                      && memberships.stream()
                          .anyMatch(id -> id.equals(target) || children.contains(id));
              boolean actual =
                  BankAccessJudgement.membershipsConfer(actor)
                      && BankAccessJudgement.isMemberOfAreaCascade(
                          target, actor.memberOrgUnitIds(), children);
              assertThat(actual)
                  .as(
                      "authenticated=%s admin=%s member=%s memberships=%s target=%s",
                      authenticated, admin, member, memberships, target)
                  .isEqualTo(expected);
            }
          }
        }
      }
    }
  }

  private static BankActor actor(
      boolean authenticated, boolean admin, boolean member, Set<UUID> memberships) {
    if (!authenticated) {
      return BankActor.NOBODY;
    }
    Set<String> roles = new java.util.HashSet<>();
    if (admin) {
      roles.add(Roles.ADMIN);
    }
    if (member) {
      roles.add(Roles.KRT_MEMBER);
    }
    return new BankActor(
        CALLER,
        roles,
        memberships.stream().map(id -> new BankActor.Seat(id, MembershipRole.MEMBER)).toList(),
        Set.of(),
        Map.of());
  }
}
