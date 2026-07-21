package com.xssh.core.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AbstractAuthMethod

internal class PromptListKeyboardInteractiveAuth(
    private val respond: (List<String>) -> List<String>,
) : AbstractAuthMethod("keyboard-interactive") {
    override fun buildReq(): SSHPacket =
        super.buildReq()
            .putString("")
            .putString("")

    override fun handle(
        msg: Message,
        buf: SSHPacket,
    ) {
        if (msg != Message.USERAUTH_60) {
            super.handle(msg, buf)
            return
        }
        val prompts =
            try {
                buf.readString() // name
                buf.readString() // instruction
                buf.readString() // lang-tag
                val count = buf.readUInt32AsInt()
                if (count !in 0..32) {
                    throw UserAuthException(
                        "Server sent an unreasonable number of authentication prompts: $count",
                    )
                }
                val list = mutableListOf<String>()
                repeat(count) {
                    val prompt = buf.readString()
                    if (prompt.length > 4_096) throw UserAuthException("Server authentication prompt is too long")
                    list.add(prompt)
                    buf.readBoolean() // echo; current UI intentionally hides answers.
                }
                list
            } catch (e: Buffer.BufferException) {
                throw UserAuthException(e)
            }

        val answers =
            runCatching { respond(prompts) }
                .getOrElse { throw UserAuthException("Keyboard-interactive responder failed", it) }

        if (answers.size != prompts.size) {
            throw UserAuthException(
                "Keyboard-interactive responder returned ${answers.size} answers for ${prompts.size} prompts.",
            )
        }

        val packet = SSHPacket(Message.USERAUTH_INFO_RESPONSE).putUInt32(answers.size.toLong())
        answers.forEach { answer ->
            val chars = answer.toCharArray()
            try {
                packet.putSensitiveString(chars)
            } finally {
                chars.fill('\u0000')
            }
        }
        params.transport.write(packet)
    }
}
