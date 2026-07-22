/*
 * xSSH — TerminalHost.
 *
 * Compose wrapper around Termux's TerminalView. Key point (this is the whole
 * reason xSSH exists): the view implements Android's InputConnection contract
 * against the *system IME* — the user's own keyboard app — so no in-app
 * keyboard is drawn. Letters, numbers, punctuation, autocorrect deltas,
 * emoji, and IME composition all flow straight into the PTY.
 *
 * Ctrl/Alt/Esc/Tab/arrows come from the modifier bar (ModifierBar.kt).
 */
package com.xssh.core.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

@Composable
fun TerminalHost(
    io: ShellIo,
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 14,
    onSession: (TerminalSession) -> Unit = {},
) {
    val context = LocalContext.current
    val viewRef = remember(io) { AtomicReference<TerminalView?>(null) }
    val remoteSession = remember(io) { createSession(context, io, viewRef) }
    val session = remoteSession.session
    val view =
        remember(remoteSession) {
            buildTerminalView(context, session, io, fontSizeSp).also(viewRef::set)
        }

    DisposableEffect(remoteSession) {
        onSession(session)
        onDispose {
            viewRef.set(null)
            remoteSession.finish()
        }
    }

    AndroidView(
        factory = {
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
            view.post {
                val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                input?.showSoftInput(view, 0)
            }
            view
        },
        modifier = modifier.background(Color(0xFF0B0F14)),
        update = { it.attachSession(session) },
    )
}

private fun createSession(
    context: Context,
    io: ShellIo,
    viewRef: AtomicReference<TerminalView?>,
): RemoteTerminalSession {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val client =
        object : TerminalSessionClient {
            override fun onTextChanged(session: TerminalSession) {
                viewRef.get()?.onScreenUpdated()
            }

            override fun onTitleChanged(session: TerminalSession) {
                io.onTitleChanged(session.title ?: "")
            }

            override fun onSessionFinished(session: TerminalSession) = Unit

            override fun onCopyTextToClipboard(
                session: TerminalSession,
                text: String,
            ) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Terminal selection", text))
            }

            override fun onPasteTextFromClipboard(session: TerminalSession) {
                val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return
                val text = item.coerceToText(context)?.toString().orEmpty()
                if (text.isNotEmpty()) io.onUserInput(text.toByteArray(Charsets.UTF_8))
            }

            override fun onBell(session: TerminalSession) {
                viewRef.get()?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                io.onBell()
            }

            override fun onColorsChanged(session: TerminalSession) = Unit

            override fun onTerminalCursorStateChange(state: Boolean) = Unit

            override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

            override fun logError(
                tag: String,
                message: String,
            ) = Unit

            override fun logWarn(
                tag: String,
                message: String,
            ) = Unit

            override fun logInfo(
                tag: String,
                message: String,
            ) = Unit

            override fun logDebug(
                tag: String,
                message: String,
            ) = Unit

            override fun logVerbose(
                tag: String,
                message: String,
            ) = Unit

            override fun logStackTraceWithMessage(
                tag: String,
                message: String,
                e: Exception,
            ) = Unit

            override fun logStackTrace(
                tag: String,
                e: Exception,
            ) = Unit
        }
    return RemoteTerminalSession(context = context, io = io, client = client)
}

