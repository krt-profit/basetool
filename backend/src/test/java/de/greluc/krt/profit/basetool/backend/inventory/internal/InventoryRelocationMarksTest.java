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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRelocationLock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

/**
 * Unit tests for {@link InventoryRelocationMarks}: which rows and stacks carry the „Umzug
 * ausstehend" marker and which rows the lock refuses the caller (REQ-PATCH-023).
 */
@ExtendWith(MockitoExtension.class)
class InventoryRelocationMarksTest {

  private static final Instant LIVE_AT = Instant.parse("2026-01-01T00:00:00Z");

  private final UUID pending = UUID.randomUUID();
  private final UUID refused = UUID.randomUUID();
  private final UUID chosen = UUID.randomUUID();

  @Mock private StockRelocationLock lock;

  @InjectMocks private InventoryRelocationMarks marks;

  @Test
  void marksThePendingHoldersRowsFromBeforeTheLiveTimeAndTheRefusedOnesAsLocked() {
    InventoryItemDto pendingOld = row(pending, LIVE_AT.minusSeconds(60));
    InventoryItemDto pendingUnknownTime = row(pending, null);
    InventoryItemDto pendingYoung = row(pending, LIVE_AT.plusSeconds(60));
    InventoryItemDto refusedOld = row(refused, LIVE_AT.minusSeconds(60));
    InventoryItemDto chosenOld = row(chosen, LIVE_AT.minusSeconds(60));
    when(lock.openPhaseLiveAt()).thenReturn(Optional.of(LIVE_AT));
    when(lock.pendingAmong(Set.of(pending, refused, chosen))).thenReturn(Set.of(pending, refused));
    when(lock.refusedAmong(Set.of(pending, refused))).thenReturn(Set.of(refused));

    List<InventoryItemDto> marked =
        marks
            .markRows(
                new PageImpl<>(
                    List.of(pendingOld, pendingUnknownTime, pendingYoung, refusedOld, chosenOld)))
            .getContent();

    assertThat(marked)
        .extracting(InventoryItemDto::relocationPending)
        .containsExactly(true, true, false, true, false);
    assertThat(marked)
        .extracting(InventoryItemDto::relocationLocked)
        .containsExactly(false, false, false, true, false);
  }

  @Test
  void leavesThePageAsItIsWhileNoPhaseIsOpen() {
    Page<InventoryItemDto> rows = new PageImpl<>(List.of(row(pending, null)));
    when(lock.openPhaseLiveAt()).thenReturn(Optional.empty());

    assertThat(marks.markRows(rows)).isSameAs(rows);
    verify(lock, never()).pendingAmong(any());
  }

  @Test
  void asksForThePendingStackHoldersOnlyWhenThereAreAny() {
    when(lock.pendingAmong(List.of(pending, chosen))).thenReturn(Set.of(pending));

    assertThat(marks.pendingHolders(List.of(pending, chosen))).containsExactly(pending);
    assertThat(marks.pendingHolders(List.of())).isEmpty();
    verify(lock, never()).pendingAmong(List.of());
  }

  /**
   * One row of a holder.
   *
   * @param holder the holder
   * @param createdAt the creation time, or {@code null}
   * @return the row
   */
  private static InventoryItemDto row(UUID holder, Instant createdAt) {
    return new InventoryItemDto(
        UUID.randomUUID(),
        new UserReferenceDto(holder, "holder", "Holder", "Holder", null),
        null,
        null,
        null,
        null,
        1.0,
        false,
        false,
        List.of(),
        1.0,
        List.of(),
        1.0,
        null,
        null,
        0L,
        true,
        createdAt);
  }
}
