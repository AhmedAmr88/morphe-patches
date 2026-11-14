package org.ungoogled.patches.samsungphone.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsungphone.SamsungPhoneConstants.COMPATIBILITY_SAMSUNG_PHONE
import org.w3c.dom.Element

private const val DIALPAD_SEARCH = "Lorg/ungoogled/samsungphone/DialpadSearch;"

@Suppress("unused")
val dialpadContactSuggestionsPatch = bytecodePatch(
    name = "Dial pad contact suggestions",
    description = "Suggests contacts by name (keypad letters) and number while you type on the dial pad.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_PHONE)
    dependsOn(enableNonOneUiPatch)
    extendWith(SAMSUNG_PHONE_EXTENSION)

    execute {
        val moved = redirectCalls(
            DIALPAD_SEARCH,
            mapOf(
                "Landroid/content/ContentProviderClient;" to setOf(
                    "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
                    "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
                ),
            ),
        )
        java.util.logging.Logger.getLogger("SamsungPhone").info("ContentProviderClient queries: $moved")
        if (moved < 2) throw PatchException("only $moved ContentProviderClient queries found")
    }
}

@Suppress("unused")
val dialpadLayoutPatch = resourcePatch(
    name = "Dial pad layout",
    description = "Moves the dial pad keys down and closer to the call button. Adjustable for each screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_PHONE)

    val phoneArea by intOption(
        key = "phoneKeypadArea",
        default = 600,
        title = "Phone screen: keypad area",
        description = "Tenths of a percent of the screen height. Smaller moves the keys down. Samsung: 650.",
        required = true,
    )
    val phoneKeys by intOption(
        key = "phoneKeysShare",
        default = 680,
        title = "Phone screen: keys share",
        description = "Tenths of a percent of the keypad area. Larger makes the keys taller and the gap smaller. Samsung: 620.",
        required = true,
    )
    val largeArea by intOption(
        key = "largeKeypadArea",
        default = 660,
        title = "Large screen: keypad area",
        description = "Same as above, for large screens (a foldable's inner screen). Samsung: 710 (650 at 480 dpi).",
        required = true,
    )
    val largeKeys by intOption(
        key = "largeKeysShare",
        default = 680,
        title = "Large screen: keys share",
        description = "Same as above, for large screens. Samsung: 619.",
        required = true,
    )

    execute {
        val values = mapOf(
            "dialpad_keypadview_weight_percent" to phoneArea!!,
            "dialpad_keypad_weight" to phoneKeys!!,
            "dialpad_keypadview_weight_percent_tab" to largeArea!!,
            "dialpad_keypadview_weight_percent_tab_delta" to largeArea!!,
            "dialpad_keypad_weight_tab" to largeKeys!!,
        )
        for ((name, value) in values) {
            if (value !in 100..1000) throw PatchException("$name must be 100 to 1000, got $value")
        }

        decode("res/values", "res/values-*")
        val res = this["res"]
        var changed = 0
        val dirs = res.listFiles()!!.filter {
            it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) && !it.name.contains("land") &&
                java.io.File(it, "integers.xml").exists()
        }
        for (dir in dirs) {
            document("res/${dir.name}/integers.xml").use { xml ->
                val items = xml.getElementsByTagName("integer")
                for (i in 0 until items.length) {
                    val item = items.item(i) as Element
                    val value = values[item.getAttribute("name")] ?: continue
                    item.textContent = value.toString()
                    changed++
                }
            }
        }
        java.util.logging.Logger.getLogger("SamsungPhone").info("Dial pad layout values changed: $changed")
        if (changed < values.size) throw PatchException("only $changed dial pad layout values found")
    }
}
