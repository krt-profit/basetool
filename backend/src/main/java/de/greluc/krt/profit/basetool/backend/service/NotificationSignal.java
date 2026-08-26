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

import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a real-time push tells its recipient, beyond "something changed".
 *
 * <p>The SSE {@code notification} event used to carry the literal string {@code "new"}. That is
 * enough for the web app, whose handler ignores the payload and refetches the unread count, and not
 * enough for the Android app: without a kind it cannot file the shade entry under the right
 * notification channel, and without an entity it cannot deep-link the tap to the screen the message
 * is about — both of which its design specification requires (REQ-APP-UI-007).
 *
 * <p><strong>A signal is per notification type, not per event.</strong> One event resolves to a
 * {@code Map<NotificationType, Set<UUID>>}: the same trigger can raise different kinds for
 * different audiences. Two recipients of one event may therefore be told two different things, and
 * a signal that described the event rather than the notification would be wrong for at least one of
 * them.
 *
 * <p><strong>A refresh-only signal carries no type.</strong> When an event <em>clears</em> stale
 * items (REQ-NOTIF-018) the affected recipients get nothing new — their inbox changed and their
 * badge must move, but there is no message to file or open. {@link #refreshOnly()} is that case,
 * and it is what the payload looked like for every recipient before this existed.
 *
 * <p>The render parameters travel because the recipient's own inbox already returns them over the
 * same authenticated connection; nothing here is visible to anyone the notification was not
 * addressed to.
 *
 * @param type what kind of notification was raised, or {@code null} for a refresh-only push
 * @param entityType the originating aggregate's type tag, e.g. {@code JOB_ORDER}; {@code null} on a
 *     refresh-only push
 * @param entityId the originating aggregate's id, {@code null} on a refresh-only push
 * @param params the i18n render parameters the client substitutes into its own wording; never
 *     {@code null}, empty on a refresh-only push
 */
public record NotificationSignal(
    @Nullable NotificationType type,
    @Nullable String entityType,
    @Nullable UUID entityId,
    @NotNull Map<String, String> params) {

  /**
   * The signal for a recipient whose inbox changed without a new message arriving.
   *
   * @return a signal carrying no kind and no entity
   */
  @NotNull
  public static NotificationSignal refreshOnly() {
    return new NotificationSignal(null, null, null, Map.of());
  }

  /**
   * Whether this signal describes a message, as opposed to a bare "your inbox changed".
   *
   * @return {@code true} when a kind is present
   */
  public boolean describesNotification() {
    return type != null;
  }
}
