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

import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.UserReassignmentParticipant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Moves the member's order assignments and linked holder identities during account merge. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JobOrderUserReassignment implements UserReassignmentParticipant {

  static final int POSITION = 100;

  private static final OwnedRows ASSIGNEES =
      OwnedRows.deduped("job_order_assignees", "user_id", "job_order_id");
  private static final OwnedRows HOLDERS =
      new OwnedRows("job_order_money_holder", "user_id", List.of("order_id"));

  private static final OwnedRows RECIPIENTS = OwnedRows.of("job_order_money_recipient", "user_id");

  private final OwnedRowsMover mover;
  private final JobOrderMoneyRecipientRepository recipients;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyPrivacy moneyPrivacy;
  private final JobOrderMoneyIdentityLock identityLock;

  @Override
  public int position() {
    return POSITION;
  }

  @Override
  public @NotNull List<OwnedRows> followsTheMember() {
    return List.of(ASSIGNEES, HOLDERS, RECIPIENTS);
  }

  @Override
  public @NotNull List<String> staysWithTheAct() {
    return List.of(
        "job_order_handover.executing_user_id",
        "job_order_item_handover.executing_user_id",
        "material_claim.claimed_by_user_id",
        "job_order_money_operation.actor_id",
        "job_order_money_operation.selected_user_id",
        "job_order_money_target_change.actor_id");
  }

  @Override
  public void assertMergeable(@NotNull UUID source, @NotNull UUID target) {
    identityLock.lockAccounts(source, target);
  }

  /**
   * Moves order membership and keeps every credited holder identity in a monetary collision.
   *
   * @param source the account being emptied
   * @param target the surviving account
   * @return the counts of assignments and holder links moved or merged
   */
  @Override
  public @NotNull List<Moved> reassign(@NotNull UUID source, @NotNull UUID target) {
    List<UUID> orderIds = moneyPrivacy.lockAffectedOrders(source, List.of());
    List<Moved> assignments = mover.move(List.of(ASSIGNEES), source, target);
    if (orderIds.isEmpty()) {
      return assignments;
    }
    int collisions = holders.reviseCollidingHolders(source, target, orderIds);
    holders.mergeCollidingHolders(source, target, orderIds);
    int movedLinks = holders.moveLinkedHolders(source, target, orderIds);
    int movedRecipients = recipients.moveAccounts(source, target, orderIds);
    if (collisions + movedLinks + movedRecipients == 0) {
      return assignments;
    }
    List<Moved> moved = new ArrayList<>(assignments);
    if (collisions + movedLinks > 0) {
      moved.add(new Moved(HOLDERS.qualified(), collisions + movedLinks));
    }
    if (movedRecipients > 0) {
      moved.add(new Moved(RECIPIENTS.qualified(), movedRecipients));
    }
    moneyPrivacy.publishChanges(orderIds);
    return List.copyOf(moved);
  }
}
