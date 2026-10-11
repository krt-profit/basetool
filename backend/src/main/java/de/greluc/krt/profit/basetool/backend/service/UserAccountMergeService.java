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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.UserReassignmentParticipant;
import de.greluc.krt.profit.basetool.backend.platform.api.UserReassignmentParticipant.Moved;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves everything one account owns onto another, so an admin can repair a member who ended up with
 * two (REQ-SEC-045).
 *
 * <p>Ownership follows the member; attribution stays with the act. Every foreign key into {@code
 * app_user} is declared by the owning module's {@link UserReassignmentParticipant} in exactly one
 * of {@link #followedColumns()} or {@link #staysWithTheAct()}, enforced by {@code
 * UserAccountMergeCoverageTest}.
 *
 * <p>Where a unique constraint lets the same row exist on both accounts, the source's duplicate is
 * dropped. A module may refuse the merge: a {@code bank_holder} on both accounts means two ledgers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAccountMergeService {

  private final UserRepository userRepository;
  private final AuditRecorder auditRecorder;
  private final EntityManager entityManager;
  private final List<UserReassignmentParticipant> participants;

  /**
   * Returns the participants in the order they run, by position.
   *
   * @return the participants, earliest first
   */
  public @NotNull List<UserReassignmentParticipant> inRunOrder() {
    return participants.stream()
        .sorted(Comparator.comparingInt(UserReassignmentParticipant::position))
        .toList();
  }

  /**
   * The moved tables as {@code table.column}, the form the schema catalogue reports.
   *
   * <p>Exists so {@code UserAccountMergeCoverageTest} can compare the classification against the
   * live schema without reaching into the participants' declarations.
   *
   * @return one qualified column name per moved table, in the order the merge moves them
   */
  @NotNull
  public List<String> followedColumns() {
    return inRunOrder().stream()
        .flatMap(participant -> participant.followsTheMember().stream())
        .map(OwnedRows::qualified)
        .toList();
  }

  /**
   * Returns every user column that records an act rather than a belonging, and therefore stays with
   * the source account.
   *
   * <p>Includes the audit columns (REQ-AUDIT-001), the requester of a bank booking request, {@code
   * user_roles} (re-derived on every login, REQ-SEC-013) and {@code terms_acceptance} (consent is
   * per account).
   *
   * @return one qualified column name per staying column, in participant order
   */
  @NotNull
  public List<String> staysWithTheAct() {
    return inRunOrder().stream()
        .flatMap(participant -> participant.staysWithTheAct().stream())
        .toList();
  }

  /**
   * Moves everything {@code sourceUserId} owns onto {@code targetUserId}.
   *
   * <p>The emptied source row is left in place; removing it is the separate user-deletion flow.
   *
   * @param sourceUserId the account to empty, which the member can no longer reach
   * @param targetUserId the account to keep, which the member logs into now
   * @param adminId the acting admin, recorded as the audit actor
   * @return the surviving target account
   * @throws NotFoundException when either account is unknown
   * @throws BusinessConflictException when the two ids are the same, or a module refuses the merge
   *     (both accounts hold a bank-holder row)
   */
  @Transactional
  @NotNull
  public User merge(@NotNull UUID sourceUserId, @NotNull UUID targetUserId, @NotNull UUID adminId) {
    if (sourceUserId.equals(targetUserId)) {
      throw new BusinessConflictException("An account cannot be merged into itself");
    }
    final User source =
        Entities.require(userRepository.findById(sourceUserId), "Source account not found");
    final User target =
        Entities.require(userRepository.findById(targetUserId), "Target account not found");

    List<UserReassignmentParticipant> ordered = inRunOrder();
    for (UserReassignmentParticipant participant : ordered) {
      participant.assertMergeable(sourceUserId, targetUserId);
    }
    carryRsiHandle(source, target);

    entityManager.flush();

    List<Moved> moved = new ArrayList<>();
    int total = 0;
    for (UserReassignmentParticipant participant : ordered) {
      for (Moved column : participant.reassign(sourceUserId, targetUserId)) {
        moved.add(column);
        total += column.rows();
      }
    }
    entityManager.clear();

    AuditDetails details = AuditDetails.of("fromUser", sourceUserId).with("rows", total);
    for (Moved column : moved) {
      details = details.with(column.column(), column.rows());
    }
    auditRecorder.record(AuditEventType.USER_MERGED, null, null, targetUserId, details);

    log.info(
        "Merged account {} into {}: {} row(s) moved across {} table(s) (acting admin {})",
        sourceUserId,
        targetUserId,
        total,
        moved.size(),
        adminId);
    return target;
  }

  /**
   * Moves the source's RSI handle onto the target when the target has none, and clears it on the
   * source either way, so the survivor's handle wins and the handle stays unique (REQ-SEC-072).
   *
   * @param source the account being emptied
   * @param target the account being kept
   */
  private void carryRsiHandle(@NotNull User source, @NotNull User target) {
    String handle = source.getRsiHandle();
    if (handle == null) {
      return;
    }
    source.setRsiHandle(null);
    userRepository.saveAndFlush(source);
    if (target.getRsiHandle() == null) {
      target.setRsiHandle(handle);
      userRepository.saveAndFlush(target);
    }
  }
}
