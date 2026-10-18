package it.ayngallery



import androidx.compose.material3.ColorScheme

import androidx.compose.material3.darkColorScheme

import androidx.compose.material3.lightColorScheme

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.lerp

import androidx.compose.ui.graphics.luminance

import androidx.compose.ui.graphics.toArgb



@Suppress("DEPRECATION") // The same saved palette applies to panel and host-configuration windows.

internal fun applyGalleryWindowTheme(activity: android.app.Activity, preferences: GalleryPreferences) {

    val theme = GalleryThemes.get(preferences)

    val color = theme.background.toArgb()

    val window = activity.window

    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))

    window.statusBarColor = color; window.navigationBarColor = color

    if (android.os.Build.VERSION.SDK_INT >= 29) {

        window.isStatusBarContrastEnforced = false; window.isNavigationBarContrastEnforced = false

    }

    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {

        isAppearanceLightStatusBars = !theme.dark; isAppearanceLightNavigationBars = !theme.dark

    }

}



/** App-owned sRGB palettes. Shell references are photographic, not manufacturer colour standards. */

data class GalleryTheme(val id: String, private val nameText: () -> String, private val descriptionText: () -> String, val family: String,

                        val dark: Boolean, val background: Color, val accent: Color, val focus: Color,

                        val swatches: List<Color> = listOf(accent, focus),

                        val surfaceColor: Color? = null, val inkColor: Color? = null, val borderColor: Color? = null) {

    val name: String get() = nameText()
    val description: String get() = descriptionText()

    val colors: ColorScheme = run {

        val ink = inkColor ?: if (dark) Color(0xFFFAF7FF) else Color(0xFF24232B)

        val muted = if (inkColor != null) lerp(background, inkColor, .8f) else if (dark) Color(0xFFE0DCE8) else Color(0xFF59535F)

        val shadedShell = dark && background.luminance() > .07f

        val lift = if (shadedShell) Color.Black else if (dark) Color.White else ink

        val surface = surfaceColor ?: lerp(background, lift, if (dark) .035f else .018f)

        val variant = if (surfaceColor != null) lerp(surfaceColor, ink, .08f) else lerp(background, lift, if (dark) .09f else .075f)

        val primaryContainer = if (shadedShell) lerp(background, Color.Black, .18f) else lerp(background, accent, if (dark) .18f else .13f)

        val secondaryContainer = if (shadedShell) lerp(background, Color.Black, .18f) else lerp(background, focus, if (dark) .18f else .13f)

        val base = if (dark) darkColorScheme() else lightColorScheme()

        base.copy(primary = accent, onPrimary = readableInk(accent), primaryContainer = primaryContainer,

            onPrimaryContainer = ink, inversePrimary = if (dark) Color(0xFF56476E) else Color(0xFFD8C7F4),

            secondary = focus, onSecondary = readableInk(focus), secondaryContainer = secondaryContainer,

            onSecondaryContainer = ink, tertiary = focus, onTertiary = readableInk(focus),

            tertiaryContainer = secondaryContainer, onTertiaryContainer = ink,

            background = background, onBackground = ink, surface = surface, onSurface = ink,

            surfaceVariant = variant, onSurfaceVariant = muted, surfaceTint = Color.Transparent,

            surfaceDim = background, surfaceBright = variant, surfaceContainerLowest = background,

            surfaceContainerLow = surface, surfaceContainer = surface,

            surfaceContainerHigh = variant, surfaceContainerHighest = variant,

            inverseSurface = if (dark) Color(0xFFF3EFF5) else Color(0xFF24232B),

            inverseOnSurface = if (dark) Color(0xFF24232B) else Color(0xFFF3EFF5),

            outline = borderColor ?: if (dark) Color(0xFFC1B9CB) else Color(0xFF746A7D),

            outlineVariant = borderColor ?: lerp(background, muted, .3f), scrim = Color.Black,

            error = if (dark) Color(0xFFFFCBD0) else Color(0xFFA71D38),

            onError = if (dark) Color(0xFF460817) else Color.White,

            errorContainer = if (dark) Color(0xFF460817) else Color(0xFFFFE0E4),

            onErrorContainer = if (dark) Color(0xFFFFE0E4) else Color(0xFF460817))

    }

}



