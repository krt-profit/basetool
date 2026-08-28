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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Domain event published right after a member signals they can supply a Materialbörse request /
 * Gesuch ("Ich kann liefern", REQ-MARKET-020). It is directed at the request's owner (the Suchende)
 * so they learn about the would-be supplier without having to poll the board: the {@code
 * EVENT_RECIPIENT} selector resolves to {@link #contextRecipientUserId()} (the requester), while
 * the signalling member is the {@link #actorSub()} (excluded when the rule sets {@code
 * excludeActor} — harmless here since a member can never signal fulfilment on their own request, so
 * actor and recipient are always distinct).
 *
 * <p>Carries only immutable scalars (ids and pre-resolved display strings) so the after-commit
 * listener never touches the managed request/interest entities. The supplier's name is carried as a
 * render parameter: this is a permitted disclosure because the notification reaches only the owner
 * (REQ-MARKET-019's supplier anonymity is owner-only), and only the opaque {@code type} + {@code
 * params} — never a rendered string — are stored (REQ-NOTIF-001).
 *
 * @param requestId the request whose fulfilment was signalled (also the notification's loose entity
 *     id)
 * @param subjectName the requested material's or item's name, for rendering
 * @param fulfillerName the signalling member's effective name, for rendering (owner-only)
 * @param requesterSub the request owner's sub — the directed recipient
 * @param actorSub the signalling member's sub
 */
public record MaterialRequestFulfillmentSignalledEvent(
    UUID requestId,
    String subjectName,
    String fulfillerName,
    @Nullable UUID requesterSub,
    @Nullable UUID actorSub)
    implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notification for deep-linking to the board. */
  public static final String ENTITY_TYPE = "MATERIAL_EXCHANGE_REQUEST";

  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.MATERIAL_REQUEST_FULFILLMENT_SIGNALLED;
  }

  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @Override
  public UUID contextRecipientUserId() {
    return requesterSub;
  }

  @Override
  public String entityType() {
    return ENTITY_TYPE;
  }

  @Override
  public UUID entityId() {
    return requestId;
  }

  @Override
  public Map<String, String> renderParams() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("lieferant", fulfillerName);
    params.put("material", subjectName);
    return params;
  }
}
