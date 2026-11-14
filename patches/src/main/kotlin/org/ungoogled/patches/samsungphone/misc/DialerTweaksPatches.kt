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
        default = 570,
        title = "Phone screen: keypad area",
        description = "Tenths of a percent of the screen height. Smaller moves the keys down. Samsung: 650.",
        required = true,
    )
    val phoneKeys by intOption(
        key = "phoneKeysShare",
        default = 565,
        title = "Phone screen: keys share",
        description = "Tenths of a percent of the keypad area. Larger makes the keys taller and the gap smaller. Samsung: 620.",
        required = true,
    )
    val largeArea by intOption(
        key = "largeKeypadArea",
        default = 620,
        title = "Large screen: keypad area",
        description = "Same as above, for large screens (a foldable's inner screen). Samsung: 710 (650 at 480 dpi).",
        required = true,
    )
    val largeKeys by intOption(
        key = "largeKeysShare",
        default = 580,
        title = "Large screen: keys share",
        description = "Same as above, for large screens. Samsung: 619.",
        required = true,
    )

        val floatingWidth by intOption(
        key = "floatingDialpadWidth",
        default = 300,
        title = "Large screen: dial pad width (dp)",
        description = "Samsung: 400.",
        required = true,
    )
