package com.rishabh.clockdown

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.ColorUtils

/**
 * How a widget looks. One mechanism for everything: your own timers, class timers and all three widgets resolve their
 * appearance through [WidgetStyle] and [Look], so there is no separate code path for classes.
 */
enum class WidgetStyle(val id: String, val label: String, val blurb: String) {
    CARD("card", "Card", "A solid coloured card"),
    GRADIENT("gradient", "Gradient", "A card that fades into a deeper shade"),
    BOLD("bold", "Bold", "Huge numbers on solid black"),
    GLASS("glass", "Glass", "No background. White text on dark wallpapers, dark text on light ones"),
    MONO("mono", "Mono", "No background, thin single-colour type that adapts to your wallpaper"),
    PAPER("paper", "Paper", "A warm paper card with dark text"),
    OUTLINE("outline", "Outline", "A see-through card with a coloured edge");

    companion object {
        fun of(id: String?): WidgetStyle? = entries.firstOrNull { it.id == id }
    }
}

/** The text treatments a layout can have. RemoteViews can't change fonts or shadows, so each is its own layout. */
enum class Variant { PLAIN, GLASS, LIGHTGLASS, THIN, THINLIGHT, HEAVY }

enum class Kind { TIMER, CLASSES, LIST }

/** What a widget needs to draw itself in a style: colours, which layout, and which backgrounds. All resolved once. */
class Look(
    val style: WidgetStyle,
    val variant: Variant,
    /** Whole-widget background drawable for single-card widgets; 0 = none. */
    val cardBg: Int,
    /** Background of the outer container of the multi-row widgets; 0 = none. */
    val containerBg: Int,
    /** Background drawable for one row/inner card of the accent colour [index]; 0 = none. */
    val rowBg: (Int) -> Int,
    val fg: Int,
    val soft: Int,
    /** Text colour for anything sitting on the outer container (headers, rows of a list) rather than on a card. */
    val containerFg: Int,
    /** True = the emoji badge is drawn light (for dark backgrounds/text); false = dark. */
    val lightBadge: Boolean,
    val numeralScale: Float,
)

/** Android's own judgement of whether the home wallpaper is light (so a see-through widget needs dark text). */
object Wallpaper {
    fun isLight(ctx: Context): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 31) return false // older Android does not say; white text with a shadow is the safer guess
        val colors = android.app.WallpaperManager.getInstance(ctx).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
        colors != null && colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    }.getOrDefault(false)
}

object Looks {
    private const val INK = 0xFF15151B.toInt()
    private const val PAPER_INK = 0xFF2A2620.toInt()

    private fun soft(fg: Int) = ColorUtils.setAlphaComponent(fg, 0xC8)

    /**
     * [lightWallpaper]: whether the wallpaper behind a see-through style is light, so dark text is the readable choice.
     * Real widgets ask Android about the home wallpaper; previews pass the pretend wallpaper they are drawn on.
     */
    fun of(ctx: Context, style: WidgetStyle, accent: Int, lightWallpaper: Boolean = Wallpaper.isLight(ctx)): Look {
        val card = cardColor(ctx, accent)
        return when (style) {
            WidgetStyle.CARD -> {
                val fg = onColor(card)
                Look(style, Variant.PLAIN, WIDGET_BGS[accent], R.drawable.widget_bg, { WIDGET_BGS[it] }, fg, soft(fg), Color.WHITE, fg == Color.WHITE, 1f)
            }
            WidgetStyle.GRADIENT -> {
                val fg = onColor(card) // the gradient fades toward a deeper (light text) or paler (dark text) shade, so this reads across all of it
                Look(style, Variant.PLAIN, WIDGET_GRADS[accent], R.drawable.widget_bg, { WIDGET_GRADS[it] }, fg, soft(fg), Color.WHITE, fg == Color.WHITE, 1f)
            }
            WidgetStyle.BOLD ->
                Look(style, Variant.HEAVY, R.drawable.widget_black, R.drawable.widget_black, { R.drawable.widget_black_row }, Color.WHITE, soft(Color.WHITE), Color.WHITE, true, 1.35f)
            WidgetStyle.GLASS ->
                if (lightWallpaper) Look(style, Variant.LIGHTGLASS, 0, 0, { 0 }, INK, soft(INK), INK, false, 1f)
                else Look(style, Variant.GLASS, 0, 0, { 0 }, Color.WHITE, soft(Color.WHITE), Color.WHITE, true, 1f)
            WidgetStyle.MONO ->
                if (lightWallpaper) Look(style, Variant.THINLIGHT, 0, 0, { 0 }, INK, soft(INK), INK, false, 1.1f)
                else Look(style, Variant.THIN, 0, 0, { 0 }, Color.WHITE, soft(Color.WHITE), Color.WHITE, true, 1.1f)
            WidgetStyle.PAPER ->
                Look(style, Variant.PLAIN, R.drawable.widget_paper, R.drawable.widget_paper, { R.drawable.widget_paper_row }, PAPER_INK, soft(PAPER_INK), PAPER_INK, false, 1f)
            WidgetStyle.OUTLINE ->
                Look(style, Variant.GLASS, WIDGET_OUTLINES[accent], 0, { WIDGET_OUTLINES[it] }, Color.WHITE, soft(Color.WHITE), Color.WHITE, true, 1f)
        }
    }

