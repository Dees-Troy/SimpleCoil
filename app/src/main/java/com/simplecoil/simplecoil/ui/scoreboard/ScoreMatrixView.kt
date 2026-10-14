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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcelable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EdgeEffect
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.domain.team.TeamColors
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * The score matrix grid (score matrix design §6): one view that draws every cell on a Canvas and
 * scrolls on both axes at once. The name column and the name row stay pinned; the totals scroll
 * with the cells. Column names are drawn rotated, reading bottom to top.
 */
class ScoreMatrixView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var model: ScoreMatrixModel? = null
    private var geometry: ScoreMatrixGeometry? = null
    /** Names ellipsized to the header bands, measured once per data change. */
    private var shortNames: List<String> = emptyList()

    private var scrollXPos = 0
    private var scrollYPos = 0
    /** A scroll position restored from saved state, applied once there is data and a size. */
    private var restoredScroll: IntArray? = null
    /** The first data hasn't been positioned yet (own row into view, Q5). */
    private var needsInitialScroll = true

    /** The tallest the view makes itself; the dialog sets it from the window height. */
    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private val res = context.resources
    private val cellSize = res.getDimensionPixelSize(R.dimen.score_matrix_cell)
    private val headerMax = res.getDimensionPixelSize(R.dimen.score_matrix_header_max)
    private val padding = res.getDimensionPixelSize(R.dimen.score_matrix_header_padding)
    private val teamBar = res.getDimensionPixelSize(R.dimen.score_matrix_team_bar)
    private val separator = res.getDimension(R.dimen.score_matrix_separator)
    private val headerBackground = ContextCompat.getColor(context, R.color.score_matrix_header_background)
    private val diagonalShade = ContextCompat.getColor(context, R.color.score_matrix_diagonal)
    private val gridColor = ContextCompat.getColor(context, R.color.score_matrix_grid)
    private val separatorColor = ContextCompat.getColor(context, R.color.score_matrix_separator)
    private val totalLabel = res.getString(R.string.score_matrix_total)
    /** Counts in the locale's digits, as the scoreboard list shows them. */
    private val numbers = NumberFormat.getIntegerInstance()

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = res.getDimension(R.dimen.score_matrix_text)
        textAlign = Paint.Align.CENTER
    }
    private val totalPaint = Paint(cellPaint).apply { typeface = Typeface.DEFAULT_BOLD }
    private val totalLabelPaint = Paint(totalPaint).apply { textAlign = Paint.Align.LEFT }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = res.getDimension(R.dimen.score_matrix_header_text)
    }
    private val fillPaint = Paint()
    private val linePaint = Paint().apply { strokeWidth = 0f }
    /** Baseline offset that centers a line of [cellPaint] (and [namePaint]) text on a point. */
    private val cellTextCenter = -(cellPaint.fontMetrics.ascent + cellPaint.fontMetrics.descent) / 2
    private val nameTextCenter = -(namePaint.fontMetrics.ascent + namePaint.fontMetrics.descent) / 2

    private val scroller = OverScroller(context)
    private val edgeLeft = EdgeEffect(context)
    private val edgeTop = EdgeEffect(context)
    private val edgeRight = EdgeEffect(context)
    private val edgeBottom = EdgeEffect(context)

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            dragBy(distanceX.roundToInt(), distanceY.roundToInt(), e2)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            scroller.forceFinished(true)
            scroller.fling(scrollXPos, scrollYPos, -velocityX.roundToInt(), -velocityY.roundToInt(),
                0, maxScrollX(), 0, maxScrollY())
            postInvalidateOnAnimation()
            return true
        }
    })

    private val accessibility = Accessibility()

    init {
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, accessibility)
    }

    // ---------------------------------------------------------------------------------------
    // Data
    // ---------------------------------------------------------------------------------------

    /**
     * Shows [matrix]. Keeps the scroll position on the same players when they are still there,
     * and lays out again only when the grid's size changed (§6.3).
     */
    fun setMatrix(matrix: ScoreMatrixModel?) {
        val oldModel = model
        val oldGeometry = geometry
        model = matrix
        if (matrix == null) {
            geometry = null
            shortNames = emptyList()
        } else {
            val geometry = measureGeometry(matrix)
            this.geometry = geometry
            if (oldModel != null && oldGeometry != null && !needsInitialScroll) {
                scrollXPos = anchoredScroll(scrollXPos, oldModel, matrix, oldGeometry.cell)
                scrollYPos = anchoredScroll(scrollYPos, oldModel, matrix, oldGeometry.cell)
            }
        }
        val sizeChanged = oldGeometry?.contentWidth != geometry?.contentWidth || oldGeometry?.contentHeight != geometry?.contentHeight
        clampScroll()
        applyPendingScroll()
        accessibility.invalidateRoot()
        if (sizeChanged) requestLayout()
        invalidate()
    }

    /** [scroll] moved so the player at its leading row (or column) keeps that place in [new]. */
    private fun anchoredScroll(scroll: Int, old: ScoreMatrixModel, new: ScoreMatrixModel, cell: Int): Int {
        val first = scroll / cell
        val playerId = old.players.getOrNull(first)?.playerId ?: return scroll
        val index = new.players.indexOfFirst { it.playerId == playerId }
        return if (index < 0) scroll else index * cell + (scroll - first * cell)
    }

    private fun measureGeometry(matrix: ScoreMatrixModel): ScoreMatrixGeometry {
        val bar = if (matrix.teams) teamBar + padding / 2 else 0
        val widestName = matrix.players.maxOfOrNull { namePaint.measureText(it.name) } ?: 0f
        val nameRoom = (headerMax - 2 * padding - bar).toFloat()
        shortNames = matrix.players.map { TextUtils.ellipsize(it.name, namePaint, nameRoom, TextUtils.TruncateAt.END).toString() }
        val header = (widestName.roundToInt() + 2 * padding + bar).coerceIn(cellSize, headerMax)
        val widestTotal = totalPaint.measureText(numbers.format(matrix.total)).roundToInt() + 2 * padding
        return ScoreMatrixGeometry(matrix.size, cellSize, header, header, maxOf(cellSize, widestTotal))
    }

    // ---------------------------------------------------------------------------------------
    // Size and scrolling
    // ---------------------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val g = geometry
        val width = resolveSize(g?.contentWidth ?: 0, widthMeasureSpec)
        val height = resolveSize(minOf(g?.contentHeight ?: 0, maxHeightPx), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampScroll()
        applyPendingScroll()
    }

    private fun maxScrollX() = geometry?.maxScrollX(width) ?: 0

    private fun maxScrollY() = geometry?.maxScrollY(height) ?: 0

    private fun clampScroll() {
        if (width == 0 || height == 0) return
        scrollXPos = scrollXPos.coerceIn(0, maxScrollX())
        scrollYPos = scrollYPos.coerceIn(0, maxScrollY())
    }

    /** A restored position, or else this phone's own row for the first data, once both data and a size exist. */
    private fun applyPendingScroll() {
        val g = geometry ?: return
        if (width == 0 || height == 0) return
        val restored = restoredScroll
        if (restored != null) {
            restoredScroll = null
            needsInitialScroll = false
            scrollTo(restored[0], restored[1])
        } else if (needsInitialScroll) {
            needsInitialScroll = false
            val self = model?.selfIndex ?: -1
            if (self >= 0) scrollTo(scrollXPos, g.scrollYForRow(self, height))
        }
    }

    override fun scrollTo(x: Int, y: Int) {
        val newX = if (width == 0) x else x.coerceIn(0, maxScrollX())
        val newY = if (height == 0) y else y.coerceIn(0, maxScrollY())
        if (newX == scrollXPos && newY == scrollYPos) return
        scrollXPos = newX
        scrollYPos = newY
        accessibility.invalidateRoot()
        if (!awakenScrollBars()) invalidate()
    }

    override fun scrollBy(x: Int, y: Int) = scrollTo(scrollXPos + x, scrollYPos + y)

    /** A drag of ([dx], [dy]); past an end the edge glow is pulled instead. */
    private fun dragBy(dx: Int, dy: Int, event: MotionEvent) {
        val maxX = maxScrollX()
        val maxY = maxScrollY()
        val x = scrollXPos + dx
        val y = scrollYPos + dy
        if (maxX > 0) {
            if (x < 0) edgeLeft.onPull(-x.toFloat() / width, 1f - event.y / height)
            else if (x > maxX) edgeRight.onPull((x - maxX).toFloat() / width, event.y / height)
        }
        if (maxY > 0) {
            if (y < 0) edgeTop.onPull(-y.toFloat() / height, event.x / width)
            else if (y > maxY) edgeBottom.onPull((y - maxY).toFloat() / height, 1f - event.x / width)
        }
        scrollTo(x, y)
        if (!edgesFinished()) postInvalidateOnAnimation()
    }

    private fun edgesFinished() = edgeLeft.isFinished && edgeTop.isFinished && edgeRight.isFinished && edgeBottom.isFinished

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val x = scroller.currX
        val y = scroller.currY
        val velocity = scroller.currVelocity.roundToInt()
        if (maxScrollX() > 0) {
            if (x <= 0 && scrollXPos > 0) edgeLeft.onAbsorb(velocity)
            else if (x >= maxScrollX() && scrollXPos < maxScrollX()) edgeRight.onAbsorb(velocity)
        }
        if (maxScrollY() > 0) {
            if (y <= 0 && scrollYPos > 0) edgeTop.onAbsorb(velocity)
            else if (y >= maxScrollY() && scrollYPos < maxScrollY()) edgeBottom.onAbsorb(velocity)
        }
        scrollTo(x, y)
        postInvalidateOnAnimation()
    }

    @Suppress("ClickableViewAccessibility") // taps go through performClick() in the gesture listener
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (geometry == null) return super.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            edgeLeft.onRelease()
            edgeTop.onRelease()
            edgeRight.onRelease()
            edgeBottom.onRelease()
            if (!edgesFinished()) postInvalidateOnAnimation()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun computeHorizontalScrollRange(): Int = geometry?.contentWidth ?: 0
    override fun computeHorizontalScrollExtent(): Int = width
    override fun computeHorizontalScrollOffset(): Int = scrollXPos
    override fun computeVerticalScrollRange(): Int = geometry?.contentHeight ?: 0
    override fun computeVerticalScrollExtent(): Int = height
    override fun computeVerticalScrollOffset(): Int = scrollYPos

    override fun canScrollHorizontally(direction: Int): Boolean =
        if (direction < 0) scrollXPos > 0 else scrollXPos < maxScrollX()

    override fun canScrollVertically(direction: Int): Boolean =
        if (direction < 0) scrollYPos > 0 else scrollYPos < maxScrollY()

    // ---------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        val g = geometry ?: return
        val rows = g.visibleRows(scrollYPos, height)
        val cols = g.visibleColumns(scrollXPos, width)
        drawCells(canvas, m, g, rows, cols)
        drawRowHeaders(canvas, m, g, rows)
        drawColumnHeaders(canvas, m, g, cols)
        fill(canvas, 0, 0, g.rowHeaderWidth, g.columnHeaderHeight, headerBackground)
        line(canvas, 0f, g.columnHeaderHeight.toFloat(), g.rowHeaderWidth.toFloat(), g.columnHeaderHeight.toFloat(), separatorColor)
        line(canvas, g.rowHeaderWidth.toFloat(), 0f, g.rowHeaderWidth.toFloat(), g.columnHeaderHeight.toFloat(), separatorColor)
        drawEdges(canvas, g)
    }

    private fun drawCells(canvas: Canvas, m: ScoreMatrixModel, g: ScoreMatrixGeometry, rows: IntRange, cols: IntRange) =
        canvas.withClip(g.rowHeaderWidth, g.columnHeaderHeight, width, height) {
            val n = m.size
            for (row in rows) {
                val top = g.rowTop(row, scrollYPos)
                for (col in cols) {
                    val left = g.columnLeft(col, scrollXPos)
                    val right = left + g.columnWidth(col)
                    if (row < n && col < n) {
                        if (m.teams && m.players[row].team == m.players[col].team) {
                            fill(this, left, top, right, top + g.cell, tint(m.players[row].team))
                        }
                        if (row == col) fill(this, left, top, right, top + g.cell, diagonalShade)
                    }
                    val value = when {
                        row < n && col < n -> m.counts[row][col]
                        row < n -> m.rowTotals[row]
                        col < n -> m.columnTotals[col]
                        else -> m.total
                    }
                    // Zero is left blank, except in the totals
                    if (value != 0 || row == n || col == n) {
                        val paint = if (row == n || col == n) totalPaint else cellPaint
                        drawText(numbers.format(value), (left + right) / 2f, top + g.cell / 2f + cellTextCenter, paint)
                    }
                }
            }
            // Grid lines, then the separators in front of the totals
            val gridLeft = g.columnLeft(cols.first, scrollXPos).toFloat()
            val gridRight = (g.columnLeft(cols.last, scrollXPos) + g.columnWidth(cols.last)).toFloat()
            val gridTop = g.rowTop(rows.first, scrollYPos).toFloat()
            val gridBottom = (g.rowTop(rows.last, scrollYPos) + g.cell).toFloat()
            for (row in rows) {
                val y = (g.rowTop(row, scrollYPos) + g.cell).toFloat()
                line(this, gridLeft, y, gridRight, y, gridColor)
            }
            for (col in cols) {
                val x = (g.columnLeft(col, scrollXPos) + g.columnWidth(col)).toFloat()
                line(this, x, gridTop, x, gridBottom, gridColor)
            }
            linePaint.strokeWidth = separator
            val totalsX = g.columnLeft(n, scrollXPos).toFloat()
            val totalsY = g.rowTop(n, scrollYPos).toFloat()
            line(this, totalsX, gridTop, totalsX, gridBottom, separatorColor)
            line(this, gridLeft, totalsY, gridRight, totalsY, separatorColor)
            linePaint.strokeWidth = 0f
        }

    private fun drawRowHeaders(canvas: Canvas, m: ScoreMatrixModel, g: ScoreMatrixGeometry, rows: IntRange) =
        canvas.withClip(0, g.columnHeaderHeight, g.rowHeaderWidth, height) {
            val n = m.size
            fill(this, 0, g.columnHeaderHeight, g.rowHeaderWidth, height, headerBackground)
            for (row in rows) {
                val top = g.rowTop(row, scrollYPos)
                val centerY = top + g.cell / 2f
                if (row == n) {
                    drawText(totalLabel, padding.toFloat(), centerY + cellTextCenter, totalLabelPaint)
                } else {
                    stylePaintFor(m.players[row])
                    drawText(shortNames[row], padding.toFloat(), centerY + nameTextCenter, namePaint)
                    if (m.teams) {
                        val inset = g.cell / 5
                        fill(this, g.rowHeaderWidth - teamBar, top + inset, g.rowHeaderWidth, top + g.cell - inset,
                            TeamColors.colorOf(m.players[row].team))
                    }
                }
                line(this, 0f, (top + g.cell).toFloat(), g.rowHeaderWidth.toFloat(), (top + g.cell).toFloat(), gridColor)
            }
            line(this, g.rowHeaderWidth.toFloat(), g.columnHeaderHeight.toFloat(), g.rowHeaderWidth.toFloat(), height.toFloat(),
                separatorColor)
        }

    private fun drawColumnHeaders(canvas: Canvas, m: ScoreMatrixModel, g: ScoreMatrixGeometry, cols: IntRange) =
        canvas.withClip(g.rowHeaderWidth, 0, width, g.columnHeaderHeight) {
            val n = m.size
            fill(this, g.rowHeaderWidth, 0, width, g.columnHeaderHeight, headerBackground)
            val barRoom = if (m.teams) teamBar + padding / 2 else 0
            for (col in cols) {
                val left = g.columnLeft(col, scrollXPos)
                val centerX = left + g.columnWidth(col) / 2f
                if (col == n) {
                    drawText(totalLabel, centerX, g.columnHeaderHeight - g.cell / 2f + cellTextCenter, totalPaint)
                } else {
                    stylePaintFor(m.players[col])
                    // Rotated about the column's center: the name reads bottom to top, ending away from the grid
                    withRotation(-90f, centerX, (g.columnHeaderHeight - padding - barRoom).toFloat()) {
                        drawText(shortNames[col], centerX, g.columnHeaderHeight - padding - barRoom + nameTextCenter, namePaint)
                    }
                    if (m.teams) {
                        val inset = g.cell / 5
                        fill(this, left + inset, g.columnHeaderHeight - teamBar, left + g.cell - inset, g.columnHeaderHeight,
                            TeamColors.colorOf(m.players[col].team))
                    }
                }
                val x = (left + g.columnWidth(col)).toFloat()
                line(this, x, 0f, x, g.columnHeaderHeight.toFloat(), gridColor)
            }
            line(this, g.rowHeaderWidth.toFloat(), g.columnHeaderHeight.toFloat(), width.toFloat(), g.columnHeaderHeight.toFloat(),
                separatorColor)
        }

    /** Own name in the scoreboard's self colour, disconnected players faded, as on the list. */
    private fun stylePaintFor(entry: ScoreMatrixModel.Entry) {
        namePaint.color = if (entry.isSelf) ScoreboardColors.SELF_NAME else Color.WHITE
        if (!entry.connected) namePaint.alpha = DISCONNECTED_ALPHA
    }

    /** The four glows, each along an edge of the cells area, rotated so it pulls inward. */
    private fun drawEdges(canvas: Canvas, g: ScoreMatrixGeometry) {
        if (edgesFinished()) return
        val cellsWidth = width - g.rowHeaderWidth
        val cellsHeight = height - g.columnHeaderHeight
        var more = false
        if (!edgeTop.isFinished) {
            canvas.withTranslation(g.rowHeaderWidth.toFloat(), g.columnHeaderHeight.toFloat()) {
                edgeTop.setSize(cellsWidth, cellsHeight)
                more = edgeTop.draw(this) or more
            }
        }
        if (!edgeBottom.isFinished) {
            canvas.withRotation(180f, width / 2f, height / 2f) {
                // Rotated about the view's center, the bottom edge is at the top
                edgeBottom.setSize(cellsWidth, cellsHeight)
                more = edgeBottom.draw(this) or more
            }
        }
        if (!edgeLeft.isFinished) {
            canvas.withTranslation(g.rowHeaderWidth.toFloat(), height.toFloat()) {
                rotate(270f)
                edgeLeft.setSize(cellsHeight, cellsWidth)
                more = edgeLeft.draw(this) or more
            }
        }
        if (!edgeRight.isFinished) {
            canvas.withTranslation(width.toFloat(), g.columnHeaderHeight.toFloat()) {
                rotate(90f)
                edgeRight.setSize(cellsHeight, cellsWidth)
                more = edgeRight.draw(this) or more
            }
        }
        if (more) postInvalidateOnAnimation()
    }

    private fun fill(canvas: Canvas, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        fillPaint.color = color
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), fillPaint)
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        linePaint.color = color
        canvas.drawLine(x1, y1, x2, y2, linePaint)
    }

    private fun tint(team: Int): Int = (TeamColors.colorOf(team) and 0x00FFFFFF) or (TEAM_TINT_ALPHA shl 24)

    // ---------------------------------------------------------------------------------------
    // Saved state
    // ---------------------------------------------------------------------------------------

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable(KEY_SUPER, super.onSaveInstanceState())
        putIntArray(KEY_SCROLL, restoredScroll ?: intArrayOf(scrollXPos, scrollYPos))
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) return super.onRestoreInstanceState(state)
        super.onRestoreInstanceState(BundleCompat.getParcelable(state, KEY_SUPER, Parcelable::class.java))
        restoredScroll = state.getIntArray(KEY_SCROLL)
        needsInitialScroll = false
        applyPendingScroll()
    }

    // ---------------------------------------------------------------------------------------
    // Accessibility (§6.4): every visible cell and header is a virtual view
    // ---------------------------------------------------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private inner class Accessibility : ExploreByTouchHelper(this@ScoreMatrixView) {
        private val bounds = Rect()

        /** Rows and columns per axis, totals included. */
        private val span get() = (model?.size ?: 0) + 1
        private fun cellId(row: Int, col: Int) = row * span + col
        private fun rowHeaderId(row: Int) = span * span + row
        private fun columnHeaderId(col: Int) = span * span + span + col

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val g = geometry ?: return INVALID_ID
            val row = g.rowAt(y.toInt(), scrollYPos) ?: return INVALID_ID
            val col = g.columnAt(x.toInt(), scrollXPos) ?: return INVALID_ID
            return when {
                row == ScoreMatrixGeometry.HEADER && col == ScoreMatrixGeometry.HEADER -> INVALID_ID
                row == ScoreMatrixGeometry.HEADER -> columnHeaderId(col)
                col == ScoreMatrixGeometry.HEADER -> rowHeaderId(row)
                else -> cellId(row, col)
            }
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            val g = geometry ?: return
            val rows = g.visibleRows(scrollYPos, height)
            val cols = g.visibleColumns(scrollXPos, width)
            for (col in cols) virtualViewIds += columnHeaderId(col)
            for (row in rows) {
                virtualViewIds += rowHeaderId(row)
                for (col in cols) virtualViewIds += cellId(row, col)
            }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val m = model
            val g = geometry
            if (m == null || g == null) {
                node.contentDescription = ""
                @Suppress("DEPRECATION") node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            val n = m.size
            val cells = span * span
            when {
                virtualViewId < cells -> {
                    val row = virtualViewId / span
                    val col = virtualViewId % span
                    node.contentDescription = describeCell(m, row, col)
                    val left = g.columnLeft(col, scrollXPos)
                    val top = g.rowTop(row, scrollYPos)
                    bounds.set(left, top, left + g.columnWidth(col), top + g.cell)
                    if (!bounds.intersect(g.rowHeaderWidth, g.columnHeaderHeight, width, height)) bounds.setEmpty()
                }
                virtualViewId < cells + span -> {
                    val row = virtualViewId - cells
                    node.contentDescription = if (row == n) res.getString(R.string.score_matrix_total_row_description)
                        else describeCell(m, row, n)
                    val top = g.rowTop(row, scrollYPos)
                    bounds.set(0, top, g.rowHeaderWidth, top + g.cell)
                    if (!bounds.intersect(0, g.columnHeaderHeight, g.rowHeaderWidth, height)) bounds.setEmpty()
                }
                else -> {
                    val col = virtualViewId - cells - span
                    node.contentDescription = if (col == n) res.getString(R.string.score_matrix_total_column_description)
                        else describeCell(m, n, col)
                    val left = g.columnLeft(col, scrollXPos)
                    bounds.set(left, 0, left + g.columnWidth(col), g.columnHeaderHeight)
                    if (!bounds.intersect(g.rowHeaderWidth, 0, width, g.columnHeaderHeight)) bounds.setEmpty()
                }
            }
            if (bounds.isEmpty) bounds.set(0, 0, 1, 1)
            @Suppress("DEPRECATION") node.setBoundsInParent(bounds)
        }

        private fun describeCell(m: ScoreMatrixModel, row: Int, col: Int): String {
            val n = m.size
            return when {
                row < n && col < n -> res.getQuantityString(R.plurals.score_matrix_cell_description, m.counts[row][col],
                    m.players[row].name, m.players[col].name, m.counts[row][col])
                row < n -> res.getQuantityString(R.plurals.score_matrix_row_total_description, m.rowTotals[row],
                    m.players[row].name, m.rowTotals[row])
                col < n -> res.getQuantityString(R.plurals.score_matrix_column_total_description, m.columnTotals[col],
                    m.players[col].name, m.columnTotals[col])
                else -> res.getQuantityString(R.plurals.score_matrix_grand_total_description, m.total, m.total)
            }
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean = false
    }

    private companion object {
        const val KEY_SUPER = "super"
        const val KEY_SCROLL = "scroll"
        const val DISCONNECTED_ALPHA = 0x80
        const val TEAM_TINT_ALPHA = 0x2E
    }
}
