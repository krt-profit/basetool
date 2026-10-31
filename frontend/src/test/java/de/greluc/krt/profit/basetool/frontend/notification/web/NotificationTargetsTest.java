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

package de.greluc.krt.profit.basetool.frontend.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SecurityConfig;
import de.greluc.krt.profit.basetool.frontend.notification.web.NotificationTargets.Viewer;
import de.greluc.krt.profit.basetool.frontend.support.BackendNotificationTypes;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Tests the page each notification type opens (REQ-NOTIF-022, REQ-NOTIF-032). */
class NotificationTargetsTest {

  private static final Viewer MEMBER = new Viewer(false, false, false);
  private static final Viewer OFFICER = new Viewer(false, true, false);
  private static final Viewer ADMIN = new Viewer(true, false, false);
  private static final Viewer BANK_STAFF = new Viewer(false, false, true);

  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

  /**
   * Resolves a target for an ordinary member.
   *
   * @param type the notification type
   * @param entityType the subject's type tag
   * @return the target
   */
  private static String target(String type, String entityType) {
    return NotificationTargets.targetOf(type, entityType, ID, MEMBER);
  }

  @Test
  void everyBackendTypeIsMappedOrDeliberatelyUnlinked() throws IOException {
    Set<String> types = BackendNotificationTypes.all();
    Set<String> decided = new HashSet<>(NotificationTargets.TARGETS.keySet());
    decided.addAll(NotificationTargets.UNLINKED_TYPES);

    assertThat(types)
        .as(
            "A notification type without a decision renders no link. Map it in"
                + " NotificationTargets.TARGETS or list it in UNLINKED_TYPES (REQ-NOTIF-032).")
        .isSubsetOf(decided);
    assertThat(decided).as("entries for types the backend no longer has").isSubsetOf(types);
    assertThat(NotificationTargets.TARGETS.keySet())
        .doesNotContainAnyElementsOf(NotificationTargets.UNLINKED_TYPES);
  }

  @Test
  void unlinkedTypesHaveNoTarget() {
    for (String type : NotificationTargets.UNLINKED_TYPES) {
      assertNull(NotificationTargets.targetOf(type, "ANY", ID, ADMIN), type);
    }
    assertNull(NotificationTargets.targetOf(null, null, null, ADMIN));
    assertNull(NotificationTargets.targetOf("UNKNOWN_TYPE", "JOB_ORDER", ID, ADMIN));
  }

  @Test
  void orderNoticesOpenTheOrderOnTheMatchingTab() {
    assertEquals("/orders/" + ID, target("JOB_ORDER_CREATED", "JOB_ORDER"));
    assertEquals("/orders/" + ID, target("JOB_ORDER_FINISHED", "JOB_ORDER"));
    assertEquals("/orders/" + ID + "?tab=assignees", target("JOB_ORDER_ASSIGNED", "JOB_ORDER"));
    assertEquals(
        "/orders/" + ID + "?tab=materials", target("JOB_ORDER_CLAIM_WITHDRAWN", "JOB_ORDER"));
    assertNull(target("JOB_ORDER_FINISHED", "JOB_ORDER_DELETED"));
    assertNull(NotificationTargets.targetOf("JOB_ORDER_CREATED", "JOB_ORDER", null, MEMBER));
  }

  @Test
  void refineryNoticesOpenTheRefineryOrder() {
    assertEquals("/refinery-orders/" + ID, target("REFINERY_ORDER_READY", "REFINERY_ORDER"));
    assertEquals(
        "/refinery-orders/" + ID, target("REFINERY_ORDER_CHANGED_BY_OTHER", "REFINERY_ORDER"));
  }