    /** Text colour for one row of a list widget: Card and Gradient rows have their own colours, the rest share the container's. */
    fun rowFg(ctx: Context, style: WidgetStyle, accent: Int, lightWallpaper: Boolean): Int = when (style) {
        WidgetStyle.CARD -> onColor(cardColor(ctx, accent))
        WidgetStyle.GRADIENT -> onColor(cardColor(ctx, accent))
        else -> of(ctx, style, accent, lightWallpaper).containerFg
    }

    fun layout(kind: Kind, variant: Variant): Int = when (kind) {
        Kind.TIMER -> when (variant) {
            Variant.PLAIN -> R.layout.widget; Variant.GLASS -> R.layout.w_timer_glass; Variant.LIGHTGLASS -> R.layout.w_timer_lightglass
            Variant.THIN -> R.layout.w_timer_thin; Variant.THINLIGHT -> R.layout.w_timer_thinlight; Variant.HEAVY -> R.layout.w_timer_heavy
        }
        Kind.CLASSES -> when (variant) {
            Variant.PLAIN -> R.layout.widget_classes; Variant.GLASS -> R.layout.w_classes_glass; Variant.LIGHTGLASS -> R.layout.w_classes_lightglass
            Variant.THIN -> R.layout.w_classes_thin; Variant.THINLIGHT -> R.layout.w_classes_thinlight; Variant.HEAVY -> R.layout.w_classes_heavy
        }
        Kind.LIST -> when (variant) {
            Variant.PLAIN -> R.layout.widget_timers; Variant.GLASS -> R.layout.w_list_glass; Variant.LIGHTGLASS -> R.layout.w_list_lightglass
            Variant.THIN -> R.layout.w_list_thin; Variant.THINLIGHT -> R.layout.w_list_thinlight; Variant.HEAVY -> R.layout.w_list_heavy
        }
    }
}

/**
 * The user's defaults for CLASS timers (they can't be styled one by one, because a sync would overwrite that), read by
 * the same functions that style your own timers. Kept in memory and refreshed whenever the preference changes.
 * Reading [version] from a composable makes it redraw when a default changes.
 */
object Defaults {
    private lateinit var prefs: SharedPreferences
    var version by mutableIntStateOf(0)
        private set

    @Volatile var classStyle: WidgetStyle = WidgetStyle.CARD; private set
    @Volatile var classColor: Int? = null; private set
    @Volatile var classProgress: Int? = null; private set

    // Must stay referenced: SharedPreferences only holds listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.prefs
        prefs.registerOnSharedPreferenceChangeListener(listener)
        reload()
    }

    private fun reload() {
        classStyle = WidgetStyle.of(prefs.getString("classStyle", null)) ?: WidgetStyle.CARD
        classColor = prefs.getInt("classColor", -1).takeIf { it >= 0 }
        classProgress = prefs.getInt("classProgress", -1).takeIf { it >= 0 }
        version++
    }

    fun setClassStyle(ctx: Context, v: WidgetStyle?) { ctx.prefs.edit().apply { if (v == null) remove("classStyle") else putString("classStyle", v.id) }.apply() }
    fun setClassColor(ctx: Context, v: Int?) { ctx.prefs.edit().putInt("classColor", v ?: -1).apply() }
    fun setClassProgress(ctx: Context, v: Int?) { ctx.prefs.edit().putInt("classProgress", v ?: -1).apply() }
}

/**
 * The single place that decides which style an event is drawn in, from most to least specific:
 * the widget's own override, then the event's own choice, then the class default (class timers only), then Card.
 */
fun resolveStyle(widgetOverride: WidgetStyle?, event: Event?, classDefault: WidgetStyle): WidgetStyle =
    widgetOverride ?: WidgetStyle.of(event?.widgetStyle) ?: if (event?.source == AMIZONE) classDefault else WidgetStyle.CARD
