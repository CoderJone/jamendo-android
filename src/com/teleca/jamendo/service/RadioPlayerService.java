/*
 * Copyright (C) 2012 Marcin Gil <marcin.gil@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.teleca.jamendo.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.teleca.jamendo.JamendoApplication;
import com.teleca.jamendo.R;
import com.teleca.jamendo.activity.RadioActivity.RadioChannel;
import com.teleca.jamendo.activity.RadioPlayerActivity;
import com.teleca.jamendo.api.Playlist;
import com.teleca.jamendo.api.PlaylistEntry;
import com.teleca.jamendo.media.PlayerEngine;
import com.teleca.jamendo.media.PlayerEngineListener;
import com.teleca.jamendo.media.RadioPlayerEngineImpl;

public class RadioPlayerService extends Service {
    private static final String TAG = "Jamendo RadioPlayerService";

    public static final String ACTION_PLAY = "play";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_BIND = "bind_listener";

    public static final String EXTRA_PLAYLISTENTRY = "extra_playlistentry";

    private WifiManager mWifiManager;
    private WifiLock mWifiLock;
    private TelephonyManager mTelephonyManager;
    private PhoneStateListener mPhoneStateListener;
    private NotificationManager mNotificationManager = null;
    private PlayerEngine mPlayerEngine;
    private boolean mPlayerPreparing = false;

    private static final int PLAYING_NOTIFY_ID = 667667;

    private RadioChannel mRadio;
    private PlaylistEntry mCurrentEntry;
    private RadioPlayerBinder mBinder = new RadioPlayerBinder();

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(JamendoApplication.TAG, "Radio Player Service onCreate");

        mPlayerEngine = new RadioPlayerEngineImpl();
        mPlayerEngine.setListener(mLocalEngineListener);

        mTelephonyManager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        mPhoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                Log.e(JamendoApplication.TAG, "RadioPlayerService::onCallStateChanged");
                if (state == TelephonyManager.CALL_STATE_IDLE) {
                    // resume playback
                } else {
                    if (mPlayerEngine != null) {
                        mPlayerEngine.pause();
                    }
                }
            }

        };
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        mWifiLock = mWifiManager.createWifiLock(JamendoApplication.TAG);
        mWifiLock.setReferenceCounted(false);

        JamendoApplication.getInstance().setConcretePlayerEngine(mPlayerEngine);
        mRemoteEngineListener = JamendoApplication.getInstance().fetchPlayerEngineListener();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent == null) {
            throw new IllegalArgumentException("Empty intent in RadioPlayerService");
        }

        String action = intent.getAction();

        Log.i(JamendoApplication.TAG, "Radio Player Service onStart - " + action);

        if (action.equals(ACTION_STOP)) {
            this.stopSelf();
            return START_NOT_STICKY;
        }

        if (action.equals(ACTION_BIND)) {
            Log.d(TAG, "onStartCommand::bind listener");
            mRemoteEngineListener = JamendoApplication.getInstance().fetchPlayerEngineListener();
            return START_NOT_STICKY;
        }

        updatePlaylist(intent);

        if (action.equals(ACTION_PLAY)) {
            mPlayerEngine.play();

            return START_NOT_STICKY;
        }

        return 0;
    }

    /**
     * @param intent
     */
    private void updatePlaylist(Intent intent) {
        if (!intent.hasExtra(RadioPlayerActivity.EXTRA_RADIO)) {
            throw new IllegalArgumentException("RadioPlayerService started with empty radio");
        }

        mRadio = (RadioChannel) intent.getSerializableExtra(RadioPlayerActivity.EXTRA_RADIO);

        Playlist p = new Playlist();
        p.addPlaylistEntry(mRadio.asPlaylistEntry());

        mPlayerEngine.openPlaylist(p);
    }

    @Override
    public void onDestroy() {
        JamendoApplication.getInstance().setConcretePlayerEngine(null);
        mPlayerEngine.stop();
        mPlayerEngine = null;

        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    private void showNotifcation(PlaylistEntry entry) {
        String notificationMessage = entry.getAlbum().getArtistName() + " - " + entry.getTrack().getName();

        Notification notification = new Notification(R.drawable.stat_notify, notificationMessage,
                System.currentTimeMillis());

        Intent i = new Intent(this, RadioPlayerActivity.class);
        i.putExtra(EXTRA_PLAYLISTENTRY, mCurrentEntry);
        i.putExtra(RadioPlayerActivity.EXTRA_RADIO, mRadio);

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, i, 0);

        notification.setLatestEventInfo(this, "Jamendo Player", notificationMessage, contentIntent);
        notification.flags |= Notification.FLAG_ONGOING_EVENT;

        mNotificationManager.notify(PLAYING_NOTIFY_ID, notification);
    }

    /**
     * Hint: if necessary this can be extended to ArrayList of listeners in the future, though I do not expect that it
     * will be necessary
     */
    private PlayerEngineListener mRemoteEngineListener;

    /**
     * Sends notification to the status bar + passes other notifications to remote listeners
     */
    private PlayerEngineListener mLocalEngineListener = new PlayerEngineListener() {

        @Override
        public void onTrackBuffering(int percent) {
        }

        @Override
        public void onTrackChanged(PlaylistEntry playlistEntry) {
            showNotifcation(playlistEntry);

            if (mRemoteEngineListener != null) {
                mRemoteEngineListener.onTrackChanged(playlistEntry);
            }

            // Scrobbling
            boolean scrobblingEnabled = PreferenceManager.getDefaultSharedPreferences(RadioPlayerService.this)
                    .getBoolean("scrobbling_enabled", false);
            if (scrobblingEnabled) {
                // scrobblerMetaChanged();
            }
        }

        @Override
        public void onTrackProgress(int seconds) {
        }

        @Override
        public void onTrackStop() {
            // allow killing this service
            // NO-OP setForeground(false);
            mWifiLock.release();

            mNotificationManager.cancel(PLAYING_NOTIFY_ID);
            if (mRemoteEngineListener != null) {
                mRemoteEngineListener.onTrackStop();
            }
        }

        @Override
        public boolean onTrackStart() {
            // prevent killing this service
            // NO-OP setForeground(true);
            mWifiLock.acquire();

            if (mRemoteEngineListener != null) {
                if (!mRemoteEngineListener.onTrackStart())
                    return false;
            }

            boolean wifiOnlyMode = PreferenceManager.getDefaultSharedPreferences(RadioPlayerService.this).getBoolean(
                    "wifi_only", false);

            // wifi only mode
            if (wifiOnlyMode && !mWifiManager.isWifiEnabled()) {
                return false;
            }

            // roaming protection
            boolean roamingProtection = PreferenceManager.getDefaultSharedPreferences(RadioPlayerService.this)
                    .getBoolean("roaming_protection", true);
            if (!mWifiManager.isWifiEnabled()) {
                if (roamingProtection && mTelephonyManager.isNetworkRoaming())
                    return false;
            }

            return true;
        }

        @Override
        public void onTrackPause() {
            if (mRemoteEngineListener != null) {
                mRemoteEngineListener.onTrackPause();
            }
        }

        @Override
        public void onTrackStreamError() {
            if (mRemoteEngineListener != null) {
                mRemoteEngineListener.onTrackStreamError();
            }
        }

    };

    private class RadioPlayerBinder extends Binder {
        RadioPlayerService getService() {
            return RadioPlayerService.this;
        }
    }
}
