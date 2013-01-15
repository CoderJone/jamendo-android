/**
 * 
 */
package com.teleca.jamendo.media;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.w3c.dom.Document;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.teleca.jamendo.JamendoApplication;
import com.teleca.jamendo.activity.RadioActivity.RadioChannel;
import com.teleca.jamendo.api.Album;
import com.teleca.jamendo.api.Playlist;
import com.teleca.jamendo.api.Playlist.PlaylistPlaybackMode;
import com.teleca.jamendo.api.PlaylistEntry;
import com.teleca.jamendo.api.Track;
import com.teleca.jamendo.api.util.XMLUtil;

/**
 * @author Marcin Gil <marcin.gil@gmail.com>
 * 
 */
public class RadioPlayerEngineImpl implements PlayerEngine, OnPreparedListener {
    private final static String TAG = "Jamendo RadioPlayerEngineImpl";

    private static final String META_ARTIST = "artists";
    private static final String META_TRACK = "title";
    private static final String META_PING = "callmeback";
    private static final String META_COVER = "cover";
    private static final String META_START = "starttime";
    private static final String META_END = "endtime";

    private static final int META_RETRY_TIME = 5000;

    private static final int MSG_UPDATE_META = 0x0101;
    private static final int MSG_TRACK_CHANGE = 0x0110;

    private MediaPlayer mPlayer;
    private boolean mPlayerPreparing = false;
    private boolean mPlayerPlayAfterPrepare = true;
    private boolean mPlayerNewRadio = false;

    private RadioChannel mRadio;

    private PlayerEngineListener mPlayerEngineListener;

    private Handler mHandler = new PrivateHandler();
    private PlaylistEntry mCurrentEntry = null;
    private UpdateMetaTask mCurrentUpdateTask = null;

    @Override
    public void openPlaylist(Playlist playlist) {
        if (playlist == null || playlist.size() == 0) {
            return;
        }

        RadioChannel radio = RadioChannel.fromPlaylistEntry(playlist.getTrack(0));
        if (mRadio == null || radio != mRadio) {
            mPlayerNewRadio = true;
            mRadio = radio;
        }
    }

    @Override
    public Playlist getPlaylist() {
        Playlist plist = new Playlist();
        plist.addPlaylistEntry(mCurrentEntry);

        return plist;
    }