private fun buildTerminalView(
    context: Context,
    session: TerminalSession,
    io: ShellIo,
    fontSizeSp: Int,
): TerminalView {
    val view = TerminalView(context, null)
    view.isFocusable = true
    view.isFocusableInTouchMode = true
    view.isClickable = true
    view.isLongClickable = true
    val scaledDensity = context.resources.displayMetrics.density * context.resources.configuration.fontScale
    var textSizePx = (fontSizeSp * scaledDensity).roundToInt()
    view.setTextSize(textSizePx)
    view.setTypeface(Typeface.MONOSPACE)
    var lastColumns = -1
    var lastRows = -1

    fun notifyRemoteSize() {
        val emulator = session.emulator ?: return
        if (emulator.mColumns != lastColumns || emulator.mRows != lastRows) {
            lastColumns = emulator.mColumns
            lastRows = emulator.mRows
            io.onResize(lastColumns, lastRows)
        }
    }
    view.addOnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
        changed.post { notifyRemoteSize() }
    }
    view.setTerminalViewClient(
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                val min = (10 * scaledDensity).roundToInt()
                val max = (28 * scaledDensity).roundToInt()
                val next = (textSizePx * scale).roundToInt().coerceIn(min, max)
                if (next != textSizePx) {
                    textSizePx = next
                    view.setTextSize(textSizePx)
                    view.post { notifyRemoteSize() }
                }
                return 1f
            }

            override fun onSingleTapUp(e: MotionEvent) {
                if (view.isSelectingText) {
                    view.stopTextSelectionMode()
                } else {
                    view.requestFocus()
                    val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    input?.showSoftInput(view, 0)
                }
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false

            override fun shouldEnforceCharBasedInput(): Boolean = false

            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

            override fun isTerminalViewSelected(): Boolean = true

            override fun copyModeChanged(copyMode: Boolean) = Unit

            override fun onKeyDown(
                keyCode: Int,
                e: KeyEvent,
                session: TerminalSession,
            ): Boolean {
                var keyMode = 0
                if (e.isShiftPressed) keyMode = keyMode or KeyHandler.KEYMOD_SHIFT
                if (e.isCtrlPressed) keyMode = keyMode or KeyHandler.KEYMOD_CTRL
                if (e.isAltPressed) keyMode = keyMode or KeyHandler.KEYMOD_ALT
                if (e.isNumLockOn) keyMode = keyMode or KeyHandler.KEYMOD_NUM_LOCK
                val emulator = session.emulator
                val special =
                    KeyHandler.getCode(
                        keyCode,
                        keyMode,
                        emulator?.isCursorKeysApplicationMode ?: false,
                        emulator?.isKeypadApplicationMode ?: false,
                    )
                if (special != null) {
                    io.onUserInput(special.toByteArray(Charsets.UTF_8))
                    return true
                }
                if (e.isAltPressed) {
                    val unmodifiedMeta =
                        e.metaState and
                            (KeyEvent.META_ALT_MASK or KeyEvent.META_CTRL_MASK).inv()
                    val codePoint = e.getUnicodeChar(unmodifiedMeta)
                    if (codePoint != 0 && codePoint and KeyCharacterMap.COMBINING_ACCENT == 0) {
                        val effective =
                            if (e.isCtrlPressed && codePoint in 0x20..0x7f) {
                                codePoint and 0x1f
                            } else {
                                codePoint
                            }
                        io.onUserInput(
                            byteArrayOf(0x1b) +
                                String(Character.toChars(effective)).toByteArray(Charsets.UTF_8),
                        )
                        return true
                    }
                }
                return false
            }

            override fun onKeyUp(
                keyCode: Int,
                e: KeyEvent,
            ): Boolean = false

            override fun onLongPress(e: MotionEvent): Boolean {
                view.startTextSelectionMode(e)
                return true
            }

            override fun readControlKey(): Boolean = false

            override fun readAltKey(): Boolean = false

            override fun readShiftKey(): Boolean = false

            override fun readFnKey(): Boolean = false

            override fun onCodePoint(
                cp: Int,
                ctrlDown: Boolean,
                session: TerminalSession,
            ): Boolean {
                if (!Character.isValidCodePoint(cp)) return true
                val effective = if (ctrlDown && cp in 0x20..0x7f) cp and 0x1f else cp
                io.onUserInput(String(Character.toChars(effective)).toByteArray(Charsets.UTF_8))
                return true
            }

            override fun onEmulatorSet() {
                notifyRemoteSize()
            }

            override fun logError(
                tag: String,
                message: String,
            ) = Unit

            override fun logWarn(
                tag: String,
                message: String,
            ) = Unit

            override fun logInfo(
                tag: String,
                message: String,
            ) = Unit

            override fun logDebug(
                tag: String,
                message: String,
            ) = Unit

            override fun logVerbose(
                tag: String,
                message: String,
            ) = Unit

            override fun logStackTraceWithMessage(
                tag: String,
                message: String,
                e: Exception,
            ) = Unit

            override fun logStackTrace(
                tag: String,
                e: Exception,
            ) = Unit
        },
    )
    view.attachSession(session)
    return view
}
