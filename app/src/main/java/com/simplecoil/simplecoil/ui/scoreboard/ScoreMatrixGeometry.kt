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

/**
 * Where everything sits in [ScoreMatrixView] (score matrix design §6.1), in pixels: a pinned row
 * header band on the left and column header band on top, [count] square cells per axis, then a
 * totals column and a totals row. The scroll offsets move only the cells (and each header band
 * along its own axis). Pure, so it is tested on the JVM.
 */
internal class ScoreMatrixGeometry(
    val count: Int,
    val cell: Int,
    val rowHeaderWidth: Int,
    val columnHeaderHeight: Int,
    val totalWidth: Int,
) {
    val contentWidth: Int get() = rowHeaderWidth + count * cell + totalWidth
    val contentHeight: Int get() = columnHeaderHeight + (count + 1) * cell

    fun maxScrollX(viewWidth: Int): Int = maxOf(0, contentWidth - viewWidth)

    fun maxScrollY(viewHeight: Int): Int = maxOf(0, contentHeight - viewHeight)

    /** Left edge of column [col] ([count] is the totals column). */
    fun columnLeft(col: Int, scrollX: Int): Int = rowHeaderWidth + col * cell - scrollX

    fun columnWidth(col: Int): Int = if (col == count) totalWidth else cell

    /** Top edge of row [row] ([count] is the totals row). */
    fun rowTop(row: Int, scrollY: Int): Int = columnHeaderHeight + row * cell - scrollY

    /** The column under [x]: [HEADER] over the row header band, [count] for the totals, null past the end. */
    fun columnAt(x: Int, scrollX: Int): Int? {
        if (x < 0) return null
        if (x < rowHeaderWidth) return HEADER
        val offset = x - rowHeaderWidth + scrollX
        val col = offset / cell
        return when {
            col < count -> col
            offset < count * cell + totalWidth -> count
            else -> null
        }
    }

    /** The row under [y]: [HEADER] over the column header band, [count] for the totals, null past the end. */
    fun rowAt(y: Int, scrollY: Int): Int? {
        if (y < 0) return null
        if (y < columnHeaderHeight) return HEADER
        val row = (y - columnHeaderHeight + scrollY) / cell
        return if (row <= count) row else null
    }

    /** The columns (totals included) at least partly visible in a view [viewWidth] wide. */
    fun visibleColumns(scrollX: Int, viewWidth: Int): IntRange {
        val first = (scrollX / cell).coerceIn(0, count)
        val last = ((scrollX + viewWidth - rowHeaderWidth - 1) / cell).coerceIn(first, count)
        return first..last
    }

    /** The rows (totals included) at least partly visible in a view [viewHeight] high. */
    fun visibleRows(scrollY: Int, viewHeight: Int): IntRange {
        val first = (scrollY / cell).coerceIn(0, count)
        val last = ((scrollY + viewHeight - columnHeaderHeight - 1) / cell).coerceIn(first, count)
        return first..last
    }

    /** The vertical scroll that puts [row] at the top of the cells, as far as the range allows. */
    fun scrollYForRow(row: Int, viewHeight: Int): Int = (row * cell).coerceIn(0, maxScrollY(viewHeight))

    companion object {
        const val HEADER = -1
    }
}
