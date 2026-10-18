package it.ayngallery

import android.content.ContentResolver
import android.graphics.*
import android.net.Uri
import android.util.Size
import org.json.JSONObject
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class ImageRecipe(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f,
    val turns: Int = 0, val mirror: Boolean = false, val format: String = "PNG", val quality: Int = 92, val longest: Int = 0,
    val allowGame: Boolean = false, val input: String = "STATIC", val mirrorVertical: Boolean = false, val filter: String = "ORIGINAL", val gifFrame: Int = 0) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } && left < right && top < bottom) { localized(R.string.text_invalid_crop_b9c1bc, "Ritaglio non valido") }
        require(turns in 0..3 && format in setOf("PNG", "JPEG") && quality in 1..100 && longest in setOf(0, 1024, 2048, 4096)) { localized(R.string.text_invalid_recipe_ed8ebd, "Ricetta non valida") }
        require(input in setOf("STATIC", "GIF_FIRST_FRAME")) { localized(R.string.text_invalid_export_type_768187, "Tipo di esportazione non valido") }
        require(gifFrame in 0..1999 && (input == "GIF_FIRST_FRAME" || gifFrame == 0))
        require(filter in filters) { localized(R.string.text_invalid_image_filter_d2a84e, "Filtro immagine non valido") }
    }
    val mime get() = if (format == "JPEG") "image/jpeg" else "image/png"
    val extension get() = if (format == "JPEG") "jpg" else "png"
    fun accepts(kind: String) = kind == if (input == "GIF_FIRST_FRAME") "GIF" else "IMAGE"
    fun rect(width: Int, height: Int): Rect {
        require(width > 0 && height > 0)
        val x = floor(left * width).toInt().coerceIn(0, width - 1); val y = floor(top * height).toInt().coerceIn(0, height - 1)
        return Rect(x, y, ceil(right * width).toInt().coerceIn(x + 1, width), ceil(bottom * height).toInt().coerceIn(y + 1, height))
    }
    fun ratio(width: Int, height: Int, ratio: Float): ImageRecipe {
        require(width > 0 && height > 0 && ratio.isFinite() && ratio > 0)
        val w = min(1f, ratio * height / width); val h = min(1f, width / (ratio * height))
        return copy(left = (1f - w) / 2, right = (1f + w) / 2, top = (1f - h) / 2, bottom = (1f + h) / 2)
    }
    fun encode() = JSONObject().put("v", 1).put("left", left).put("top", top).put("right", right).put("bottom", bottom)
        .put("turns", turns).put("mirror", mirror).put("format", format).put("quality", quality).put("longest", longest)
        .put("allowGame", allowGame).put("input", input).put("mirrorVertical", mirrorVertical).put("filter", filter).put("gifFrame", gifFrame).toString()
    companion object {
        val filters = setOf("ORIGINAL", "MONO", "WARM", "COOL")
        fun decode(text: String): ImageRecipe {
            require(text.length <= 4096)
            val root = JSONObject(text); require(root.getInt("v") == 1) { localized(R.string.text_unsupported_recipe_d2aba5, "Ricetta non supportata") }
            return ImageRecipe(root.getDouble("left").toFloat(), root.getDouble("top").toFloat(), root.getDouble("right").toFloat(), root.getDouble("bottom").toFloat(),
                root.getInt("turns"), root.getBoolean("mirror"), root.getString("format"), root.getInt("quality"), root.getInt("longest"), root.optBoolean("allowGame", false),
                if (root.has("input")) root.getString("input") else "STATIC", root.optBoolean("mirrorVertical", false), root.optString("filter", "ORIGINAL"), root.optInt("gifFrame", 0))
        }
    }
}

data class ImagePreview(val bitmap: Bitmap, val original: Size)

