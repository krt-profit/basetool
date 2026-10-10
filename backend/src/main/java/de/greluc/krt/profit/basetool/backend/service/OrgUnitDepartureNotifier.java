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

import de.greluc.krt.profit.basetool.backend.identity.api.events.MemberDepartedEvent;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.events.OrgNotices;
import de.greluc.krt.profit.basetool.backend.orgunit.internal.OrgUnitLabels;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the leadership of every unit a departed member belonged to, and of the parent Bereich when
 * a seat of theirs became vacant (REQ-ORG-030). Runs after the sync that noticed the departure has
 * committed, in a transaction of its own so the notices it publishes are delivered.
 */
@Component
@RequiredArgsConstructor
public class OrgUnitDepartureNotifier {

  private final OrgUnitMembershipRepository membershipRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final UserRepository userRepository;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Publishes one notice per unit the member belonged to and per parent Bereich of a vacated seat.
   *
   * @param event the departure
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onDeparture(@NotNull MemberDepartedEvent event) {
    UUID userId = event.userId();
    List<OrgUnitMembership> memberships = membershipRepository.findAllByIdUserId(userId);
    if (memberships.isEmpty()) {
      return;
    }
    Map<UUID, OrgUnit> units =
        orgUnitRepository
            .findAllById(memberships.stream().map(m -> m.getId().getOrgUnitId()).toList())
            .stream()
            .collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
    String member = userRepository.findPlainById(userId).map(User::getEffectiveName).orElse("—");
    Set<UUID> announcedBereiche = new HashSet<>();
    for (OrgUnitMembership membership : memberships) {
      OrgUnit unit = units.get(membership.getId().getOrgUnitId());
      if (unit == null) {
        continue;
      }
      boolean seat = membership.getRole() != MembershipRole.MEMBER;
      announce(userId, member, unit, event.reason(), seat);
      OrgUnit parent = unit.getParent();
      if (seat
          && parent != null
          && parent.getKind() == OrgUnitKind.BEREICH
          && announcedBereiche.add(parent.getId())) {
        announce(userId, member, parent, event.reason(), true);
      }
    }
  }

  private void announce(UUID userId, String member, OrgUnit unit, String reason, boolean vacant) {
    eventPublisher.publishEvent(
        OrgNotices.memberDeparted(
            userId,
            member,
            new OrgUnitRef(unit.getId(), unit.getKind()),
            OrgUnitLabels.shorthandOrName(unit),
            reason,
            vacant));
  }
}
