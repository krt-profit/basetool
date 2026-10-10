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

package de.greluc.krt.profit.basetool.backend.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * A mocked {@link EntityManager} for unit tests of the Lager's stock commands whose {@code
 * getReference} answers with one instance per id carrying that id, as a persistence context answers
 * with its managed instance.
 */
public final class ReferenceResolvingEntityManager {

  /** Not instantiable. */
  private ReferenceResolvingEntityManager() {}

  /**
   * Creates the mock.
   *
   * @return the mocked entity manager
   */
  public static @NotNull EntityManager create() {
    Map<Object, Object> byId = new HashMap<>();
    EntityManager entityManager = mock(EntityManager.class);
    lenient()
        .when(entityManager.getReference(any(Class.class), any()))
        .thenAnswer(
            invocation -> {
              Object id = invocation.getArgument(1);
              Class<?> type = invocation.getArgument(0);
              Object existing = byId.get(id);
              if (existing != null) {
                return existing;
              }
              Object fresh = type.getDeclaredConstructor().newInstance();
              for (java.lang.reflect.Method setter : type.getMethods()) {
                if (setter.getName().equals("setId") && setter.getParameterCount() == 1) {
                  setter.invoke(fresh, id);
                  break;
                }
              }
              byId.put(id, fresh);
              return fresh;
            });
    return entityManager;
  }
}
