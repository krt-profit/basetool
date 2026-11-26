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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * Unit tests of {@link MemberSaleValueSettings} (REQ-INV-072): the average price without a row, the
 * row's own version and the optimistic lock.
 */
@ExtendWith(MockitoExtension.class)
class MemberSaleValueSettingsTest {

  private static final UUID USER = UUID.randomUUID();

  @Mock private MemberSaleValueSettingRepository repository;
  @Mock private UserRepository userRepository;

  @InjectMocks private MemberSaleValueSettings settings;

  private static MemberSaleValueSetting row(SalePriceBasis basis, long version) {
    MemberSaleValueSetting row = new MemberSaleValueSetting();
    row.setId(USER);
    row.setSalePriceBasis(basis);
    row.setVersion(version);
    return row;
  }

  @Test
  void aMemberWithoutARowValuesAtTheAveragePriceAtVersionZero() {
    when(repository.findById(USER)).thenReturn(Optional.empty());

    assertThat(settings.snapshotOf(USER))
        .isEqualTo(new MemberSaleValueSettings.Snapshot(SalePriceBasis.AVERAGE, 0L));
    assertThat(settings.basisFor(USER)).isEqualTo(SalePriceBasis.AVERAGE);
  }

  @Test
  void aStoredRowIsReadWithItsOwnVersion() {
    when(repository.findById(USER)).thenReturn(Optional.of(row(SalePriceBasis.HIGHEST, 4L)));

    assertThat(settings.snapshotOf(USER))
        .isEqualTo(new MemberSaleValueSettings.Snapshot(SalePriceBasis.HIGHEST, 4L));
    assertThat(settings.basisFor(USER)).isEqualTo(SalePriceBasis.HIGHEST);
  }

  @Test
  void theFirstWriteCreatesTheRowAndAcceptsVersionZero() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(MemberSaleValueSetting.class)))
        .thenAnswer(invocation -> {
          MemberSaleValueSetting saved = invocation.getArgument(0);
          saved.setVersion(0L);
          return saved;
        });

    MemberSaleValueSettings.Snapshot result = settings.update(USER, SalePriceBasis.LOWEST, 0L);

    assertThat(result).isEqualTo(new MemberSaleValueSettings.Snapshot(SalePriceBasis.LOWEST, 0L));
  }

  @Test
  void aWriteWithTheMatchingVersionUpdatesTheRow() {
    MemberSaleValueSetting stored = row(SalePriceBasis.AVERAGE, 2L);
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(stored));
    when(repository.saveAndFlush(stored)).thenReturn(stored);

    settings.update(USER, SalePriceBasis.HIGHEST, 2L);

    assertThat(stored.getSalePriceBasis()).isEqualTo(SalePriceBasis.HIGHEST);
  }

  @Test
  void aStaleVersionIsRefusedAndNothingIsWritten() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.of(row(SalePriceBasis.AVERAGE, 7L)));

    assertThatThrownBy(() -> settings.update(USER, SalePriceBasis.LOWEST, 3L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void aVersionOtherThanZeroIsStaleForAMemberWithoutARow() {
    when(userRepository.existsById(USER)).thenReturn(true);
    when(repository.findById(USER)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> settings.update(USER, SalePriceBasis.LOWEST, 4L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void anUnknownMemberIsNotFound() {
    when(userRepository.existsById(USER)).thenReturn(false);

    assertThatThrownBy(() -> settings.update(USER, SalePriceBasis.LOWEST, 0L))
        .isInstanceOf(NotFoundException.class);
    verify(repository, never()).saveAndFlush(any());
  }
}
