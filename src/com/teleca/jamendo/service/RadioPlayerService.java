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

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.w3c.dom.Document;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.teleca.jamendo.JamendoApplication;
import com.teleca.jamendo.R;
import com.teleca.jamendo.activity.RadioActivity.RadioChannel;
import com.teleca.jamendo.activity.RadioPlayerActivity;
import com.teleca.jamendo.api.Album;
import com.teleca.jamendo.api.PlaylistEntry;
import com.teleca.jamendo.api.Track;
import com.teleca.jamendo.api.util.XMLUtil;
import com.teleca.jamendo.media.PlayerEngineListener;

public class RadioPlayerService extends Service implements OnPreparedListener {
    public static final String ACTION_PLAY = "play";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_BIND = "bind_listener";

    private static final String META_ARTIST = "artists";
    private static final String META_TRACK = "title";
    private static final String META_PING = "callmeback";
    private static final String META_COVER = "cover";
    private static final String META_START = "starttime";
    private static final String META_END = "endtime";

    private static final int MSG_UPDATE_META = 0x0101;
    private static final int MSG_TRACK_CHANGE = 0x0110;

    private WifiManager mWifiManager;
    private WifiLock mWifiLock;
    private TelephonyManager mTelephonyManager;
    private PhoneStateListener mPhoneStateListener;
    private NotificationManager mNotificationManager = null;
    private MediaPlayer mPlayer = new MediaPlayer();

    private static final int PLAYING_NOTIFY_ID = 667667;

    private RadioChannel mRadio;
    private Handler mHandler = new PrivateHandler();
    private RadioPlayerBinder mBinder = new RadioPlayerBinder();

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(JamendoApplication.TAG, "Radio Player Service onCreate");

        prepareMediaPlayer();

