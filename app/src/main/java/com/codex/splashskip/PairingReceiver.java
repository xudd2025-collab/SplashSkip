package com.codex.splashskip;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class PairingReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Bundle reply = RemoteInput.getResultsFromIntent(intent);
        if (reply != null && reply.getCharSequence("pair_code") != null) {
            LocalAdbController.get(context).pairFromNotification(reply.getCharSequence("pair_code").toString(),
                    intent.getIntExtra("pair_port", 0), intent.getStringExtra("pair_session"));
        }
    }
}