  @Test
  void missionAndOperationNoticesOpenTheRecordOnTheMatchingTab() {
    assertEquals("/missions/" + ID, target("MISSION_RESCHEDULED", "MISSION"));
    assertEquals("/missions/" + ID + "?tab=crew", target("MISSION_REMINDER", "MISSION"));
    assertEquals("/missions/" + ID + "?tab=crew", target("MISSION_CHECKIN_OPEN", "MISSION"));
    assertEquals("/missions/" + ID + "?tab=verw", target("MISSION_NEVER_ENDED", "MISSION"));
    assertEquals(
        "/missions/" + ID + "?tab=crew", target("HANGAR_SHIP_REMOVED_FROM_UNIT", "MISSION"));
    assertEquals("/operations/" + ID + "?tab=payout", target("OPERATION_COMPLETED", "OPERATION"));
  }

  @Test
  void bookingRequestsOpenTheQueueForStaffAndTheOrgUnitBankForMembers() {
    assertEquals(
        "/bank/requests?focus=" + ID,
        NotificationTargets.targetOf(
            "BANK_BOOKING_REQUEST_CREATED", "BANK_BOOKING_REQUEST", ID, BANK_STAFF));
    assertEquals(
        "/org-unit-bank?focus=" + ID + "#tab=fremde",
        target("BANK_BOOKING_REQUEST_CREATED", "BANK_BOOKING_REQUEST"));
    assertEquals(
        "/org-unit-bank?focus=" + ID + "#tab=antraege",
        target("BANK_BOOKING_REQUEST_APPROVED", "BANK_BOOKING_REQUEST"));
    assertEquals(
        "/org-unit-bank?focus=" + ID + "#tab=antraege",
        target("BANK_BOOKING_REQUEST_REJECTED", "BANK_BOOKING_REQUEST"));
    assertEquals(
        "/org-unit-bank?focus=" + ID + "#tab=fremde",
        target("BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED", "BANK_BOOKING_REQUEST"));
    assertEquals(
        "/org-unit-bank#tab=antraege",
        NotificationTargets.targetOf(
            "BANK_BOOKING_REQUEST_CONFIRMED", "BANK_BOOKING_REQUEST", null, MEMBER));
  }

  @Test
  void otherBankNoticesOpenWhatTheirRecipientCanSee() {
    assertEquals(
        "/org-unit-bank/accounts/" + ID,
        target("BANK_ACCOUNT_RESPONSIBLE_ASSIGNED", "BANK_ACCOUNT"));
    assertEquals("/bank/accounts/" + ID, target("BANK_GRANT_CHANGED", "BANK_ACCOUNT_GRANT"));
    assertEquals("/org-unit-bank", target("BANK_ACCOUNT_DEBITED", "BANK_TRANSACTION"));
    assertEquals(
        "/bank/holders/" + ID, target("BANK_HOLDER_DEACTIVATED_WITH_BALANCE", "BANK_HOLDER"));
  }

  @Test
  void listSubjectsAreFocusedOnTheirListPage() {
    assertEquals(
        "/supply/donations/incoming?focus=" + ID,
        target("SUPPLY_DONATION_PLEDGED", "SUPPLY_DONATION"));
    assertEquals(
        "/supply/donations/mine?focus=" + ID,
        target("SUPPLY_DONATION_RECEIVED", "SUPPLY_DONATION"));
    assertEquals("/supply/needs?focus=" + ID, target("SUPPLY_NEED_ORDER_COVERED", "SUPPLY_NEED"));
    assertEquals(
        "/connected-apps?focus=" + ID, target("EXCHANGE_CLIENT_SUSPENDED", "EXCHANGE_CLIENT"));
    assertEquals(
        "/connected-apps?focus=" + ID,
        target("EXCHANGE_INSTALLATION_CONNECTED", "EXCHANGE_INSTALLATION"));
    assertEquals(
        "/purchasing/delegates?focus=" + ID,
        target("PURCHASING_DELEGATE_APPOINTED", "PURCHASING_DELEGATE"));
    assertEquals("/supply/needs", target("SUPPLY_NEED_ORDER_COVERED", "OTHER"));
  }

