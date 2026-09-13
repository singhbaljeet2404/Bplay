package com.bplay.sender;

import android.media.projection.MediaProjection;

/**
 * Internal audio capture, behind an interface that carries no API-level requirement.
 *
 * <p>The only implementation needs Android 10, and referring to a {@code @RequiresApi} type
 * anywhere outside a version check risks a verification failure when the class is first touched on
 * an older device. Holding the reference as this interface keeps that type confined to the one
 * branch that has already checked the version.
 */
interface AudioSource {

    /** @return true if capture actually started; false means carry on without sound */
    boolean start(MediaProjection projection);

    void stop();
}
