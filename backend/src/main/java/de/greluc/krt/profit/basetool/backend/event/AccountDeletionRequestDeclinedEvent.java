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

package de.greluc.krt.profit.basetool.backend.event;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import java.util.Map;
import java.util.UUID;

/**
 * Domain event published when an admin refuses a member's erasure request (REQ-SEC-061). The seeded
 * default rule notifies the requesting member through the {@code EVENT_RECIPIENT} selector.
 *
 * <p>This notification is not a courtesy. <b>Art. 12(4) obliges the controller to tell the
 * requester that the request was refused</b>, and to do so without undue delay; the reasoning,
 * their right to complain to a supervisory authority and their right to a judicial remedy go with
 * it. The notification is what makes that happen in-app rather than depending on an admin
 * remembering.
 *
 * <p><b>The reasoning itself does not ride the event.</b> The admin's note lives on the request
 * row; a notification is rendered from a bounded message template with bounded parameters
 * (REQ-NOTIF-002), and pushing free text through it would put an admin's prose into every
 * recipient's inbox payload. The member reads the reason on their profile page, where the request
 * is shown.
 *
 * @param userId the requesting member, who is the single recipient
 */
public record AccountDeletionRequestDeclinedEvent(UUID userId) implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notifications for deep-linking. */
  public static final String ENTITY_TYPE = "DELETION_REQUEST";

  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.ACCOUNT_DELETION_REQUEST_DECLINED;
  }

  @Override
  public UUID actorSub() {
    // The acting admin is not carried: excluding them would be meaningless here (the sole recipient
    // is the member) and naming them would tell the member which admin refused, which is the
    // controller's decision to communicate, not the notification engine's.
    return null;
  }

  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @Override
  public String entityType() {
    return ENTITY_TYPE;
  }

  @Override
  public UUID entityId() {
    return userId;
  }

  @Override
  public Map<String, String> renderParams() {
    // The message names no one and quotes nothing: "your deletion request was declined; the reason
    // is on your profile page".
    return Map.of();
  }

  /**
   * The single recipient the {@code EVENT_RECIPIENT} selector resolves to — the requesting member.
   *
   * @return the requesting member's id
   */
  @Override
  public UUID contextRecipientUserId() {
    return userId;
  }
}