  @Test
  void materialboerseNoticesSelectTheEntry() {
    assertEquals(
        "/materialboerse?view=offers&scope=mine&selected=" + ID,
        target("MATERIAL_EXCHANGE_INTEREST_REGISTERED", "MATERIAL_EXCHANGE_OFFER"));
    assertEquals(
        "/materialboerse?view=requests&scope=mine&selected=" + ID,
        target("MATERIAL_REQUEST_FULFILLMENT_SIGNALLED", "MATERIAL_EXCHANGE_REQUEST"));
    assertEquals(
        "/materialboerse",
        target("MATERIAL_EXCHANGE_OFFER_UNAVAILABLE", "MATERIAL_EXCHANGE_OFFER"));
    assertEquals(
        "/materialboerse?view=requests",
        target("MATERIAL_REQUEST_UNAVAILABLE", "MATERIAL_EXCHANGE_REQUEST"));
  }

  @Test
  void pageSubjectsOpenTheirPage() {
    assertEquals("/inventory/my", target("INVENTORY_TRANSFERRED_TO_USER", "INVENTORY_ITEM"));
    assertEquals("/hangar", target("HANGAR_SHIP_ASSIGNED", "MISSION_UNIT"));
    assertEquals(
        "/personal-inventory/blueprints",
        target("BLUEPRINT_PURGED_BY_ADMIN", "PERSONAL_BLUEPRINTS"));
    assertEquals(
        "/profile#profile-deletion-host",
        target("ACCOUNT_DELETION_REQUEST_DECLINED", "DELETION_REQUEST"));
    assertEquals(
        "/purchasing/deals/" + ID, target("PURCHASING_DELIVERY_DECIDED", "PURCHASING_DEAL"));
    assertEquals("/purchasing/deals", target("PURCHASING_PAUSED", "PURCHASING_PAUSE"));
  }

  @Test
  void adminAndOfficerPagesLinkOnlyForViewersWhoCanOpenThem() {
    assertEquals(
        "/admin/discord-registrations?focus=" + ID,
        NotificationTargets.targetOf(
            "DISCORD_REGISTRATION_PENDING", "DISCORD_REGISTRATION", ID, ADMIN));
    assertNull(target("DISCORD_REGISTRATION_PENDING", "DISCORD_REGISTRATION"));
    assertEquals(
        "/admin/deletion-requests?focus=" + ID,
        NotificationTargets.targetOf("ACCOUNT_DELETION_REQUESTED", "DELETION_REQUEST", ID, ADMIN));
    assertEquals(
        "/members/" + ID + "/edit",
        NotificationTargets.targetOf("ORG_LEADERSHIP_ROLE_MISMATCH", "ORG_UNIT_MEMBER", ID, ADMIN));
    assertNull(target("ORG_LEADERSHIP_ROLE_MISMATCH", "ORG_UNIT_MEMBER"));
    assertEquals(
        "/organisation/leitung",
        NotificationTargets.targetOf("ORG_MEMBER_DEPARTED", "ORG_UNIT_MEMBER", ID, OFFICER));
    assertNull(target("ORG_MEMBER_DEPARTED", "ORG_UNIT_MEMBER"));
  }

  @Test
  void viewerAppliesTheRoleHierarchy() {
    Viewer management =
        Viewer.of(
            SecurityConfig.roleHierarchy()
                .getReachableGrantedAuthorities(
                    List.of(new SimpleGrantedAuthority(Roles.authority(Roles.BANK_MANAGEMENT)))));
    assertEquals(new Viewer(false, false, true), management);

    Viewer admin =
        Viewer.of(
            SecurityConfig.roleHierarchy()
                .getReachableGrantedAuthorities(
                    List.of(new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN)))));
    assertEquals(new Viewer(true, false, true), admin);
    assertEquals(MEMBER, Viewer.of(List.of()));
  }
}
