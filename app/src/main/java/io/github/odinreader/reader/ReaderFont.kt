package io.github.odinreader.reader

/** Stable preference IDs; labels may change without losing the user's choice. */
enum class ReaderFont(val id: String, val label: String, val description: String, internal val asset: String? = null) {
    SystemSerif("system_serif", "系统宋体", "跟随设备的衬线字体，保持原有阅读样式"),
    SystemSans("system_sans", "系统黑体", "跟随设备的无衬线字体，清晰简洁"),
    NotoSerif("noto_serif_sc", "思源宋体 · Noto Serif SC", "中文衬线字体，适合小说与长篇阅读", "noto_serif_sc_regular.otf"),
    NotoSans("noto_sans_sc", "思源黑体 · Noto Sans SC", "中文无衬线字体，笔画均匀清晰", "noto_sans_sc_regular.otf"),
    LxgwWenKai("lxgw_wenkai", "霞鹜文楷", "舒展的中文楷体，带有手写韵味", "lxgw_wenkai_regular.ttf"),
    Literata("literata", "Literata", "为电子阅读设计的英文衬线字体", "literata_regular.ttf"),
    JetBrainsMono("jetbrains_mono_nerd", "JetBrains Mono Nerd", "英文等宽字体，包含 Nerd 图标", "jetbrains_mono_nerd_regular.ttf"),
    ;

    companion object {
        val chineseChoices: List<ReaderFont> = listOf(SystemSerif, SystemSans, NotoSerif, NotoSans, LxgwWenKai)
        val englishChoices: List<ReaderFont> = entries
        fun fromId(id: String?): ReaderFont = entries.firstOrNull { it.id == id } ?: SystemSerif
    }
}

/** Each language has its own stable preference, with a compatible old single-font fallback. */
data class ReaderFontSelection(
    val chinese: ReaderFont = ReaderFont.SystemSerif,
    val english: ReaderFont = ReaderFont.SystemSerif,
) {
    companion object {
        fun fromPreferences(chineseId: String?, englishId: String?, legacyId: String?): ReaderFontSelection {
            val legacy = ReaderFont.fromId(legacyId)
            // English-only assets previously used Android's default sans Chinese fallback.
            val oldChinese = legacy.takeIf { it in ReaderFont.chineseChoices } ?: ReaderFont.SystemSans
            return ReaderFontSelection(
                chinese = ReaderFont.chineseChoices.firstOrNull { it.id == chineseId } ?: oldChinese,
                english = ReaderFont.englishChoices.firstOrNull { it.id == englishId } ?: legacy,
            )
        }
    }
}
