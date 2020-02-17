/*
 * Copyright (c) 2009 - 2019 Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.tally;

import static org.candlepin.subscriptions.tally.collector.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.candlepin.subscriptions.db.model.HardwareMeasurementType;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

public class ProductUsageCalculationTest {

    @Test
    public void testDefaults() {
        ProductUsageCalculation calculation = new ProductUsageCalculation("Test Product");
        assertEquals("Test Product", calculation.getProductId());

        for (HardwareMeasurementType type : HardwareMeasurementType.values()) {
            assertNull(calculation.getTotals(type), "Unexpected values for type: " + type);
        }
    }

    @Test
    public void testAddToTotal() {
        ProductUsageCalculation calculation = new ProductUsageCalculation("Product");
        IntStream.rangeClosed(0, 4).forEach(i -> calculation.addToTotal(i + 2, i + 1, i));

        assertHardwareMeasurementTotals(calculation, HardwareMeasurementType.TOTAL, 15, 20, 10);
        assertNullExcept(calculation, HardwareMeasurementType.TOTAL);
    }

    @Test
    public void testPhysicalSystemTotal() {
        ProductUsageCalculation calculation = new ProductUsageCalculation("Product");
        IntStream.rangeClosed(0, 4).forEach(i -> calculation.addPhysical(i + 2, i + 1, i));

        assertHardwareMeasurementTotals(calculation, HardwareMeasurementType.PHYSICAL, 15, 20, 10);
        assertHardwareMeasurementTotals(calculation, HardwareMeasurementType.TOTAL, 15, 20, 10);
        assertNullExcept(calculation, HardwareMeasurementType.TOTAL, HardwareMeasurementType.PHYSICAL);
    }


    @Test
    public void testHypervisorTotal() {
        ProductUsageCalculation calculation = new ProductUsageCalculation("Product");
        IntStream.rangeClosed(0, 4).forEach(i -> calculation.addHypervisor(i + 2, i + 1, i));

        assertHardwareMeasurementTotals(calculation, HardwareMeasurementType.HYPERVISOR, 15, 20, 10);
        assertHardwareMeasurementTotals(calculation, HardwareMeasurementType.TOTAL, 15, 20, 10);
        assertNullExcept(calculation, HardwareMeasurementType.TOTAL, HardwareMeasurementType.HYPERVISOR);
    }

    @Test
    public void testAWSTotal() {
        checkCloudProvider(HardwareMeasurementType.AWS);
    }

    @Test
    public void testAlibabaTotal() {
        checkCloudProvider(HardwareMeasurementType.ALIBABA);
    }

    @Test
    public void testGoogleTotal() {
        checkCloudProvider(HardwareMeasurementType.GOOGLE);
    }

    @Test
    public void testAzureTotal() {
        checkCloudProvider(HardwareMeasurementType.AZURE);
    }

    @Test
    public void invalidCloudTypeThrowsExcpection() {
        ProductUsageCalculation calculation = new ProductUsageCalculation("Product");
        assertThrows(IllegalArgumentException.class, () -> {
            calculation.addCloudProvider(HardwareMeasurementType.HYPERVISOR, 1, 1, 1);
        });
    }

    private void checkCloudProvider(HardwareMeasurementType providerType) {
        ProductUsageCalculation calculation = new ProductUsageCalculation("Product");
        IntStream.rangeClosed(0, 4).forEach(i -> calculation.addCloudProvider(
            providerType, i + 2, i + 1, i));

        assertHardwareMeasurementTotals(calculation, providerType, 15, 20, 10);
        assertHardwareMeasurementTotals(calculation, HardwareMeasurementType.TOTAL, 15, 20, 10);
        assertNullExcept(calculation, HardwareMeasurementType.TOTAL, providerType);
    }

}
