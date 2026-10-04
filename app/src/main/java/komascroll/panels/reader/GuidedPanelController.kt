package komascroll.panels.reader

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.util.system.dpToPx
import komascroll.i18n.KSR
import komascroll.lab.LabPreferences
import komascroll.panels.PanelLayout
import komascroll.panels.PanelManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import uy.kohesive.injekt.injectLazy
import kotlin.math.abs

/**
 * Guided panel view for the paged readers: taps, swipes and keys step through the detected panels
 * of a page with an animated zoom, in the series' reading direction (right-to-left for the R2L
 * reader), and move to the next/previous page after the last/first panel. Pages whose panels
 * could not be detected confidently are shown in full and navigate as usual.
 */
class GuidedPanelController(private val viewer: PagerViewer) {

    private val preferences: LabPreferences by injectLazy()
    private val panelManager: PanelManager by injectLazy()

    private val scope = MainScope()
    private var loadJob: Job? = null

    private var page: ReaderPage? = null
    private var layout: PanelLayout? = null
    private var index = 0

    private val rightToLeft: Boolean get() = viewer is R2LPagerViewer

    /** Guided view is on and the current page layout allows it (no merged/split double pages). */
    val isEnabled: Boolean
        get() = preferences.guidedEnabled().get() &&
            preferences.guidedInReader().get() &&
            !viewer.config.doublePages &&
            !viewer.config.dualPageSplit &&
            !viewer.config.dualPageRotateToFit

    /** Panels are being navigated on the current page. */
    private val isEngaged: Boolean get() = isEnabled && layout?.isUsable == true

    fun attach() {
        updateGestures()
        combine(preferences.guidedEnabled().changes(), preferences.guidedInReader().changes()) { _, _ -> }
            .drop(1)
            .onEach {
                updateGestures()
                page?.let { onPageSelected(it, forward = true) }
            }
            .launchIn(scope)
    }

    fun destroy() {
        scope.cancel()
        viewer.pager.panelFlingListener = null
    }

    /** Called by the viewer when a new page becomes current. */
    fun onPageSelected(page: ReaderPage, forward: Boolean) {
        this.page = page
        layout = null
        index = 0
        loadJob?.cancel()
        viewer.pageHolderFor(page)?.let(::hideIndicator)
        if (!isEnabled || page is InsertPage) return

        loadJob = scope.launch {
            val result = panelManager.layoutFor(page, rightToLeft)
            if (this@GuidedPanelController.page !== page) return@launch
            layout = result
            index = if (forward || !result.isUsable) 0 else result.panels.lastIndex
            apply(animate = true)
        }
    }

    /** Called by a page holder whenever its image is (re)loaded, e.g. after upscaling. */
    fun onImageLoaded(holder: PagerPageHolder) {
        if (holder.page === page) apply(animate = false)
    }

    /**
     * Called before the viewer moves one item towards a higher ([towardsHigherIndex]) or lower
     * adapter position. Returns true when the move was handled by stepping to another panel.
     */
    fun onMove(towardsHigherIndex: Boolean): Boolean {
        val panels = layout?.panels
        if (!isEngaged || panels == null) return false
        // In the R2L reader, the next page sits at the lower adapter position.
        val forward = if (rightToLeft) !towardsHigherIndex else towardsHigherIndex
        val target = if (forward) index + 1 else index - 1
        if (target !in panels.indices) return false
        index = target
        apply(animate = true)
        return true
    }

    /** Whether vertical panning should be skipped in favour of panel navigation. */
    fun consumesPanning(): Boolean = isEngaged

    private fun updateGestures() {
        viewer.pager.panelFlingListener = if (isEnabled) ::onFling else null
    }

    private fun onFling(velocityX: Float, velocityY: Float): Boolean {
        val velocity = if (viewer is VerticalPagerViewer) velocityY else velocityX
        if (abs(velocity) < MIN_FLING_VELOCITY) return false
        // Swiping towards the start of the axis reveals the item at the higher adapter position.
        viewer.moveByItem(towardsHigherIndex = velocity < 0)
        return true
    }

    private fun apply(animate: Boolean) {
        val page = page ?: return
        val holder = viewer.pageHolderFor(page) ?: return
        val panels = layout?.panels
        if (!isEngaged || panels == null || holder.item.second != null) {
            hideIndicator(holder)
            return
        }
        val panel = panels[index].padded(PANEL_PADDING)
        if (holder.zoomToImageRegion(panel.left, panel.top, panel.right, panel.bottom, animate)) {
            showIndicator(holder, index + 1, panels.size)
        }
    }

    private fun showIndicator(holder: FrameLayout, position: Int, total: Int) {
        val indicator = holder.findViewWithTag<TextView>(INDICATOR_TAG) ?: createIndicator(holder)
        indicator.text = holder.context.stringResource(KSR.strings.guided_indicator, position, total)
        indicator.isVisible = true
        indicator.bringToFront()
    }

    private fun hideIndicator(holder: FrameLayout) {
        holder.findViewWithTag<TextView>(INDICATOR_TAG)?.isVisible = false
    }

    private fun createIndicator(holder: FrameLayout): TextView = TextView(holder.context).apply {
        tag = INDICATOR_TAG
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(Color.WHITE)
        val horizontal = 8.dpToPx
        val vertical = 3.dpToPx
        setPadding(horizontal, vertical, horizontal, vertical)
        background = GradientDrawable().apply {
            cornerRadius = 10.dpToPx.toFloat()
            setColor(Color.argb(160, 0, 0, 0))
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = 12.dpToPx }
        holder.addView(this, params)
    }

    private companion object {
        const val INDICATOR_TAG = "komascroll_guided_indicator"

        /** Breathing room around each panel, as a share of its size. */
        const val PANEL_PADDING = 0.03f

        const val MIN_FLING_VELOCITY = 600f
    }
}
