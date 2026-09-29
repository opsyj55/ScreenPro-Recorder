package com.screenpro.recorder

import android.content.Context
import android.graphics.Typeface

/** Poppins fonts bundled in res/font, with safe fallbacks to the system font. */
object Fonts {
    private val cache = HashMap<Int, Typeface>()

    private fun load(ctx: Context, res: Int, fallback: Typeface): Typeface {
        return cache.getOrPut(res) {
            try {
                ctx.applicationContext.resources.getFont(res)
            } catch (_: Exception) {
                fallback
            }
        }
    }

    fun regular(ctx: Context): Typeface =
        load(ctx, R.font.poppins_regular, Typeface.DEFAULT)

    fun medium(ctx: Context): Typeface =
        load(ctx, R.font.poppins_medium, Typeface.DEFAULT)

    fun semiBold(ctx: Context): Typeface =
        load(ctx, R.font.poppins_semibold, Typeface.DEFAULT_BOLD)

    fun bold(ctx: Context): Typeface =
        load(ctx, R.font.poppins_bold, Typeface.DEFAULT_BOLD)
}