        mTelephonyManager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        mPhoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                Log.e(JamendoApplication.TAG, "onCallStateChanged");
                if (state == TelephonyManager.CALL_STATE_IDLE) {
                    // resume playback
                } else {
                    if (mPlayer != null) {
                        mPlayer.pause();
                    }
                }
            }

        };
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        mWifiLock = mWifiManager.createWifiLock(JamendoApplication.TAG);
        mWifiLock.setReferenceCounted(false);
    }

    private void prepareMediaPlayer() {
        mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mPlayer.setOnPreparedListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent == null) {
            throw new IllegalArgumentException("Empty intent in RadioPlayerService");
        }

        String action = intent.getAction();
        mRadio = (RadioChannel) intent.getSerializableExtra(RadioPlayerActivity.EXTRA_RADIO);

        Log.i(JamendoApplication.TAG, "Radio Player Service onStart - " + action);

        if (action.equals(ACTION_STOP)) {
            stopPlayback();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        if (action.equals(ACTION_BIND)) {
            mRemoteEngineListener = JamendoApplication.getInstance().getRadioPlayerEngineListener();
            return START_NOT_STICKY;
        }

        if (action.equals(ACTION_PLAY)) {
            if (!intent.hasExtra(RadioPlayerActivity.EXTRA_RADIO)) {
                throw new IllegalArgumentException("RadioPlayerService EXTRA_RADIO empty on ACTION_PLAY");
            }
            startPlayback();
            return START_STICKY;
        }

        return 0;
    }

    /**
     * Private handler implementation.
     * 
     * Responsible for handling radio meta update events
     * 
     */
    private class PrivateHandler extends Handler {
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_UPDATE_META: {
                new UpdateMetaTask().execute(mRadio);
                break;
            }
            case MSG_TRACK_CHANGE: {
                if (msg.obj != null) {
                    PlaylistEntry entry = (PlaylistEntry) msg.obj;
                    mLocalEngineListener.onTrackChanged(entry);
                }
                break;
            }
            }
        }
    };

    private class UpdateMetaTask extends AsyncTask<RadioChannel, Object, Integer> {
        @Override
        protected void onPostExecute(Integer result) {
            mHandler.sendEmptyMessageDelayed(MSG_UPDATE_META, result);
            super.onPostExecute(result);
        }

        @Override
        protected Integer doInBackground(RadioChannel... params) {
            HttpClient client = new DefaultHttpClient();
            HttpGet get = new HttpGet(params[0].getMetaUrl());
            get.addHeader("Accept", "application/xml");
            get.addHeader("Content-Type", "application/xml");
            HttpResponse responsePost = null;
            Document doc = null;
            try {
                responsePost = client.execute(get);
                HttpEntity resEntity = responsePost.getEntity();
                doc = XMLUtil.stringToDocument(EntityUtils.toString(resEntity));
            } catch (ClientProtocolException e) {
                e.printStackTrace();
                // return 5s for next update
                return 5000;
            } catch (IOException e) {
                e.printStackTrace();
                // return 5s for next update
                return 5000;
            }

            doc.normalize();

            String artist = doc.getElementsByTagName(META_ARTIST).item(0).getTextContent();
            String track = doc.getElementsByTagName(META_TRACK).item(0).getTextContent();
            String cover = doc.getElementsByTagName(META_COVER).item(0).getTextContent();

            Log.d(JamendoApplication.TAG, "Radio meta: " + artist + " - " + track);
            Integer pingTime = Integer.valueOf(doc.getElementsByTagName(META_PING).item(0).getTextContent());

            Album a = new Album();
            a.setArtistName(artist);
            a.setImage(cover);

            Track t = new Track();
            t.setName(track);

            PlaylistEntry p = new PlaylistEntry();

            p.setAlbum(a);
            p.setTrack(t);

            // send message to handler on UI thread
            Message m = mHandler.obtainMessage(MSG_TRACK_CHANGE, p);
            m.sendToTarget();
            
            return pingTime;
        }
    }

    private void stopPlayback() {
        Log.d(JamendoApplication.TAG, "RadioPlayerService::stopPlayback()");

        mPlayer.stop();

        mWifiLock.release();

        mNotificationManager.cancel(PLAYING_NOTIFY_ID);
    }

    private void startPlayback() {
        Log.d(JamendoApplication.TAG, "RadioPlayerService::startPlayback()");

        mWifiLock.acquire();

        boolean wifiOnlyMode = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("wifi_only", false);

        // wifi only mode
        if (wifiOnlyMode && !mWifiManager.isWifiEnabled()) {
            return;
        }

        // roaming protection
        boolean roamingProtection = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                "roaming_protection", true);
        if (!mWifiManager.isWifiEnabled()) {
            if (roamingProtection && mTelephonyManager.isNetworkRoaming())
                return;
        }

        try {
            if (mPlayer.isPlaying()) {
                return;
            }
            mPlayer.setDataSource(mRadio.getStreamUrl());
            mPlayer.prepareAsync();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        stopPlayback();

        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    private void showNotifcation(String artist, String track) {
        String notificationMessage = artist + " - " + track;

        Notification notification = new Notification(R.drawable.stat_notify, notificationMessage,
                System.currentTimeMillis());

        PendingIntent contentIntent = PendingIntent
                .getActivity(this, 0, new Intent(this, RadioPlayerActivity.class), 0);

        notification.setLatestEventInfo(this, "Jamendo Player", notificationMessage, contentIntent);
        notification.flags |= Notification.FLAG_ONGOING_EVENT;

        mNotificationManager.notify(PLAYING_NOTIFY_ID, notification);
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (mp != mPlayer) {
            throw new IllegalArgumentException();
        }

        // mPlayer.start();
        mHandler.sendEmptyMessage(MSG_UPDATE_META);
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
            showNotifcation(playlistEntry.getAlbum().getArtistName(), playlistEntry.getTrack().getName());

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
            stopPlayback();

            if (mRemoteEngineListener != null) {
                mRemoteEngineListener.onTrackStop();
            }
        }

        @Override
        public boolean onTrackStart() {
            startPlayback();

            if (mPlayer.isPlaying()) {
                mRemoteEngineListener.onTrackStart();
                return true;
            }

            return false;
        }

        @Override
        public void onTrackPause() {
            if (mPlayer.isPlaying()) {
                stopPlayback();
            }

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
