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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import de.greluc.krt.profit.basetool.frontend.inventory.client.InventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.inventory.model.MySalePriceBasisResponse;
import de.greluc.krt.profit.basetool.frontend.inventory.model.ProfileSalePriceBasisForm;
import de.greluc.krt.profit.basetool.frontend.inventory.model.SalePriceBasis;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Reads the caller's "Mein Lager" price basis for the profile's section (REQ-INV-072). A failed
 * read never fails the page: the section then shows the average price at version 0, and a save
 * against a stored row answers with the optimistic-lock toast.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SalePriceBasisPreferenceReader {

  /** The section's choice when the setting cannot be read: the backend's default. */
  private static final ProfileSalePriceBasisForm FALLBACK =
      new ProfileSalePriceBasisForm(SalePriceBasis.AVERAGE, 0L);

  /** Reads the setting on the backend. */
  private final InventoryBackendClient inventoryClient;

  /**
   * Returns the profile section's form.
   *
   * @return the stored basis with its version, or the average at version 0
   */
  public @NotNull ProfileSalePriceBasisForm profileForm() {
    MySalePriceBasisResponse stored =
        SectionFallback.load(
            log, "profile-sale-price-basis", inventoryClient::mySalePriceBasis, null);
    if (stored == null || stored.salePriceBasis() == null) {
      return FALLBACK;
    }
    return new ProfileSalePriceBasisForm(stored.salePriceBasis(), stored.version());
  }
}
