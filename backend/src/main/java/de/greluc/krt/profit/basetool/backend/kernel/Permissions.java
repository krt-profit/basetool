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

package de.greluc.krt.profit.basetool.backend.kernel;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Constants for the fine-grained permission strings a {@code Role} carries in its {@code
 * permissions} collection, read back as {@code GrantedAuthority} names without {@code ROLE_}.
 *
 * <p>The public constants form the closed permission vocabulary stored in {@code role_permissions}.
 */
public final class Permissions {

  private static final Set<String> VOCABULARY = readVocabulary();

  /** Non-instantiable static-constant holder. */
  private Permissions() {}

  public static final String HANGAR_READ = "HANGAR_READ";
  public static final String HANGAR_WRITE = "HANGAR_WRITE";
  public static final String MISSION_READ = "MISSION_READ";
  public static final String MISSION_WRITE = "MISSION_WRITE";
  public static final String MISSION_MANAGE = "MISSION_MANAGE";
  public static final String USER_MANAGE = "USER_MANAGE";
  public static final String ROLE_MANAGE = "ROLE_MANAGE";

  /**
   * Checks whether a permission belongs to the vocabulary.
   *
   * @param value the value to check
   * @return whether the value matches a declared permission
   */
  public static boolean isKnown(@Nullable String value) {
    return value != null && VOCABULARY.contains(value);
  }

  private static Set<String> readVocabulary() {
    Set<String> vocabulary = new HashSet<>();
    for (Field field : Permissions.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (field.isSynthetic()
          || !Modifier.isPublic(modifiers)
          || !Modifier.isStatic(modifiers)
          || !Modifier.isFinal(modifiers)
          || field.getType() != String.class) {
        continue;
      }
      try {
        vocabulary.add((String) field.get(null));
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("Cannot read permission constant " + field.getName(), e);
      }
    }
    return Set.copyOf(vocabulary);
  }
}