    @Override
    public void play() {
        if (mPlayerEngineListener.onTrackStart() == false) {
            return; // apparently sth prevents us from playing tracks
        }

        try {
            if ((mPlayer.isPlaying() && mPlayerNewRadio) || mPlayerPreparing) {
                mPlayer.stop();
                mPlayer.release();

                mHandler.removeMessages(MSG_UPDATE_META);
                mHandler.removeMessages(MSG_TRACK_CHANGE);

                mPlayer = new MediaPlayer();

                mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mPlayer.setOnPreparedListener(this);
            }
            Log.d(TAG, "startPlayback(): before setDataSource/prepareAsync()");

            mPlayerPreparing = true;
            mPlayerNewRadio = false;
            mPlayer.setDataSource(mRadio.getStreamUrl());
            mPlayer.prepareAsync();

            mHandler.sendEmptyMessage(MSG_UPDATE_META);

            Log.d(TAG, "startPlayback(): after prepareAsync");

            JamendoApplication.getInstance().setMyCurrentMedia(mPlayer);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isPlaying() {
        return mPlayer.isPlaying();
    }

    @Override
    public void stop() {
        if (mPlayer != null) {
            try {
                mPlayer.stop();
            } catch (IllegalStateException e) {
                // this may happen sometimes
            } finally {
                mPlayer.release();
                mPlayer = null;
            }
        }

        mHandler.removeMessages(MSG_UPDATE_META);
        mHandler.removeMessages(MSG_TRACK_CHANGE);
        
        if (mPlayerEngineListener != null) {
            mPlayerEngineListener.onTrackStop();
        }
    }

    @Override
    public void pause() {
        if (mPlayer != null) {
            // still preparing
            if (mPlayerPreparing) {
                mPlayerPlayAfterPrepare = false;
                return;
            }

            // check if we play, then pause
            if (mPlayer.isPlaying()) {
                mPlayer.pause();
                if (mPlayerEngineListener != null)
                    mPlayerEngineListener.onTrackPause();
                return;
            }
        }
    }

    @Override
    public void next() {
        // no action on radio stream
    }

    @Override
    public void prev() {
        // no action on radio stream
    }

    @Override
    public void prevList() {
        // no action on radio stream
    }

    @Override
    public void skipTo(int index) {
        // no action on radio stream
    }

    @Override
    public void setListener(PlayerEngineListener playerEngineListener) {
        mPlayerEngineListener = playerEngineListener;
    }

    @Override
    public void setPlaybackMode(PlaylistPlaybackMode aMode) {
        // no action on radio stream; always normal
    }

    @Override
    public PlaylistPlaybackMode getPlaybackMode() {
        return PlaylistPlaybackMode.NORMAL;
    }

    @Override
    public void forward(int time) {
        // no action on radio stream
    }

    @Override
    public void rewind(int time) {
        // no action on radio stream
    }

    @Override
    public void onPrepared(MediaPlayer player) {
        if (player != mPlayer) {
            throw new IllegalArgumentException();
        }

        mPlayerPreparing = false;
        if (mPlayerPlayAfterPrepare == false) {
            return;
        }

        mPlayerEngineListener.onTrackStart();

        Log.d(TAG, "onPrepared() before mPlayer.start");
        mPlayer.start();
        Log.d(TAG, "onPrepared() end");
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
                if (mCurrentUpdateTask != null) {
                    mCurrentUpdateTask.cancel(true);
                }
                mCurrentUpdateTask = new UpdateMetaTask(mHandler);
                mCurrentUpdateTask.execute(mRadio);
                break;
            }
            // track change because it is basically update of metadata + notification to listener
            // it is initiated from AsyncTask but must be performed on service's thread
            case MSG_TRACK_CHANGE: {
                if (msg.obj != null) {
                    PlaylistEntry entry = (PlaylistEntry) msg.obj;
                    mCurrentEntry = entry;
                    mPlayerEngineListener.onTrackChanged(entry);
                }
                break;
            }
            }
        }
    };

    /**
     * Asynchronous task to update radio channel metadata from network. First started upon play then queued in handler
     * with ping time read from XML.
     * 
     */
    private static class UpdateMetaTask extends AsyncTask<RadioChannel, Object, Integer> {
        private final WeakReference<Handler> mLocalHandler;

        public UpdateMetaTask(Handler handler) {
            mLocalHandler = new WeakReference<Handler>(handler);
        }

        @Override
        protected void onPostExecute(Integer result) {
            if (mLocalHandler.get() != null && result != -1) {
                mLocalHandler.get().sendEmptyMessageDelayed(MSG_UPDATE_META, result);
            }

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
                return META_RETRY_TIME;
            } catch (IOException e) {
                e.printStackTrace();
                // return 5s for next update
                return META_RETRY_TIME;
            }

            if (isCancelled()) {
                return -1;
            }

            doc.normalize();

            String artist = doc.getElementsByTagName(META_ARTIST).item(0).getTextContent();
            String track = doc.getElementsByTagName(META_TRACK).item(0).getTextContent();
            String cover = doc.getElementsByTagName(META_COVER).item(0).getTextContent();
            String start = doc.getElementsByTagName(META_START).item(0).getTextContent();
            String end = doc.getElementsByTagName(META_END).item(0).getTextContent();

            Log.d(JamendoApplication.TAG, "Radio meta: " + artist + " - " + track);
            Integer pingTime = Integer.valueOf(doc.getElementsByTagName(META_PING).item(0).getTextContent());

            Album a = new Album();
            a.setArtistName(artist);
            a.setImage(cover);

            Track t = new Track();
            t.setName(track);

            try {
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.sss");
                Date startdate = df.parse(start);
                Date enddate = df.parse(end);
                Log.d(JamendoApplication.TAG, "startdate " + startdate);
                Log.d(JamendoApplication.TAG, "enddate " + enddate);
                Log.d(JamendoApplication.TAG, "diff " + (enddate.getTime() - startdate.getTime()));
                t.setDuration((int) (enddate.getTime() - startdate.getTime()) / 1000);
            } catch (ParseException e) {
                e.printStackTrace();
            }

            PlaylistEntry p = new PlaylistEntry();

            p.setAlbum(a);
            p.setTrack(t);

            // send message to handler on UI thread
            if (mLocalHandler.get() != null && !isCancelled()) {
                Message m = mLocalHandler.get().obtainMessage(MSG_TRACK_CHANGE, p);
                m.sendToTarget();
            }

            return pingTime;
        }
    }
}
