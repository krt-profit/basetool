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

package de.greluc.krt.profit.basetool.backend.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Gives directly constructed Inventory write fixtures a transaction synchronization boundary. */
public final class InventoryWritePlanExtension implements BeforeEachCallback, AfterEachCallback {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(InventoryWritePlanExtension.class);

  @Override
  public void beforeEach(ExtensionContext context) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.initSynchronization();
      context.getStore(NAMESPACE).put("ownsSynchronization", true);
    }
  }

  @Override
  public void afterEach(ExtensionContext context) {
    if (Boolean.TRUE.equals(context.getStore(NAMESPACE).remove("ownsSynchronization"))) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }
}
