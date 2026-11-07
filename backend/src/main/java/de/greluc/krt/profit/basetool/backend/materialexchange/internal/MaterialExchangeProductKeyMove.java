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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import de.greluc.krt.profit.basetool.backend.blueprint.api.ProductKeyChangeParticipant;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Moves the Materialbörse's item offers and item requests onto a product's GUID key when its name
 * key is upgraded (REQ-INV-070, ADR-0343); each moved row's version is bumped.
 */
@Component
@RequiredArgsConstructor
public class MaterialExchangeProductKeyMove implements ProductKeyChangeParticipant {

  private final MaterialExchangeOfferRepository offerRepository;
  private final MaterialExchangeRequestRepository requestRepository;

  @Override
  public @NotNull @Unmodifiable Set<String> referencedProductKeys() {
    Set<String> keys = new HashSet<>(offerRepository.findAllDistinctItemProductKeys());
    keys.addAll(requestRepository.findAllDistinctItemProductKeys());
    return Set.copyOf(keys);
  }

  @Override
  public void moveProductKey(@NotNull String from, @NotNull String to) {
    offerRepository.moveItemProductKey(from, to);
    requestRepository.moveItemProductKey(from, to);
  }
}
