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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.NotificationMute;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationPreferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.NotificationMuteRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member's choice of which notification types to receive (REQ-NOTIF-027): reads and writes the
 * preferences and drops muted members from the recipients the rules resolve.
 */
@Service
@RequiredArgsConstructor
public class NotificationMuteService {

  private final NotificationMuteRepository notificationMuteRepository;
  private final MeterRegistry meterRegistry;

  /**
   * Lists every notification type with whether the member may mute it and whether they have.
   *
   * @param userId the member
   * @return one entry per {@link NotificationType}, in declaration order; never {@code null}
   */
  @Transactional(readOnly = true)
  @NotNull
  public List<NotificationPreferenceDto> preferences(@NotNull UUID userId) {
    Set<NotificationType> muted = new HashSet<>();
    for (NotificationMute mute : notificationMuteRepository.findByUserId(userId)) {
      muted.add(mute.getType());
    }
    return Arrays.stream(NotificationType.values())
        .map(
            type ->
                new NotificationPreferenceDto(
                    type, type.isMutable(), type.isMutable() && muted.contains(type)))
        .toList();
  }

  /**
   * Mutes or unmutes one type for a member. Idempotent: muting a muted type or unmuting a type that
   * is not muted changes nothing.
   *
   * @param userId the member
   * @param type the notification type
   * @param muted {@code true} to mute, {@code false} to receive it again
   * @throws IllegalArgumentException when the type cannot be muted and {@code muted} is {@code
   *     true}
   */
  @Transactional
  public void setMuted(@NotNull UUID userId, @NotNull NotificationType type, boolean muted) {
    if (!muted) {
      notificationMuteRepository.deleteByUserIdAndType(userId, type);
      return;
    }
    if (!type.isMutable()) {
      throw new IllegalArgumentException("Notification type " + type + " cannot be muted");
    }
    if (!notificationMuteRepository.existsByUserIdAndType(userId, type)) {
      notificationMuteRepository.save(NotificationMute.builder().userId(userId).type(type).build());
    }
  }

  /**
   * Removes the members who muted a type from the recipients the rules resolved. A type that cannot
   * be muted is never filtered, whatever rows exist.
   *
   * @param recipientsByType the resolved recipients per notification type
   * @return the same recipients without the muted members; empty types are dropped; never {@code
   *     null}
   */
  @Transactional(readOnly = true)
  @NotNull
  public Map<NotificationType, Set<UUID>> withoutMuted(
      @NotNull Map<NotificationType, Set<UUID>> recipientsByType) {
    Map<NotificationType, Set<UUID>> kept = new EnumMap<>(NotificationType.class);
    for (Map.Entry<NotificationType, Set<UUID>> entry : recipientsByType.entrySet()) {
      NotificationType type = entry.getKey();
      Set<UUID> recipients = new HashSet<>(entry.getValue());
      if (type.isMutable() && !recipients.isEmpty()) {
        int before = recipients.size();
        recipients.removeAll(notificationMuteRepository.findMutedUserIds(type, recipients));
        int dropped = before - recipients.size();
        if (dropped > 0) {
          meterRegistry
              .counter(
                  MetricNames.NOTIFICATION_MUTED, MetricNames.TAG_NOTIFICATION_TYPE, type.name())
              .increment(dropped);
        }
      }
      if (!recipients.isEmpty()) {
        kept.put(type, recipients);
      }
    }
    return kept;
  }
}
