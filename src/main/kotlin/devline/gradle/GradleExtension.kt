package devline.gradle

import com.google.gson.Gson
import com.google.gson.JsonObject
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionManifest
import devline.extensions.IdeExtensionProvider
import devline.extensions.IdeExtensionRuntime

/** Standalone IDE extension; never a project or exported-application dependency. */
class GradleExtension : IdeExtensionProvider {
    override val manifest: IdeExtensionManifest = javaClass.getResourceAsStream("/gradle-extension.json")!!.reader(Charsets.UTF_8).use {
        val gson = Gson()
        gson.fromJson(gson.fromJson(it, JsonObject::class.java)["extension"], IdeExtensionManifest::class.java)
    }

    override fun activate(context: IdeExtensionContext): IdeExtensionRuntime = GradleRuntime(context)
}
