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

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant.Rewritten;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Preserves monetary references when the holder or a historical name is erased. */
@Component
@RequiredArgsConstructor
public class JobOrderMoneyPrivacy {

  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyHolderChangeRepository holderChanges;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyRecipientRepository recipients;
  private final JobOrderMoneyTargetChangeRepository targetChanges;
  private final LiveSyncPublisher liveSync;
  private final JobOrderMoneyIdentityLock identityLock;

  void anonymise(UUID userId, List<String> spellings, BiConsumer<Rewritten, Integer> report) {
    identityLock.lockAccounts(userId, null);
    String[] names = spellings.toArray(String[]::new);
    List<UUID> orderIds = holders.lockPrivacyOrders(userId, names);
    if (orderIds.isEmpty()) {
      return;
    }
    int retiredHolderRecipients = recipients.retireErasedHolderRecipients(userId, names, orderIds);
    int holderRows = holders.anonymiseNames(userId, names, orderIds, HandleAnonymisation.SENTINEL);
    int operationRows = operations.anonymiseSubmittedNames(userId, names, orderIds);
    int changeRows =
        holderChanges.anonymiseNames(userId, names, orderIds, HandleAnonymisation.SENTINEL);
    int recipientRows = recipients.anonymiseNames(names, orderIds, HandleAnonymisation.SENTINEL);
    report.accept(Rewritten.ORDER_MONEY_RECIPIENTS, recipientRows + retiredHolderRecipients);
    report.accept(Rewritten.ORDER_MONEY_HOLDERS, holderRows);
    report.accept(Rewritten.ORDER_MONEY_OPERATIONS, operationRows);
    report.accept(Rewritten.ORDER_MONEY_HOLDER_CHANGES, changeRows);
    if (holderRows + operationRows + recipientRows + retiredHolderRecipients + changeRows > 0) {
      publishChanges(orderIds);
    }
  }

  void unlink(UUID userId) {
    List<UUID> orderIds = lockAffectedOrders(userId, List.of());
    if (orderIds.isEmpty()) {
      return;
    }
    recipients.retireErasedHolderRecipients(userId, new String[0], orderIds);
    holders.unlinkAccount(userId, orderIds);
    recipients.unlinkAccount(userId, orderIds);
    targetChanges.unlinkActor(userId, orderIds);
    publishChanges(orderIds);
  }

  void publishChanges(Collection<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return;
    }
    for (UUID orderId : orderIds) {
      liveSync.publishAfterCommit(
          LiveSyncTopic.of(LiveSyncTopicClass.ORDER, orderId), List.of("money", "actions"));
    }
    liveSync.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue"));
  }

  List<UUID> lockAffectedOrders(UUID userId, List<String> spellings) {
    return holders.lockPrivacyOrders(userId, spellings.toArray(String[]::new));
  }
}
