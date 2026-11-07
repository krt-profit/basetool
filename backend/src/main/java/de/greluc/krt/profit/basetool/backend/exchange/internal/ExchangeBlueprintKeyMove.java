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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.blueprint.api.ProductKeyChangeParticipant;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Moves the blueprint change feed and the exchange journal onto a product's GUID key when its name
 * key is upgraded (REQ-XCH-015, ADR-0343). The earlier feed entries keep their positions, so a
 * removal another channel made stays a live tombstone of the GUID key; the move of the member's row
 * that follows records the name key's removal and the GUID key's arrival as new entries.
 */
@Component
@RequiredArgsConstructor
public class ExchangeBlueprintKeyMove implements ProductKeyChangeParticipant {

  private final ExchangeChangeRepository changeRepository;
  private final ExchangeJournalRepository journalRepository;

  @Override
  public @NotNull @Unmodifiable Set<String> referencedProductKeys() {
    Set<String> keys = new HashSet<>(changeRepository.findBlueprintEntityKeys());
    keys.addAll(journalRepository.findBlueprintEntityKeys());
    return Set.copyOf(keys);
  }

  @Override
  public void moveProductKey(@NotNull String from, @NotNull String to) {
    changeRepository.moveBlueprintKey(from, to);
    journalRepository.moveBlueprintKey(from, to);
  }
}
