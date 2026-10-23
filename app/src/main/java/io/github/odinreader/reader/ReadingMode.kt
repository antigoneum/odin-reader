package io.github.odinreader.reader

enum class PageTurnAnimation(val id: String, val label: String) {
    None("none", "无动画"),
    Slide("slide", "横向滑动"),
    Fade("fade", "淡入淡出"),
    PageTurn("page_turn", "书页翻转"),
    ZoomSlide("zoom_slide", "移动并放大");

    companion object {
        fun fromPreference(value: String?, fallback: PageTurnAnimation): PageTurnAnimation =
            entries.firstOrNull { it.id == value } ?: fallback
    }
}

/** The step counts fully readable pages; a preview never advances the bookmark. */
enum class ReadingMode(
    val id: String,
    val label: String,
    val description: String,
    val pageStep: Int,
    val defaultAnimation: PageTurnAnimation,
) {
    Single("single", "单页", "一屏显示一页，适合原来的阅读习惯。", 1, PageTurnAnimation.Slide),
    DualColumn("dual_column", "双列", "同一纸面分成两栏，正文先左后右连续阅读。", 2, PageTurnAnimation.Slide),
    Preview("preview", "预览", "左侧完整阅读，右侧露出下一页的一半。", 1, PageTurnAnimation.Slide),
    ZoomPreview("zoom_preview", "缩放预览", "左侧完整阅读，右侧缩小预览；翻页时移到左侧放大。", 1, PageTurnAnimation.ZoomSlide);

    val hasPreview: Boolean get() = this == Preview || this == ZoomPreview
    val isSpread: Boolean get() = pageStep == 2

    /** Width of one complete sheet, before any sheet inset. All dimensions are pixels. */
    fun pageWidthPx(viewportWidth: Int, gutter: Int): Int {
        require(viewportWidth > 0)
        require(gutter >= 0)
        val usable = (viewportWidth - gutter).coerceAtLeast(1)
        return when (this) {
            Single -> viewportWidth
            DualColumn -> (usable / 2).coerceAtLeast(1)
            Preview, ZoomPreview -> (usable.toLong() * 2L / 3L).toInt().coerceAtLeast(1)
        }
    }

    fun visibleLastIndex(start: Int, pageCount: Int): Int {
        require(pageCount > 0)
        return (start.coerceIn(0, pageCount - 1).toLong() + pageStep - 1L)
            .coerceAtMost(pageCount - 1L).toInt()
    }

    /** Chapter boundaries start a new spread, with a blank right sheet for odd totals. */
    fun finalSpreadStart(pageCount: Int): Int {
        require(pageCount > 0)
        return ((pageCount - 1) / pageStep) * pageStep
    }

    /** A reflow can restore the second leaf of a spread; going back must still visit leaf zero. */
    fun previousStart(current: Int): Int {
        require(current >= 0)
        return if (current == 0) -1 else (current - pageStep).coerceAtLeast(0)
    }

    /** Keep an older dual-page choice when its layout is replaced by dual columns. */
    fun animationFromPreferences(
        value: String?,
        legacyDualPageValue: String?,
        legacyMode: String?,
    ): PageTurnAnimation {
        val fallback = if (this == DualColumn) {
            val previousDefault = if (legacyMode == "dual_page") PageTurnAnimation.PageTurn else defaultAnimation
            PageTurnAnimation.fromPreference(legacyDualPageValue, previousDefault)
        } else defaultAnimation
        return PageTurnAnimation.fromPreference(value, fallback)
    }

    companion object {
        fun fromPreference(value: String?): ReadingMode = when (value) {
            "dual_page" -> DualColumn
            else -> entries.firstOrNull { it.id == value } ?: Single
        }
    }
}
