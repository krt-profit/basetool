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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ChangeSource;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The JPA transaction manager that tells every writing transaction who is writing, so the
 * change-feed triggers can attribute what they record (REQ-XCH-013, ADR-0224).
 *
 * <p>At the start of each transaction that is not read-only it sets the transaction-local variable
 * {@value ChangeSource#VARIABLE}; the variable ends with the transaction.
 */
public class ChangeSourceTransactionManager extends JpaTransactionManager {

  /** Names who is writing. */
  private final transient ChangeSource changeSource;

  /**
   * Creates the manager.
   *
   * @param entityManagerFactory the persistence unit
   * @param changeSource names who is writing
   */
  public ChangeSourceTransactionManager(
      @NotNull EntityManagerFactory entityManagerFactory, @NotNull ChangeSource changeSource) {
    super(entityManagerFactory);
    this.changeSource = changeSource;
  }

  @Override
  protected void doBegin(@NotNull Object transaction, @NotNull TransactionDefinition definition) {
    super.doBegin(transaction, definition);
    if (definition.isReadOnly()) {
      return;
    }
    EntityManagerHolder holder =
        (EntityManagerHolder)
            TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
    if (holder == null) {
      return;
    }
    holder
        .getEntityManager()
        .createNativeQuery("SELECT set_config('" + ChangeSource.VARIABLE + "', :source, true)")
        .setParameter("source", changeSource.current())
        .getSingleResult();
  }
}
