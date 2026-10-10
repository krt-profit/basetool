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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.NotificationMute;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationPreferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.NotificationMuteRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationMuteServiceTest {

  private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  @Mock private NotificationMuteRepository notificationMuteRepository;

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private NotificationMuteService service() {
    return new NotificationMuteService(notificationMuteRepository, meterRegistry);
  }

  @Test
  void theDeadlineAndSecurityTypesCannotBeMutedAndEveryOtherTypeCan() {
    assertThat(NotificationType.values())
        .filteredOn(type -> !type.isMutable())
        .containsExactlyInAnyOrder(
            NotificationType.ACCOUNT_DELETION_REQUESTED,
            NotificationType.ACCOUNT_DELETION_REQUEST_DECLINED,
            NotificationType.EXCHANGE_INSTALLATION_CONNECTED);
  }

  @Test
  void preferencesListsEveryTypeAndMarksTheMutedOnes() {
    when(notificationMuteRepository.findByUserId(ALICE))
        .thenReturn(
            List.of(
                NotificationMute.builder()
                    .userId(ALICE)
                    .type(NotificationType.JOB_ORDER_CREATED)
                    .build(),
                NotificationMute.builder()
                    .userId(ALICE)
                    .type(NotificationType.ACCOUNT_DELETION_REQUESTED)
                    .build()));

    List<NotificationPreferenceDto> preferences = service().preferences(ALICE);

    assertThat(preferences).hasSize(NotificationType.values().length);
    assertThat(preferences)
        .filteredOn(p -> p.type() == NotificationType.JOB_ORDER_CREATED)
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.mutable()).isTrue();
              assertThat(p.muted()).isTrue();
            });
    assertThat(preferences)
        .filteredOn(p -> p.type() == NotificationType.BANK_BOOKING_REQUEST_CREATED)
        .singleElement()
        .satisfies(p -> assertThat(p.muted()).isFalse());
    assertThat(preferences)
        .filteredOn(p -> p.type() == NotificationType.ACCOUNT_DELETION_REQUESTED)
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.mutable()).isFalse();
              assertThat(p.muted()).isFalse();
            });
  }

  @Test
  void mutingStoresARowOnce() {
    when(notificationMuteRepository.existsByUserIdAndType(
            ALICE, NotificationType.JOB_ORDER_CREATED))
        .thenReturn(false);

    service().setMuted(ALICE, NotificationType.JOB_ORDER_CREATED, true);

    ArgumentCaptor<NotificationMute> saved = ArgumentCaptor.forClass(NotificationMute.class);
    verify(notificationMuteRepository).save(saved.capture());
    assertThat(saved.getValue().getUserId()).isEqualTo(ALICE);
    assertThat(saved.getValue().getType()).isEqualTo(NotificationType.JOB_ORDER_CREATED);
  }

  @Test
  void mutingAMutedTypeChangesNothing() {
    when(notificationMuteRepository.existsByUserIdAndType(
            ALICE, NotificationType.JOB_ORDER_CREATED))
        .thenReturn(true);

    service().setMuted(ALICE, NotificationType.JOB_ORDER_CREATED, true);

    verify(notificationMuteRepository, never()).save(any());
  }

  @Test
  void unmutingDeletesTheRow() {
    service().setMuted(ALICE, NotificationType.JOB_ORDER_CREATED, false);

    verify(notificationMuteRepository)
        .deleteByUserIdAndType(ALICE, NotificationType.JOB_ORDER_CREATED);
  }

  @Test
  void aTypeThatCannotBeMutedIsRefused() {
    assertThatThrownBy(
            () -> service().setMuted(ALICE, NotificationType.EXCHANGE_INSTALLATION_CONNECTED, true))
        .isInstanceOf(IllegalArgumentException.class);

    verify(notificationMuteRepository, never()).save(any());
  }

  @Test
  void aTypeThatCannotBeMutedCanStillBeUnmutedToCleanUpAStrayRow() {
    service().setMuted(ALICE, NotificationType.ACCOUNT_DELETION_REQUESTED, false);

    verify(notificationMuteRepository)
        .deleteByUserIdAndType(ALICE, NotificationType.ACCOUNT_DELETION_REQUESTED);
  }

  @Test
  void withoutMutedDropsTheMembersWhoMutedTheTypeAndCountsThem() {
    when(notificationMuteRepository.findMutedUserIds(
            org.mockito.ArgumentMatchers.eq(NotificationType.JOB_ORDER_CREATED), any()))
        .thenReturn(Set.of(ALICE));

    Map<NotificationType, Set<UUID>> kept =
        service().withoutMuted(Map.of(NotificationType.JOB_ORDER_CREATED, Set.of(ALICE, BOB)));

    assertThat(kept).containsOnlyKeys(NotificationType.JOB_ORDER_CREATED);
    assertThat(kept.get(NotificationType.JOB_ORDER_CREATED)).containsExactly(BOB);
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.NOTIFICATION_MUTED,
                    MetricNames.TAG_NOTIFICATION_TYPE,
                    NotificationType.JOB_ORDER_CREATED.name())
                .count())
        .isEqualTo(1);
  }

  @Test
  void withoutMutedDropsATypeWhoseEveryRecipientMutedIt() {
    when(notificationMuteRepository.findMutedUserIds(
            org.mockito.ArgumentMatchers.eq(NotificationType.JOB_ORDER_CREATED), any()))
        .thenReturn(Set.of(ALICE));

    assertThat(service().withoutMuted(Map.of(NotificationType.JOB_ORDER_CREATED, Set.of(ALICE))))
        .isEmpty();
  }

  @Test
  void withoutMutedNeverFiltersATypeThatCannotBeMuted() {
    Map<NotificationType, Set<UUID>> kept =
        service()
            .withoutMuted(Map.of(NotificationType.EXCHANGE_INSTALLATION_CONNECTED, Set.of(ALICE)));

    assertThat(kept.get(NotificationType.EXCHANGE_INSTALLATION_CONNECTED)).containsExactly(ALICE);
    verify(notificationMuteRepository, never()).findMutedUserIds(any(), any());
  }
}
