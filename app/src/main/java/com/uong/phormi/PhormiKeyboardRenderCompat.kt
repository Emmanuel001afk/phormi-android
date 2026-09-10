package com.uong.phormi

import android.view.View

/** Single renderer entry point retained for the existing V2 lifecycle. */
internal fun PhormiKeyboardServiceV2.render(): View = PhormiKeyboardUi.render(this)
