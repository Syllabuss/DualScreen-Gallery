package it.ayngallery

import android.net.Uri
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.util.ReflectionHelpers
import androidx.core.content.FileProvider
import java.io.File

/** AndroidX 1.15 uses literal '/' for canonical paths. Normalize only host Windows separators:
 * manifest parsing, root choice, URI decoding, provider guards and actual file IO remain native.
 * This does not certify Android recipient grant enforcement, which requires a device. */
@Implements(className = "androidx.core.content.FileProvider\$SimplePathStrategy", isInAndroidSdk = false)
class WindowsFileProviderPaths {
    companion object {
        // AndroidX static authority cache otherwise retains the previous test's temporary filesDir.
        fun reset() { ReflectionHelpers.getStaticField<MutableMap<*, *>>(FileProvider::class.java, "sCache").clear() }
    }
    @RealObject private lateinit var real: Any
    @Implementation protected fun belongsToRoot(path: String, root: String): Boolean {
        val child = path.replace('\\', '/').trimEnd('/')
        val parent = root.replace('\\', '/').trimEnd('/')
        return child == parent || child.startsWith("$parent/")
    }
    @Implementation protected fun getUriForFile(file: File): Uri {
        val uri = Shadow.directlyOn<Uri>(real, "androidx.core.content.FileProvider\$SimplePathStrategy",
            "getUriForFile", ClassParameter.from(File::class.java, file))
        return uri.buildUpon().encodedPath(uri.encodedPath!!.replace("%5C", "/")).build()
    }
}
