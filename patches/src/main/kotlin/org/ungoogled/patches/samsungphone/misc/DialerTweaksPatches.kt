package org.ungoogled.patches.samsungphone.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsungphone.SamsungPhoneConstants.COMPATIBILITY_SAMSUNG_PHONE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.w3c.dom.Element
import java.io.File

private const val DIALPAD_SEARCH = "Lorg/ungoogled/samsungphone/DialpadSearch;"

private const val QUERY_ARGS = "Landroid/net/Uri;[Ljava/lang/String;" +
    "Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;"

private const val CURSOR = "Landroid/database/Cursor;"

@Suppress("unused")
val dialpadContactSuggestionsPatch = bytecodePatch(
    name = "Dial pad contact suggestions",
    description = "Suggests contacts while you type on the dial pad.",
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
                    "query($QUERY_ARGS)$CURSOR",
                    "query(${QUERY_ARGS}Landroid/os/CancellationSignal;)$CURSOR",
                ),
            ),
        )
        if (moved < 2) throw PatchException("only $moved queries found")
    }
}

internal object DialpadWindowLayoutFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("DisplayMetrics Width:", " isSmallestDisplay : "),
)

private val keypadHeightEveryTimePatch = bytecodePatch {
    execute {
        val method = DialpadWindowLayoutFingerprint.method
        val code = method.implementation!!.instructions.toList()
        var flag: FieldReference? = null
        for (i in code.indices) {
            if (code[i].opcode != Opcode.IGET_BOOLEAN) continue
            val ref = (code[i] as ReferenceInstruction).reference as FieldReference
            if (ref.definingClass != method.definingClass) continue
            val cleared = code.drop(i + 1).take(6).any {
                it.opcode == Opcode.IPUT_BOOLEAN &&
                    (it as ReferenceInstruction).reference == ref
            }
            if (cleared) {
                flag = ref
                break
            }
        }
        val f = flag ?: throw PatchException("keypad layout flag not found")
        val field = "${f.definingClass}->${f.name}:${f.type}"
        method.addInstructions(
            0,
            """
                move-object/from16 v0, p0
                const/4 v1, 0x1
                iput-boolean v1, v0, $field
            """.trimIndent(),
        )
    }
}

@Suppress("unused")
val dialpadLayoutPatch = resourcePatch(
    name = "Dial pad layout",
    description = "Moves the dial pad keys down. Adjustable.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_PHONE)
    dependsOn(keypadHeightEveryTimePatch)

    val phoneArea by intOption(
        key = "phoneKeypadArea",
        default = 570,
        title = "Phone screen: keypad area",
        description = "Samsung: 650.",
        required = true,
    )
    val phoneKeys by intOption(
        key = "phoneKeysShare",
        default = 565,
        title = "Phone screen: keys share",
        description = "Samsung: 620.",
        required = true,
    )
    val largeArea by intOption(
        key = "largeKeypadArea",
        default = 620,
        title = "Large screen: keypad area",
        description = "Samsung: 710.",
        required = true,
    )
    val largeKeys by intOption(
        key = "largeKeysShare",
        default = 580,
        title = "Large screen: keys share",
        description = "Samsung: 619.",
        required = true,
    )
    val floatingWidth by intOption(
        key = "floatingDialpadWidth",
        default = 300,
        title = "Large screen: dial pad width (dp)",
        description = "Samsung: 400.",
        required = true,
    )

    execute {
        val width = floatingWidth!!
        if (width !in 200..600) throw PatchException("width must be 200 to 600")
        val values = mapOf(
            "dialpad_keypadview_weight_percent" to phoneArea!!,
            "dialpad_keypad_weight" to phoneKeys!!,
            "dialpad_keypadview_weight_percent_tab" to largeArea!!,
            "dialpad_keypadview_weight_percent_tab_delta" to largeArea!!,
            "dialpad_keypad_weight_tab" to largeKeys!!,
        )
        for ((name, value) in values) {
            if (value !in 100..1000) throw PatchException("$name must be 100 to 1000")
        }

        decode("res/values", "res/values-*")
        val res = this["res"]
        val valueDirs = res.listFiles()!!.filter {
            it.isDirectory && (it.name == "values" || it.name.startsWith("values-"))
        }

        var changed = 0
        for (dir in valueDirs) {
            if (dir.name.contains("land")) continue
            if (!File(dir, "integers.xml").exists()) continue
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
        if (changed < values.size) throw PatchException("only $changed values found")

        var widths = 0
        for (dir in valueDirs) {
            if (!File(dir, "dimens.xml").exists()) continue
            document("res/${dir.name}/dimens.xml").use { xml ->
                val items = xml.getElementsByTagName("dimen")
                for (i in 0 until items.length) {
                    val item = items.item(i) as Element
                    if (item.getAttribute("name") != "floating_dialpad_width") continue
                    item.textContent = "${width}dp"
                    widths++
                }
            }
        }
        if (widths == 0) throw PatchException("floating_dialpad_width not found")
    }
}
