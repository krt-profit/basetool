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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link OperationPayoutStatus}. */
@Repository
public interface OperationPayoutStatusRepository
    extends JpaRepository<OperationPayoutStatus, UUID> {

  /**
   * Returns every payout-status row for the given operation, with the audit user pre-fetched so the
   * operation roll-up does not trigger an extra SELECT per row when rendering "paid out by".
   *
   * @param operationId the operation primary key
   * @return all status rows for this operation (possibly empty)
   */
  @EntityGraph(attributePaths = {"paidOutByUser"})
  List<OperationPayoutStatus> findByOperationId(UUID operationId);

  /**
   * Checks whether an operation still has a paid-out mark that freezes its inputs.
   *
   * @param operationId the operation to check
   * @return whether a participant is currently marked paid
   */
  boolean existsByOperationIdAndPaidOutTrue(UUID operationId);

  /**
   * Finds a current paid mark by its opaque id inside the given operation.
   *
   * @param id the mark to reverse
   * @param operationId the operation it must belong to
   * @return the paid mark if it belongs to this operation and has not been reversed
   */
  Optional<OperationPayoutStatus> findByIdAndOperationIdAndPaidOutTrue(UUID id, UUID operationId);

  /**
   * Looks up the status row for a specific (operation, participant-key) tuple. The toggle endpoint
   * uses this to decide whether to materialize a new row or update the existing one in place.
   *
   * @param operationId the operation primary key
   * @param participantKey opaque participant key (user UUID or {@code "guest_<name>"})
   * @return the existing row, or empty if the flag has never been toggled
   */
  Optional<OperationPayoutStatus> findByOperationIdAndParticipantKey(
      UUID operationId, String participantKey);

  /**
   * Re-keys every payout status of the given member from the member's id to the key the payout
   * calculation gives the member's participation once the account is gone, for the account
   * deletion. The key becomes {@code deleted_<participantId>} of the member's first participation
   * in the operation's missions, or {@code deleted_<statusId>} when the operation holds none. Must
   * run while {@code mission_participant.user_id} still names the member.
   *
   * @param memberKey the departing member's {@code app_user.id} as a payout key
   * @return the number of status rows re-keyed
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE operation_payout_status status
          SET participant_key = 'deleted_' || COALESCE(
                  (SELECT CAST(participant.id AS text)
                   FROM mission_participant participant
                   JOIN mission ON mission.id = participant.mission_id
                   WHERE mission.operation_id = status.operation_id
                     AND CAST(participant.user_id AS text) = status.participant_key
                   ORDER BY participant.id
                   LIMIT 1),
                  CAST(status.id AS text)),
              version = status.version + 1
          WHERE status.participant_key = :memberKey
          """,
      nativeQuery = true)
  int rekeyDeletedMember(@Param("memberKey") String memberKey);
}
