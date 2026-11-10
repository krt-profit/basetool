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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Serializes reintroduction of the same physical or journal-reference evidence. */
@NoRepositoryBean
interface InventoryQuantityEvidenceLockRepository extends Repository<InventoryItem, UUID> {

  /** Distinct identity domains that never infer proof from a storage row or timestamp. */
  enum Kind {
    /** A proved durable physical origin. */
    PHYSICAL,
    /** Unproved quantity referenced by a real server journal entry. */
    JOURNAL_REFERENCE
  }

  /**
   * A declared proof identity independent of its advisory-key representation.
   *
   * @param kind the disjoint evidence domain
   * @param reference the actual origin or real journal identity
   */
  record Identity(@NotNull Kind kind, @NotNull UUID reference) {
    /** Requires an explicit domain and real identity without a guessed default. */
    public Identity {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(reference);
    }
  }

  /**
   * Derives the restoration advisory key without changing the existing stock-lot namespace.
   *
   * @param identity the declared typed evidence identity
   * @return the signed first eight bytes of the namespaced SHA-256 digest
   */
  static long advisoryKey(@NotNull Identity identity) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  ("inventory-restoration-proof|"
                          + identity.kind().name()
                          + '|'
                          + identity.reference())
                      .getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(digest).getLong();
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is not available", failure);
    }
  }

  /**
   * Holds one restoration-evidence lock until the originating transaction commits or rolls back.
   *
   * @param key the raw namespaced advisory key
   * @return always one
   */
  @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
  int lockRestorationEvidence(@Param("key") long key);
}