object ImageEditing {
    internal fun gifBytes(resolver: ContentResolver, uri: Uri): ByteArray {
        return resolver.openInputStream(uri)!!.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(output.size() + n <= 16 * 1024 * 1024)
                output.write(buffer, 0, n)
            }
            output.toByteArray().also { require(GifPlayback.header(it) != null) }
        }
    }

    private fun checkInput(info: ImageDecoder.ImageInfo, input: String) {
        when (input) {
            "STATIC" -> require(!info.isAnimated) { localized(R.string.text_this_file_is_animated_use_explicit_gif_frame_exp_7d4cbd, "Questo file è animato: usa l'esportazione esplicita del fotogramma GIF") }
            "GIF_FIRST_FRAME" -> require(info.mimeType == "image/gif") { localized(R.string.text_the_source_is_not_a_gif_no_conversion_performed_784764, "La sorgente non è una GIF: nessuna conversione effettuata") }
            else -> throw IllegalArgumentException(localized(R.string.text_invalid_export_type_768187, "Tipo di esportazione non valido"))
        }
    }
    fun preview(resolver: ContentResolver, uri: Uri, input: String = "STATIC", gifFrame: Int = 0): ImagePreview {
        if (input == "GIF_FIRST_FRAME") return GifPlayback.decodeStill(gifBytes(resolver, uri), gifFrame, 1280)
        var original = Size(1, 1)
        val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            checkInput(info, input)
            original = info.size
            val factor = min(1.0, 1280.0 / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, (info.size.width * factor).toInt()), max(1, (info.size.height * factor).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        return ImagePreview(image, original)
    }
    /** Decode orientation once, then crop in that upright coordinate system before mirror/quarter-turns. */
    fun render(resolver: ContentResolver, uri: Uri, recipe: ImageRecipe, budget: Long = min(128L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 3)): Bitmap {
        if (recipe.input == "GIF_FIRST_FRAME") {
            val bytes = gifBytes(resolver, uri)
            val header = checkNotNull(GifPlayback.header(bytes))
            val longest = if (recipe.longest == 0) max(header.width, header.height) else recipe.longest
            require(min(header.width, longest).toLong() * min(header.height, longest) <= budget / 16)
            val image = GifPlayback.decodeStill(bytes, recipe.gifFrame, longest).bitmap
            val rect = recipe.rect(image.width, image.height)
            val cropped = Bitmap.createBitmap(image, rect.left, rect.top, rect.width(), rect.height())
            try { return transform(cropped, recipe.turns, recipe.mirror, recipe.mirrorVertical, recipe.filter, recipe.format == "JPEG") }
            finally { if (cropped !== image) cropped.recycle(); image.recycle() }
        }
        val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            checkInput(info, recipe.input)
            val limit = if (recipe.longest == 0) max(info.size.width, info.size.height) else recipe.longest
            val factor = min(1.0, limit.toDouble() / max(info.size.width, info.size.height))
            val width = max(1, (info.size.width * factor).toInt()); val height = max(1, (info.size.height * factor).toInt())
            require(width.toLong() * height <= budget / 16) { localized(R.string.text_large_image_choose_2048_or_1024_px_before_export_f848e3, "Immagine grande: scegli 2048 o 1024 px prima di esportare") }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(width, height)
            decoder.setCrop(recipe.rect(width, height))
        }
        return try { transform(image, recipe.turns, recipe.mirror, recipe.mirrorVertical, recipe.filter, recipe.format == "JPEG") }
        finally { image.recycle() }
    }
    fun transform(image: Bitmap, turns: Int, mirror: Boolean, white: Boolean): Bitmap = transform(image, turns, mirror, false, "ORIGINAL", white)
    fun transform(image: Bitmap, turns: Int, mirror: Boolean, mirrorVertical: Boolean, filter: String, white: Boolean): Bitmap {
        require(turns in 0..3)
        require(filter in ImageRecipe.filters)
        val width = if (turns % 2 == 0) image.width else image.height
        val height = if (turns % 2 == 0) image.height else image.width
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            if (white) { canvas.drawColor(Color.WHITE); output.setHasAlpha(false) }
            val matrix = Matrix().apply {
                postScale(if (mirror) -1f else 1f, if (mirrorVertical) -1f else 1f)
                postRotate(turns * 90f)
                val bounds = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()); mapRect(bounds)
                postTranslate(-bounds.left, -bounds.top)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (filter != "ORIGINAL") paint.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(filterMatrix(filter)))
            canvas.drawBitmap(image, matrix, paint)
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
    }
    fun filterMatrix(filter: String): FloatArray = when (filter) {
        "MONO" -> floatArrayOf(.213f,.715f,.072f,0f,0f, .213f,.715f,.072f,0f,0f, .213f,.715f,.072f,0f,0f, 0f,0f,0f,1f,0f)
        "WARM" -> floatArrayOf(1.06f,0f,0f,0f,8f, 0f,1f,0f,0f,2f, 0f,0f,.88f,0f,-8f, 0f,0f,0f,1f,0f)
        "COOL" -> floatArrayOf(.92f,0f,0f,0f,-5f, 0f,1f,0f,0f,1f, 0f,0f,1.08f,0f,8f, 0f,0f,0f,1f,0f)
        else -> floatArrayOf(1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, 0f,0f,0f,1f,0f)
    }
    fun write(image: Bitmap, recipe: ImageRecipe, output: OutputStream) {
        check(image.compress(if (recipe.format == "JPEG") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, recipe.quality, output)) { localized(R.string.text_image_encoding_failed_856b1c, "Codifica immagine non riuscita") }
        output.flush()
    }
}
