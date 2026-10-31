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

package de.greluc.krt.profit.basetool.backend.mission.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests of {@link MemberPayoutSettings}: absent rows, own versions and the optimistic lock.
 */
@ExtendWith(MockitoExtension.class)
class MemberPayoutSettingsTest {

  private static final UUID USER = UUID.randomUUID();

  @Mock private MemberPayoutSettingRepository repository;
  @Mock private UserRepository userRepository;

  @InjectMocks private MemberPayoutSettings settings;

  private static MemberPayoutSetting row(PayoutPreference preference, long version) {
    MemberPayoutSetting row = new MemberPayoutSetting();
    row.setId(USER);
    row.setDefaultPayoutPreference(preference);
    row.setVersion(version);
    return row;
  }

  @Test
  void aMemberWithoutARowHasNoChoiceAtVersionZero() {
    when(repository.findById(USER)).thenReturn(Optional.empty());

    assertThat(settings.snapshotOf(USER)).isEqualTo(new MemberPayoutSettings.Snapshot(null, 0L));
    assertThat(settings.defaultFor(USER)).isEmpty();
  }

  @Test
  void aStoredRowIsReadWithItsOwnVersion() {
    when(repository.findById(USER)).thenReturn(Optional.of(row(PayoutPreference.DONATE, 4L)));

    assertThat(settings.snapshotOf(USER))
        .isEqualTo(new MemberPayoutSettings.Snapshot(PayoutPreference.DONATE, 4L));
    assertThat(settings.defaultFor(USER)).contains(PayoutPreference.DONATE);
  }

  @Test
  void theFirstWriteCreatesTheRowAndAcceptsVersionZero() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(MemberPayoutSetting.class)))
        .thenAnswer(invocation -> {
          MemberPayoutSetting saved = invocation.getArgument(0);
          saved.setVersion(0L);
          return saved;
        });

    MemberPayoutSettings.Snapshot result = settings.update(USER, PayoutPreference.DONATE, 0L);

    assertThat(result).isEqualTo(new MemberPayoutSettings.Snapshot(PayoutPreference.DONATE, 0L));
  }

  @Test
  void aWriteWithTheMatchingVersionUpdatesTheRow() {
    MemberPayoutSetting stored = row(PayoutPreference.PAYOUT, 2L);
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(stored));
    when(repository.saveAndFlush(stored)).thenReturn(stored);

    settings.update(USER, PayoutPreference.DONATE, 2L);

    assertThat(stored.getDefaultPayoutPreference()).isEqualTo(PayoutPreference.DONATE);
  }

  @Test
  void aNullVersionSkipsTheCheck() {
    MemberPayoutSetting stored = row(PayoutPreference.PAYOUT, 5L);
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(stored));
    when(repository.saveAndFlush(stored)).thenReturn(stored);

    settings.update(USER, PayoutPreference.DONATE, null);

    assertThat(stored.getDefaultPayoutPreference()).isEqualTo(PayoutPreference.DONATE);
  }

  @Test
  void aStaleVersionIsRefusedAndNothingIsWritten() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(row(PayoutPreference.PAYOUT, 7L)));

    assertThatThrownBy(() -> settings.update(USER, PayoutPreference.DONATE, 3L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void aVersionOtherThanZeroIsStaleForAMemberWithoutARow() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> settings.update(USER, PayoutPreference.DONATE, 4L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void anUnknownMemberIsNotFound() {
    when(userRepository.existsById(USER)).thenReturn(false);

    assertThatThrownBy(() -> settings.update(USER, PayoutPreference.DONATE, 0L))
        .isInstanceOf(NotFoundException.class);
    verify(repository, never()).saveAndFlush(any());
  }
}