private fun readableInk(color: Color): Color =

    if ((color.luminance() + .05f) / .05f >= 1.05f / (color.luminance() + .05f)) Color(0xFF101014) else Color.White



object GalleryThemes {

    const val CUSTOM = "custom"

    const val BLACK = "ayn_black"

    const val WHITE = "ayn_white"

    val all = listOf(

        GalleryTheme("ayn_purple", { "AYN Purple" }, { localized(R.string.text_smoky_purple_lavender_and_champagne_5341d5, "Viola fumé · lavanda e champagne") }, "AYN", true,

            Color(0xFF241438), Color(0xFFE9A8FF), Color(0xFFFFD166),
            listOf(Color(0xFFE9A8FF), Color(0xFFFFD166), Color(0xFF69E7D2)), surfaceColor = Color(0xFF302047), borderColor = Color(0xFF68517D)),

        // Warm light-gray shell estimated from AYN's Rainbow product photograph (not an official HEX).
        // https://www.ayntec.com/products/ayn-thor?variant=46692546642112
        GalleryTheme("ayn_rainbow", { "AYN Rainbow" }, { localized(R.string.text_pearl_gray_button_accents_e8fab0, "Grigio caldo · accenti dei tasti") }, "AYN", false,

            Color(0xFFD9D5CC), Color(0xFF9D174D), Color(0xFF4F46A5),

            listOf(Color(0xFFB72F45), Color(0xFF27699B), Color(0xFF397142), Color(0xFFD0A634)),
            inkColor = Color(0xFF1C1B20)),

        GalleryTheme(WHITE, { "AYN White" }, { localized(R.string.text_neutral_white_graphite_and_slate_blue_7cd029, "Bianco neutro · grafite e blu ardesia") }, "AYN", false,

            Color(0xFFF7F7F4), Color(0xFF26354A), Color(0xFF087EA4), surfaceColor = Color(0xFFFFFFFF), borderColor = Color(0xFFC7D0D9)),

        GalleryTheme(BLACK, { "AYN Black" }, { localized(R.string.text_amoled_black_silver_and_ice_313bfc, "Nero AMOLED · argento e ghiaccio") }, "AYN", true,

            Color.Black, Color(0xFFE8EDF7), Color(0xFF58D8FF), surfaceColor = Color(0xFF101522), borderColor = Color(0xFF39465B)),

        GalleryTheme("rose_milk", { localized(R.string.text_rose_milk_2c3a5e, "Latte e rosa") }, { localized(R.string.text_warm_white_powder_pink_5524ec, "Bianco caldo · rosa cipria") }, "PASTEL", false,

            Color(0xFFFFE6EF), Color(0xFF9B2353), Color(0xFF5A54A4), listOf(Color(0xFFFF8DB9), Color(0xFFB9AAF4), Color(0xFFFFD4A8)), surfaceColor = Color(0xFFFFDBE8), inkColor = Color(0xFF3B1B2C), borderColor = Color(0xFFDFA0B9)),

        GalleryTheme("sky_milk", { localized(R.string.text_sky_milk_9a0e81, "Latte e cielo") }, { localized(R.string.text_cool_white_powder_blue_710a43, "Bianco fresco · azzurro polvere") }, "PASTEL", false,

            Color(0xFFDDF0FF), Color(0xFF155A7E), Color(0xFF964B5D), listOf(Color(0xFF89CDF6), Color(0xFFF1AFC0), Color(0xFFFFCF8A)), surfaceColor = Color(0xFFC8E6FB), inkColor = Color(0xFF18364A), borderColor = Color(0xFF8BB9D1)),

        GalleryTheme("lavender_cloud", { localized(R.string.text_lavender_cloud_4cff2b, "Nuvola lavanda") }, { localized(R.string.text_soft_lilac_violet_and_sage_7793f8, "Lilla tenue · violetta e salvia") }, "PASTEL", false,

            Color(0xFFE7DFF9), Color(0xFF5B338C), Color(0xFF306A60), listOf(Color(0xFFBDA1EB), Color(0xFF91CDBB), Color(0xFFF4BACD)), surfaceColor = Color(0xFFD9CCF2), inkColor = Color(0xFF33224D), borderColor = Color(0xFFAC94C7)),

        GalleryTheme("mint_cream", { localized(R.string.text_mint_cream_2af769, "Crema e menta") }, { localized(R.string.text_pale_mint_vanilla_cream_d7ef2e, "Menta pallida · crema vaniglia") }, "PASTEL", false,

            Color(0xFFD8F0DF), Color(0xFF18583A), Color(0xFF91454E), listOf(Color(0xFF86C59A), Color(0xFFF4BEA0), Color(0xFFC7B4EF)), surfaceColor = Color(0xFFC6E7D1), inkColor = Color(0xFF173426), borderColor = Color(0xFF89B89A)),

        GalleryTheme("peach_tea", { localized(R.string.text_peach_tea_740475, "Tè alla pesca") }, { localized(R.string.text_light_peach_terracotta_and_teal_0e2200, "Pesca chiara · terracotta e petrolio") }, "PASTEL", false,

            Color(0xFFFFE4CF), Color(0xFF8E351B), Color(0xFF285E76), listOf(Color(0xFFFFA777), Color(0xFF8FC9D9), Color(0xFFFFD36E)), surfaceColor = Color(0xFFFFD3B3), inkColor = Color(0xFF47271E), borderColor = Color(0xFFDFA180)),

        GalleryTheme("midnight_sage", { localized(R.string.text_black_and_sage_cf43a0, "Nero e salvia") }, { localized(R.string.text_charcoal_soft_green_and_sand_690002, "Carbone · verde morbido e sabbia") }, "DARK", true,

            Color(0xFF102821), Color(0xFFA6D7B4), Color(0xFFF3C88B), listOf(Color(0xFFA6D7B4), Color(0xFFF3C88B), Color(0xFF72BCAE)), surfaceColor = Color(0xFF1A382D), borderColor = Color(0xFF547A65)),

        GalleryTheme("electric_lime", { localized(R.string.text_electric_lime_5aa0f2, "Lime elettrico") }, { localized(R.string.text_black_acid_green_and_cyan_97deef, "Nero · verde acido e ciano") }, "DARK", true,

            Color(0xFF050B0F), Color(0xFFABFF30), Color(0xFF00E5FF), listOf(Color(0xFFABFF30), Color(0xFF00E5FF), Color(0xFFFF4FD8)), surfaceColor = Color(0xFF0B1D21), borderColor = Color(0xFF3B665D)),

        GalleryTheme("stocking_pink", { "Stocking Pink" }, { localized(R.string.text_ink_black_neon_pink_and_ice_c1dc9e, "Nero inchiostro · rosa neon e ghiaccio") }, "DARK", true,

            Color(0xFF210C1F), Color(0xFFFF4CB8), Color(0xFF65ECFF), listOf(Color(0xFFFF4CB8), Color(0xFF65ECFF), Color(0xFFFFD166)), surfaceColor = Color(0xFF34152C), borderColor = Color(0xFF905275)),

        GalleryTheme("arcade_violet", { localized(R.string.text_violet_arcade_ad309f, "Arcade viola") }, { localized(R.string.text_violet_night_orchid_and_turquoise_eaa135, "Notte viola · orchidea e turchese") }, "DARK", true,

            Color(0xFF17143F), Color(0xFFA9AAFF), Color(0xFF49F2C2), listOf(Color(0xFFA9AAFF), Color(0xFF49F2C2), Color(0xFFFF6B8A)), surfaceColor = Color(0xFF252050), borderColor = Color(0xFF685D9E)),

        GalleryTheme("neon_nocturne", { localized(R.string.text_neon_nocturne_40d0b9, "Neon notturno") }, { localized(R.string.text_midnight_blue_cyan_and_fuchsia_87834d, "Blu notte · ciano e fucsia") }, "DARK", true,

            Color(0xFF051F32), Color(0xFF26ECFF), Color(0xFFFF6796),
            listOf(Color(0xFF26ECFF), Color(0xFFFF6796), Color(0xFFB8FF3B)),
            surfaceColor = Color(0xFF0B3048), borderColor = Color(0xFF396F89)),

        GalleryTheme("cyber_citrus", { "Cyber Citrus" }, { "Ambra scura · agrumi e viola laser" }, "DARK", true,
            Color(0xFF2B1505), Color(0xFFFFD43B), Color(0xFFCA9AFF),
            listOf(Color(0xFFFFD43B), Color(0xFFCA9AFF), Color(0xFF81F5C4)), surfaceColor = Color(0xFF3B210C), borderColor = Color(0xFF916939)),

        GalleryTheme("ultraviolet_drive", { "Ultraviolet Drive" }, { "Viola profondo · ametista e albicocca" }, "DARK", true,
            Color(0xFF220C31), Color(0xFFDF99FF), Color(0xFFFFA867),
            listOf(Color(0xFFDF99FF), Color(0xFFFFA867), Color(0xFF8CDDED)), surfaceColor = Color(0xFF351348), borderColor = Color(0xFF8C5B9E)),

        GalleryTheme("pastel_prism", { localized(R.string.text_pastel_prism_dec86c, "Prisma pastello") }, { localized(R.string.text_cream_lavender_and_sage_5c0db4, "Crema · lavanda e salvia") }, "PASTEL", false,

            Color(0xFFFFF6DD), Color(0xFF4A5179), Color(0xFF487054),

            listOf(Color(0xFFB8BCE8), Color(0xFFA9CDAA), Color(0xFFE8C689)),

            surfaceColor = Color(0xFFECE5CE), inkColor = Color(0xFF332E43), borderColor = Color(0xFFB9B39B)),

        GalleryTheme("sorbet_pop", { "Sorbet Pop" }, { "Limone · corallo e lavanda" }, "PASTEL", false,
            Color(0xFFFFF0B5), Color(0xFF8A1C3D), Color(0xFF5550A0),
            listOf(Color(0xFFFFD34D), Color(0xFFFF8A7A), Color(0xFFB5A2FF)), surfaceColor = Color(0xFFFFE28A), inkColor = Color(0xFF3A2A32), borderColor = Color(0xFFD5B34B)),

        GalleryTheme("lagoon_candy", { "Lagoon Candy" }, { "Azzurro acqua · corallo e uva" }, "PASTEL", false,
            Color(0xFFD8F3F0), Color(0xFF006169), Color(0xFF8A3271),
            listOf(Color(0xFF50C9C3), Color(0xFFFF9B83), Color(0xFFB99AFF)), surfaceColor = Color(0xFFBDE7E0), inkColor = Color(0xFF123333), borderColor = Color(0xFF78BDB3)),

        GalleryTheme("ember_copper", { localized(R.string.text_midnight_copper_6af1c7, "Rame notturno") }, { localized(R.string.text_warm_black_copper_and_mineral_blue_a03f6d, "Nero caldo · rame e azzurro minerale") }, "DARK", true,

            Color(0xFF261616), Color(0xFFFFAA81), Color(0xFF7FDEDD))

    )

    fun get(preferences: GalleryPreferences): GalleryTheme =

        if (preferences.themeId == CUSTOM) (preferences.customTheme ?: CustomTheme.from(get(BLACK))).theme() else get(preferences.themeId)

    fun find(id: String): GalleryTheme? = all.find { it.id == id }

    fun get(id: String): GalleryTheme = find(id) ?: all.first { it.id == BLACK }

}
