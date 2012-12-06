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
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import org.w3c.dom.Document;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaRecorder.AudioSource;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.webkit.URLUtil;
import android.widget.MediaController.MediaPlayerControl;

import com.teleca.jamendo.JamendoApplication;
import com.teleca.jamendo.R;
import com.teleca.jamendo.activity.RadioActivity.RadioChannel;
import com.teleca.jamendo.activity.RadioPlayerActivity;
import com.teleca.jamendo.api.util.XMLUtil;

public class RadioPlayerService extends Service implements OnPreparedListener {
    public static final String ACTION_PLAY = "play";
    public static final String ACTION_STOP = "stop";

    private static final String META_ARTIST = "";
    private static final String META_TRACK = "";
    private static final String META_PING = "";
    
    private static final int MSG_UPDATE_META = 0x0101;
    
    private WifiManager mWifiManager;
    private WifiLock mWifiLock;
    private TelephonyManager mTelephonyManager;
    private PhoneStateListener mPhoneStateListener;
    private NotificationManager mNotificationManager = null;
    private MediaPlayer mPlayer = new MediaPlayer();

    private static final int PLAYING_NOTIFY_ID = 667667;
    
    private RadioChannel mRadio;
    private LooperThread mLooper;
    
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
        
        mLooper = new LooperThread(this);
        mLooper.start();
    }

    private void prepareMediaPlayer() {
        mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mPlayer.setOnPreparedListener(this);
    }

    static class LooperThread extends Thread {
        private WeakReference<RadioPlayerService> mService;
        private PrivateHandler mHandler;
        
        static class PrivateHandler extends Handler {
            
        }
        
        public LooperThread(RadioPlayerService service) {
            mService = new WeakReference<RadioPlayerService>(service);
        }
        
        public void run() {
            Looper.prepare();

            mHandler = new PrivateHandler() {
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                    case MSG_UPDATE_META: {
                        mService.get().updateChannelMetadata();
                    }
                    }
                }
            };

            Looper.loop();
        }
        
        public Handler getHandler() {
            return mHandler;
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent == null || !intent.hasExtra(RadioPlayerActivity.EXTRA_RADIO)) {
            throw new IllegalArgumentException("Empty intent in RadioPlayerService");
        }

        mRadio = (RadioChannel)intent.getSerializableExtra(RadioPlayerActivity.EXTRA_RADIO);
        String action = intent.getAction();

        Log.i(JamendoApplication.TAG, "Radio Player Service onStart - " + action);

        if (action.equals(ACTION_STOP)) {
            stopPlayback();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        if (action.equals(ACTION_PLAY)) {
            startPlayback();
            return START_STICKY;
        }
        
        return 0;
    }
    
    
    
    void updateChannelMetadata() {
        try {
            URL u = new URL(mRadio.getMetaUrl());
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            String xml = (String) conn.getContent();
            Document doc = XMLUtil.stringToDocument(xml);
            
            String artist = doc.getElementsByTagName(META_ARTIST).item(0).getTextContent();
            String track = doc.getElementsByTagName(META_TRACK).item(0).getTextContent();
            Integer pingTime = Integer.valueOf(doc.getElementsByTagName(META_PING).item(0).getTextContent());
            
            mLooper.getHandler().sendEmptyMessageDelayed(MSG_UPDATE_META, pingTime);
            
            showNotifcation(artist, track);
            
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopPlayback() {
        mPlayer.stop();
        
        mWifiLock.release();
        
        mNotificationManager.cancel(PLAYING_NOTIFY_ID);
    }

    private void startPlayback() {
        mWifiLock.acquire();
        
        boolean wifiOnlyMode = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("wifi_only", false);

        // wifi only mode
        if(wifiOnlyMode && !mWifiManager.isWifiEnabled()){
            return;
        }

        // roaming protection
        boolean roamingProtection = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("roaming_protection", true);
        if(!mWifiManager.isWifiEnabled()){
            if(roamingProtection && mTelephonyManager.isNetworkRoaming())
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
        // TODO Auto-generated method stub
        return null;
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
        
        mPlayer.start();
    }

}
