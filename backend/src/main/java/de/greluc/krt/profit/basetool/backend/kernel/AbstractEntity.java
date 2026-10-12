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

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.data.domain.Persistable;

/** Base class shared by all JPA entities (Abstract Entity). */
@MappedSuperclass
@Getter
@Setter
public abstract class AbstractEntity<K extends Serializable> implements Persistable<K> {

  @Version private Long version;

  @CreationTimestamp
  @Column(updatable = false)
  private Instant createdAt;

  @UpdateTimestamp private Instant updatedAt;

  @JsonIgnore
  @Override
  public boolean isNew() {
    return getId() == null;
  }

  @Override
  @SuppressWarnings("EqualsGetClass")
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null) {
      return false;
    }
    Class<?> otherEffectiveClass =
        o instanceof HibernateProxy otherProxy
            ? otherProxy.getHibernateLazyInitializer().getPersistentClass()
            : o.getClass();
    Class<?> thisEffectiveClass =
        this instanceof HibernateProxy thisProxy
            ? thisProxy.getHibernateLazyInitializer().getPersistentClass()
            : this.getClass();
    if (thisEffectiveClass != otherEffectiveClass) {
      return false;
    }
    AbstractEntity<?> that = (AbstractEntity<?>) o;
    return getId() != null && Objects.equals(getId(), that.getId());
  }

  @Override
  public int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
