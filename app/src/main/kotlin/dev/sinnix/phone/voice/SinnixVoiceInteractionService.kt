package dev.sinnix.phone.voice

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.sinnix.phone.ui.talk.TalkActivity

/** Lightweight system-assistant role used for reliable capture restarts. */
class SinnixVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() = launchTalk()

    override fun onPrepareToShowSession(args: Bundle, flags: Int) {
        super.onPrepareToShowSession(args, flags)
        launchTalk()
    }

    private fun launchTalk() {
        startActivity(
            Intent(this, TalkActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

class SinnixVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        object : VoiceInteractionSession(this) {}
}
