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
 * Domain event published right after a member registers interest in a Materialbörse offer (#1187,
 * REQ-MARKET-011). It is directed at the offer's owner (the Anbieter) so they learn about the
 * interested party without having to poll the board: the {@code EVENT_RECIPIENT} selector resolves
 * to {@link #contextRecipientUserId()} (the owner), while the registering member is the {@link
 * #actorSub()} (excluded when the rule sets {@code excludeActor} — harmless here since a member can
 * never register interest in their own offer, so actor and recipient are always distinct).
 *
 * <p>Carries only immutable scalars (ids and pre-resolved display strings) so the after-commit
 * listener never touches the managed offer/interest entities. The interessent's name is carried as
 * a render parameter: this is a permitted disclosure because the notification reaches only the
 * owner (REQ-MARKET-006's interessenten anonymity is owner-only), and only the opaque {@code type}
 * + {@code params} — never a rendered string — are stored (REQ-NOTIF-001).
 *
 * @param offerId the offer whose interest was registered (also the notification's loose entity id)
 * @param materialName the offered material's name, for rendering
 * @param interestedUserName the registering member's effective name, for rendering (owner-only)
 * @param ownerUserId the offer owner's sub — the directed recipient
 * @param actorSub the registering member's sub
 */
public record MaterialExchangeInterestRegisteredEvent(
    UUID offerId,
    String materialName,
    String interestedUserName,
    @Nullable UUID ownerUserId,
    @Nullable UUID actorSub)
    implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notification for deep-linking to the board. */
  public static final String ENTITY_TYPE = "MATERIAL_EXCHANGE_OFFER";

  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.MATERIAL_EXCHANGE_INTEREST_REGISTERED;
  }

  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @Override
  public UUID contextRecipientUserId() {
    return ownerUserId;
  }

  @Override
  public String entityType() {
    return ENTITY_TYPE;
  }

  @Override
  public UUID entityId() {
    return offerId;
  }

  @Override
  public Map<String, String> renderParams() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("interessent", interestedUserName);
    params.put("material", materialName);
    return params;
  }
}
