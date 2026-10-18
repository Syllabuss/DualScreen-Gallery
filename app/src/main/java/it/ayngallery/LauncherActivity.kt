package it.ayngallery

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Ordinary launch uses the detected pair by default; shortcuts and ceded displays remain single. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GalleryApp
        val session = app.session
        if (intent.action in listOf(null, android.content.Intent.ACTION_MAIN) && (app.video.returningFromHome || app.video.floating || app.video.pipOpening) && session.resumeVideoPanels(this)) {
            finish(); return
        }
        session.beginLauncherLaunch()
        session.refreshDisplays()
        val here = session.current(this)
        if (here != null) {
            val explicitSingle = intent.action == "it.ayngallery.OPEN_SINGLE"
            val widgetId = intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            val photo = intent.action == PhotoWidgetProvider.OPEN && app.photoWidgets.owns(widgetId)
            val music = intent.action == "it.ayngallery.OPEN_MUSIC" && app.widgets.owns(widgetId)
            val preferred = if (photo) app.photoWidgets.settings.get(widgetId).fallbackDisplay
                else if (app.widgets.owns(widgetId)) app.widgets.settings.get(widgetId).fallbackDisplay else ""
            val safePreferred = preferred.takeIf { key -> key.isNotBlank() && key !in session.state.ceded && session.screens.any { it.key == key } }
            val ordinary = intent.action in listOf(null, android.content.Intent.ACTION_MAIN)
            val thorLower = if (ordinary && !explicitSingle) session.thorPair?.first?.takeIf { session.startupOther(it.key) != null } else null
            val key = if (explicitSingle) here.key else thorLower?.key ?: safePreferred ?: DisplayPolicy.ordinaryTarget(
                here.key, session.screens.map { it.key }.toSet(), session.state)
            val target = session.screens.find { it.key == key }
            if (target == null) {
                uiToast(this, localized(R.string.text_this_display_is_released_long_press_the_app_icon_ede49c, "Questo display è ceduto. Tieni premuta l'icona e scegli «Apri qui, singolo»."), Toast.LENGTH_LONG).show()
            } else {
                val next = if (explicitSingle) DisplayPolicy.singleHere(target.key, session.screens.map { it.key }.toSet(), session.state)
                    .copy(anchorSlot = 0)
                    else if (thorLower != null) DisplayPolicy.thorTaskState(thorLower.key, session.thorPair!!.second.key, session.state)
                    else session.state.copy(anchor = target.key,
                        anchorSlot = if (target.key == session.state.anchor) session.state.anchorSlot else 0)
                if (session.save(next)) {
                    if (explicitSingle) session.singleFromLauncher(target.key)
                    val other = if (ordinary && !explicitSingle) session.screens.firstOrNull {
                        it.key == session.startupOther(target.key)
                    } else null
                    // Keep a panel on the launcher's display before opening the companion.
                    // Otherwise dual-display launchers can restore Home while we route to the other screen.
                    val companionFirst = other?.key == here.key
                    if (companionFirst && thorLower != null) {
                        if (session.launch(this, other!!, 1 - next.anchorSlot)) {
                            session.deferStartupCompanion(other.key, target.key, next.anchorSlot)
                        }
                        finish()
                        return
                    }
                    if (companionFirst) session.launch(this, other!!, 1 - next.anchorSlot)
                    val opened = session.launch(this, target, next.anchorSlot, openPlayer = intent.action == "it.ayngallery.OPEN_PLAYER",
                        openOperations = intent.action == "it.ayngallery.OPEN_OPERATIONS", openPhoto = photo,
                        photo = if (photo) PhotoWidgetTarget.from(intent) else null, pairedGallery = ordinary && !explicitSingle,
                        openMusic = music, musicMedia = if (music) intent.getStringExtra("music_media") else null)
                    if (opened && !companionFirst && other != null) {
                        session.launch(this, other, 1 - next.anchorSlot)
                    }
                }
            }
        }
        finish()
    }
}
