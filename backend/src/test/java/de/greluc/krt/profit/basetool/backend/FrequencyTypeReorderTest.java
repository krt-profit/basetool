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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.FrequencyType;
import de.greluc.krt.profit.basetool.backend.repository.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.service.FrequencyTypeService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class FrequencyTypeReorderTest {

  @Autowired private FrequencyTypeService frequencyTypeService;

  @Autowired private FrequencyTypeRepository frequencyTypeRepository;

  @Test
  @Transactional
  void testReorder() {
    FrequencyType ft1 = new FrequencyType();
    ft1.setName("Type A");
    ft1.setSortIndex(0);
    ft1 = frequencyTypeRepository.saveAndFlush(ft1);

    FrequencyType ft2 = new FrequencyType();
    ft2.setName("Type B");
    ft2.setSortIndex(0);
    ft2 = frequencyTypeRepository.saveAndFlush(ft2);

    frequencyTypeService.reorderFrequencyTypes(List.of(ft2.getId(), ft1.getId()));

    frequencyTypeRepository.flush();

    FrequencyType updatedFt1 = frequencyTypeRepository.findById(ft1.getId()).orElseThrow();
    FrequencyType updatedFt2 = frequencyTypeRepository.findById(ft2.getId()).orElseThrow();

    assertEquals(1, updatedFt1.getSortIndex());
    assertEquals(0, updatedFt2.getSortIndex());
  }
}
