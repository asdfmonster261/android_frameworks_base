/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

/**
 * The vendor extension some side-mounted fingerprint HALs attach to their IFingerprint
 * binder. Such a HAL pauses enrollment once too many captures in a row add nothing, and
 * waits to be resumed; nothing in the platform resumes it, so enrollment stops accepting
 * touches and only ends when the client times out or the user gives up.
 *
 * A paused HAL goes silent, so the trigger is a capture that produced no progress rather
 * than a count of captures: acquisition messages arrive before the enrollment result, and
 * cannot themselves say whether the touch was accepted.
 *
 * The interface is not part of AOSP, so it is called by transaction id rather than through
 * a generated stub. Every device without the extension gets a null binder here and none of
 * this runs.
 */
final class SidefpsExtension {
    private static final String TAG = "SidefpsExtension";

    private static final String DESCRIPTOR =
            "com.google.hardware.biometrics.sidefps.IFingerprintExt";

    // Ordinals follow the declaration order of the interface, after registerFeatureProvider.
    private static final int TRANSACTION_resumeEnroll = IBinder.FIRST_CALL_TRANSACTION + 1;

    // Long enough to tell a capture the HAL accepted from one it threw away. The enrollment
    // result follows its acquisition message within a millisecond or two, and touches are
    // hundreds of milliseconds apart, so there is a wide gap to sit in.
    private static final long SETTLE_MS = 400;

    private final IBinder mExt;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mResume = this::resumeEnroll;

    private SidefpsExtension(IBinder ext) {
        mExt = ext;
    }

    /** Returns null when this HAL exposes no such extension, which is the normal case. */
    static SidefpsExtension get(String halInstanceName) {
        final IBinder hal = ServiceManager.getService(
                "android.hardware.biometrics.fingerprint.IFingerprint/" + halInstanceName);
        if (hal == null) {
            return null;
        }
        final IBinder ext;
        try {
            ext = hal.getExtension();
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not read the HAL binder extension", e);
            return null;
        }
        if (ext == null) {
            return null;
        }
        Slog.i(TAG, "Fingerprint HAL exposes a binder extension, enroll will be resumable");
        return new SidefpsExtension(ext);
    }

    /** A capture happened. Resume unless the enrollment result says it counted. */
    void onAcquired() {
        mHandler.removeCallbacks(mResume);
        mHandler.postDelayed(mResume, SETTLE_MS);
    }

    /** The capture counted, so the HAL is still taking touches. */
    void onProgress() {
        mHandler.removeCallbacks(mResume);
    }

    /** Enrollment is over, one way or another. */
    void stop() {
        mHandler.removeCallbacks(mResume);
    }

    /** Lets a paused enrollment accept touches again. Idempotent when it is not paused. */
    private void resumeEnroll() {
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            mExt.transact(TRANSACTION_resumeEnroll, data, reply, 0);
            reply.readException();
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "resumeEnroll failed", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
