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

import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.UserReassignmentParticipant;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserAccountMergeService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins what the account merge (REQ-SEC-045) asks of each module: the participants the running
 * application registers, in the order {@link UserAccountMergeService#inRunOrder()} runs them, the
 * columns each moves with its conflict keys, and the columns each leaves with the act.
 *
 * <p>The order is the order the merge's audit row reports its columns in; a conflict key is the
 * remainder of a unique constraint, so a wrong key either drops rows the target does not hold or
 * violates the constraint.
 */
@SpringBootTest
@Transactional
class UserReassignmentOrderTest {

  /**
   * The participants of today's modules, earliest first, as {@code position:Class|follows|stays}.
   */
  private static final List<String> EXPECTED =
      List.of(
          "10:InventoryUserReassignment|inventory_item.user_id|",
          "20:HangarUserReassignment|ship.owner_id|",
          "30:RefineryUserReassignment|refinery_order.owner_id|",
          "40:PersonalInventoryUserReassignment|personal_inventory_item.owner_user_id|",
          "50:PersonalBlueprintUserReassignment|personal_blueprint.owner_user_id[product_key]|",
          "60:OrgUnitMembershipUserReassignment|org_unit_membership.user_id[org_unit_id]|",
          "70:OrgChartUserReassignment|org_chart_position.user_id[org_unit_id]|",
          "80:OrgUnitGrandAdmiralReassignment|org_unit.grand_admiral_user_id|",
          "90:MissionUserReassignment|mission.owner_id,mission_ownership.owner_id,"
              + "mission.party_lead_user_id,mission_unit.responsible_user_id,"
              + "mission_managers.user_id[mission_id],mission_participant.user_id[mission_id]|",
          "100:JobOrderUserReassignment|job_order_assignees.user_id[job_order_id]"
              + "|job_order_handover.executing_user_id,job_order_item_handover.executing_user_id,"
              + "material_claim.claimed_by_user_id",
          "110:MaterialExchangeUserReassignment|material_exchange_offer.owner_id,"
              + "material_exchange_request.owner_id,"
              + "material_exchange_interest.interested_user_id[offer_id],"
              + "material_exchange_request_interest.interested_user_id[request_id]|",
          "120:BankUserReassignment|bank_account_grant.user_id[account_id],"
              + "bank_account_view_grant.grantee_user_id[account_id+grantee_kind],"
              + "bank_account_approval_limit.grantee_user_id[account_id+grantee_kind],"
              + "bank_holder.user_id"
              + "|bank_audit_event.actor_user_id,bank_audit_event.target_user_id,"
              + "bank_account_grant.granted_by,bank_booking_request.requested_by,"
              + "bank_booking_request.decided_by,bank_booking_request.owner_approval_granted_by,"
              + "bank_booking_request.counterparty_user_id,bank_transaction.initiated_by,"
              + "bank_transaction.counterparty_user_id",
          "130:NotificationUserReassignment|notification.recipient_user_id,"
              + "notification_rule_selector.user_id,"
              + "notification_mute.user_id[notification_type]|",
          "140:PromotionUserReassignment|member_evaluation.user_id[category_id]|",
          "150:ExchangeUserReassignment|exchange_installation.user_id"
              + "[exchange_client_id+key_thumbprint],"
              + "exchange_ship_link.user_id[client_id+installation_key+external_id]"
              + "|exchange_client_revocation.user_id,exchange_change.user_id,"
              + "exchange_journal.user_id,exchange_bulk_undo_run.requested_by,"
              + "exchange_bulk_undo_skip.user_id",
          "160:AuditUserReassignment||audit_event.actor_user_id,audit_event.target_user_id",
          "170:OperationUserReassignment||operation_payout_status.paid_out_by_user_id",
          "180:IdentityUserReassignment||app_user.approved_by_id,user_approval_event.user_id,"
              + "user_approval_event.decided_by_id,deletion_request.user_id,"
              + "deletion_request.decided_by_id,user_roles.user_id,terms_acceptance.user_id");

  @Autowired private UserAccountMergeService mergeService;

  @Test
  @DisplayName("the registered participants run in the recorded order with the recorded columns")
  void theRegisteredParticipantsRunInTheRecordedOrder() {
    assertThat(describe(mergeService.inRunOrder())).isEqualTo(EXPECTED);
  }

  @Test
  @DisplayName("no two participants share a position")
  void noTwoParticipantsShareAPosition() {
    Set<Integer> seen = new HashSet<>();
    for (UserReassignmentParticipant participant : mergeService.inRunOrder()) {
      assertThat(seen.add(participant.position()))
          .as("%s at %s", participant.getClass().getSimpleName(), participant.position())
          .isTrue();
    }
  }

  @Test
  @DisplayName("no column is declared twice")
  void noColumnIsDeclaredTwice() {
    List<String> all = new ArrayList<>(mergeService.followedColumns());
    all.addAll(mergeService.staysWithTheAct());
    assertThat(all).doesNotHaveDuplicates().hasSize(56);
  }

  private static List<String> describe(List<UserReassignmentParticipant> participants) {
    List<String> described = new ArrayList<>();
    for (UserReassignmentParticipant participant : participants) {
      String follows =
          participant.followsTheMember().stream()
              .map(UserReassignmentOrderTest::describe)
              .collect(Collectors.joining(","));
      String stays = String.join(",", participant.staysWithTheAct());
      described.add(
          participant.position()
              + ":"
              + AopUtils.getTargetClass(participant).getSimpleName()
              + "|"
              + follows
              + "|"
              + stays);
    }
    return described;
  }

  private static String describe(OwnedRows rows) {
    String keys = String.join("+", rows.conflictKeys());
    return rows.qualified() + (keys.isEmpty() ? "" : "[" + keys + "]");
  }
}
