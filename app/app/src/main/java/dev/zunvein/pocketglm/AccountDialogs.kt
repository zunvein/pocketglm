package dev.zunvein.pocketglm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.text.InputType
import android.widget.Toast

/** Dialogs shared by Accounts and the first-run setup. */
object AccountDialogs {

    fun promptPaste(ctx: Context, onChanged: () -> Unit) {
        val input = Ui.input(ctx, "eyJ…").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            minLines = 3
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NONE
        }
        Ui.dialogWith(ctx, "Paste a Z.AI token", input, "Save") {
            val msg = when (val r = AccountStore.add(ctx, input.text.toString())) {
                is AccountStore.AddResult.Added -> "Account saved (${r.account.hint})"
                AccountStore.AddResult.Duplicate -> "That account is already saved"
                AccountStore.AddResult.Guest -> "That is a guest token, not a signed-in account."
                is AccountStore.AddResult.Invalid -> r.reason
                is AccountStore.AddResult.StorageError -> "Could not store the token securely: ${r.reason}"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            onChanged()
        }
    }

    /** Copies the full token after a warning, so it can be pasted back after a reinstall. */
    fun confirmCopyToken(ctx: Context, account: AccountStore.Account) {
        Ui.confirm(ctx, "Copy the full token?",
            "Anyone who has this token can use your Z.AI account. Paste it somewhere private, then clear your clipboard.",
            "Copy") {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("zai-token", account.token)
            if (Build.VERSION.SDK_INT >= 33) {
                // Keeps the value out of clipboard previews.
                clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            cm.setPrimaryClip(clip)
            Toast.makeText(ctx, "Token copied", Toast.LENGTH_SHORT).show()
        }
    }
}
