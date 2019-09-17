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
package org.candlepin.subscriptions.tally.filler;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.candlepin.subscriptions.FixedClockConfiguration;
import org.candlepin.subscriptions.db.model.TallyGranularity;
import org.candlepin.subscriptions.util.ApplicationClock;

import org.junit.jupiter.api.Test;

public class ReportFillerFactoryTest {

    private ApplicationClock clock;

    public ReportFillerFactoryTest() {
        clock = new FixedClockConfiguration().fixedClock();
    }

    @Test
    public void testDailyReportFillerCreation() {
        ReportFiller filler = ReportFillerFactory.getInstance(clock, TallyGranularity.DAILY);
        assertTrue(filler instanceof DailyReportFiller);
    }

    @Test
    public void testWeeklyReportFillerCreation() {
        ReportFiller filler = ReportFillerFactory.getInstance(clock, TallyGranularity.WEEKLY);
        assertTrue(filler instanceof WeeklyReportFiller);
    }

    @Test
    public void testMonthlyReportFillerCreation() {
        ReportFiller filler = ReportFillerFactory.getInstance(clock, TallyGranularity.MONTHLY);
        assertTrue(filler instanceof MonthlyReportFiller);
    }

    @Test
    public void testYearlyReportFillerCreation() {
        ReportFiller filler = ReportFillerFactory.getInstance(clock, TallyGranularity.YEARLY);
        assertTrue(filler instanceof YearlyReportFiller);
    }

    @Test
    public void testQuarterlyReportFillerCreation() {
        ReportFiller filler = ReportFillerFactory.getInstance(clock, TallyGranularity.QUARTERLY);
        assertTrue(filler instanceof QuarterlyReportFiller);
    }

}
