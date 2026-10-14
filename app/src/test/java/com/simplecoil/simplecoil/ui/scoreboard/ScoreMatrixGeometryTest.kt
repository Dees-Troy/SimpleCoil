/*
 * Copyright (C) 2026 Ethan Yonker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplecoil.simplecoil.ui.scoreboard

import com.simplecoil.simplecoil.ui.scoreboard.ScoreMatrixGeometry.Companion.HEADER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where ScoreMatrixView puts things, and how far it scrolls. */
class ScoreMatrixGeometryTest {

    // 32 players, 40 px cells, 100 px header bands, a 50 px totals column: 1430 x 1420 px of content
    private val g = ScoreMatrixGeometry(count = 32, cell = 40, rowHeaderWidth = 100, columnHeaderHeight = 100, totalWidth = 50)

    @Test
    fun contentSize_coversHeadersCellsAndTotals() {
        assertEquals(100 + 32 * 40 + 50, g.contentWidth)
        assertEquals(100 + 33 * 40, g.contentHeight)
    }

    @Test
    fun scrollRange_isTheContentPastTheViewport_neverNegative() {
        assertEquals(1430 - 400, g.maxScrollX(400))
        assertEquals(1420 - 600, g.maxScrollY(600))
        assertEquals(0, g.maxScrollX(2000))
        val small = ScoreMatrixGeometry(2, 40, 100, 100, 40)
        assertEquals(0, small.maxScrollY(600))
    }

    @Test
    fun hitTesting_followsTheScroll_andThePinnedHeaders() {
        assertEquals(HEADER, g.columnAt(99, 500)) // the row header band doesn't scroll
        assertEquals(0, g.columnAt(100, 0))
        assertEquals(12, g.columnAt(100, 480))
        assertEquals(32, g.columnAt(100 + 32 * 40 + 49, 0)) // the totals column
        assertNull(g.columnAt(100 + 32 * 40 + 50, 0))
        assertEquals(HEADER, g.rowAt(50, 300))
        assertEquals(7, g.rowAt(100, 280))
        assertEquals(32, g.rowAt(100 + 32 * 40, 0)) // the totals row
        assertNull(g.rowAt(100 + 33 * 40, 0))
        assertNull(g.rowAt(-1, 0))
    }

    @Test
    fun cellPositions_moveWithTheScroll() {
        assertEquals(100 + 3 * 40 - 50, g.columnLeft(3, 50))
        assertEquals(100 + 5 * 40 - 70, g.rowTop(5, 70))
        assertEquals(50, g.columnWidth(32))
        assertEquals(40, g.columnWidth(31))
    }

    @Test
    fun visibleRange_includesPartlyVisibleCells_andStopsAtTheTotals() {
        // 300 px wide: 200 px of cells from scroll 20 cover columns 0 (partly) to 5 (partly)
        assertEquals(0..5, g.visibleColumns(20, 300))
        assertEquals(0..4, g.visibleColumns(0, 300))
        assertEquals(25..32, g.visibleColumns(g.maxScrollX(400), 400))
        assertEquals(30..32, g.visibleRows(1200, 200))
    }

    @Test
    fun ownRow_isScrolledToTheTop_whenTheRangeAllows() {
        assertEquals(10 * 40, g.scrollYForRow(10, 600))
        assertEquals(g.maxScrollY(600), g.scrollYForRow(31, 600))
        assertEquals(0, g.scrollYForRow(0, 600))
    }
}
