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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A counter that moves whenever a P4K APPLY or a review resolution writes, so readers of derived
 * P4K figures ({@link P4kCatalogueBusinessGauges}) know when to read them again.
 */
@Component
public class P4kCatalogueRevision {

  private final AtomicLong revision = new AtomicLong();

  /**
   * Marks a P4K write: the revision moves at once, for a reader in the writing transaction, and
   * again after the commit, so a reader that read before the commit reads the committed rows.
   */
  public void changed() {
    revision.incrementAndGet();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              revision.incrementAndGet();
            }
          });
    }
  }

  /**
   * Returns the current revision.
   *
   * @return a number that differs from an earlier one once a P4K write happened in between
   */
  public long current() {
    return revision.get();
  }
}
