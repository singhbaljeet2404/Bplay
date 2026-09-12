package com.bplay.mirror;

import com.bplay.protocol.HandshakeCodec;

/** What a transport needs from the service in order to admit a sender. */
public interface SessionHost {

    /** The PIN senders must present, or "" when the user has disabled it. */
    String requiredPin();

    String deviceName();

    int maxHeight();

    int maxBitrate();

    /**
     * Admits a sender if the screen is free.
     *
     * @return the new session, or null when another device is already mirroring. Only one at a
     *     time: there is a single screen, and silently replacing whoever is on it would be a
     *     hostile way for a stranger on the same Wi-Fi to behave.
     */
    MirrorSession tryBeginSession(HandshakeCodec.Request request,
                                  MirrorSession.Transport transport);
}
