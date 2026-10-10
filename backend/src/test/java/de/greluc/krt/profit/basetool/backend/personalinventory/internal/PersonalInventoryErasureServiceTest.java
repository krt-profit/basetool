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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PersonalInventoryErasureServiceTest {

  @Mock private PersonalInventoryItemRepository personalInventoryItemRepository;

  @InjectMocks private PersonalInventoryErasureService erasure;

  @Test
  void deletesEveryRowOfTheOwnerAndReportsTheCount() {
    UUID userId = UUID.randomUUID();
    when(personalInventoryItemRepository.deleteByOwnerUserId(userId)).thenReturn(3);

    assertThat(erasure.deleteAllItemsOf(userId)).isEqualTo(3);
  }

  @Test
  void runsOnlyInsideTheCallersTransaction() throws NoSuchMethodException {
    Method method = PersonalInventoryErasureService.class.getMethod("deleteAllItemsOf", UUID.class);

    assertThat(method.getAnnotation(Transactional.class).propagation())
        .isEqualTo(Propagation.MANDATORY);
  }
}
