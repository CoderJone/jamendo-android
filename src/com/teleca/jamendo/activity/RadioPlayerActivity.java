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

package com.teleca.jamendo.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils.TruncateAt;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.teleca.jamendo.JamendoApplication;
import com.teleca.jamendo.R;
import com.teleca.jamendo.activity.RadioActivity.RadioChannel;
import com.teleca.jamendo.api.PlaylistEntry;
import com.teleca.jamendo.media.PlayerEngine;
import com.teleca.jamendo.media.PlayerEngineListener;
import com.teleca.jamendo.media.RadioPlayerEngineImpl;
import com.teleca.jamendo.service.PlayerService;
import com.teleca.jamendo.service.RadioPlayerService;
import com.teleca.jamendo.util.Helper;
import com.teleca.jamendo.util.SeekToMode;
import com.teleca.jamendo.widget.ReflectableLayout;
import com.teleca.jamendo.widget.ReflectiveSurface;
import com.teleca.jamendo.widget.RemoteImageView;

/**
 * Play Jamendo's icecast streams. Simpler than normal player as there are no playlists, no skip/next/prev functionality
 * 
 * @author Marcin Gil
 */
public class RadioPlayerActivity extends Activity {
    public static String EXTRA_RADIO = "RadioChannelExtra";

    PlayerEngine getPlayerEngine() {
        return JamendoApplication.getInstance().getPlayerEngineInterface();
    };

    private RadioChannel mRadioChannel;
    private PlaylistEntry mCurrentTrack;

    // XML layout

    private TextView mArtistTextView;
    private TextView mSongTextView;
    private TextView mCurrentTimeTextView;
    private TextView mTotalTimeTextView;
    // private ProgressBar mProgressBar;

    private ImageButton mPlayImageButton;
    private ImageButton mStopImageButton;

    private RemoteImageView mCoverImageView;

    private Animation mFadeInAnimation;
    private Animation mFadeOutAnimation;

    private ReflectableLayout mReflectableLayout;
    private ReflectiveSurface mReflectiveSurface;

    private String mBetterRes;

    SeekToMode seekToMode;
    Handler mHandlerOfFadeOutAnimation;
    Runnable mRunnableOfFadeOutAnimation;

    private AlertDialog mLoadingDialog = null;

    public static void launch(Context c, RadioChannel channel) {
        Intent intent = new Intent(c, RadioPlayerActivity.class);
        intent.putExtra(EXTRA_RADIO, channel);
        c.startActivity(intent);
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(JamendoApplication.TAG, "RadioPlayerActivity.onCreate");

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.radio_player);

        mRadioChannel = (RadioChannel) getIntent().getSerializableExtra(EXTRA_RADIO);

        // XML binding
        mBetterRes = getResources().getString(R.string.better_res);

        mArtistTextView = (TextView) findViewById(R.id.ArtistTextView);
        mSongTextView = (TextView) findViewById(R.id.SongTextView);
        // AutoScrolling of long song titles
        mSongTextView.setEllipsize(TruncateAt.MARQUEE);
        mSongTextView.setHorizontallyScrolling(true);
        mSongTextView.setSelected(true);

        mCurrentTimeTextView = (TextView) findViewById(R.id.CurrentTimeTextView);
        mTotalTimeTextView = (TextView) findViewById(R.id.TotalTimeTextView);

        mCoverImageView = (RemoteImageView) findViewById(R.id.CoverImageView);
        mCoverImageView.setOnClickListener(mCoverOnClickListener);
        mCoverImageView.setDefaultImage(R.drawable.no_cd_300);

        // mProgressBar = (ProgressBar)findViewById(R.id.ProgressBar);

        mReflectableLayout = (ReflectableLayout) findViewById(R.id.ReflectableLayout);
        mReflectiveSurface = (ReflectiveSurface) findViewById(R.id.ReflectiveSurface);

        if (mReflectableLayout != null && mReflectiveSurface != null) {
            mReflectableLayout.setReflectiveSurface(mReflectiveSurface);
            mReflectiveSurface.setReflectableLayout(mReflectableLayout);
        }

        // used for Fade Out Animation handle control
        mHandlerOfFadeOutAnimation = new Handler();
        mRunnableOfFadeOutAnimation = new Runnable() {
            public void run() {
                if (mFadeInAnimation.hasEnded())
                    mPlayImageButton.startAnimation(mFadeOutAnimation);
            }

        };

        mPlayImageButton = (ImageButton) findViewById(R.id.PlayImageButton);
        mPlayImageButton.setOnClickListener(mPlayOnClickListener);

        mStopImageButton = (ImageButton) findViewById(R.id.StopImageButton);
        mStopImageButton.setOnClickListener(mStopOnClickListener);

        mFadeInAnimation = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        mFadeInAnimation.setAnimationListener(new AnimationListener() {

            @Override
            public void onAnimationEnd(Animation animation) {
                mHandlerOfFadeOutAnimation.removeCallbacks(mRunnableOfFadeOutAnimation);
                mHandlerOfFadeOutAnimation.postDelayed(mRunnableOfFadeOutAnimation, 7500);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
                // nothing here
            }

            @Override
            public void onAnimationStart(Animation animation) {
                setMediaVisible();
            }

        });

        mFadeOutAnimation = AnimationUtils.loadAnimation(this, R.anim.fade_out);
        mFadeOutAnimation.setAnimationListener(new AnimationListener() {

            @Override
            public void onAnimationEnd(Animation animation) {
                setMediaGone();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
                // nothing here
            }

            @Override
            public void onAnimationStart(Animation animation) {
                setFadeOutAnimation();
            }

        });

        // if entry's not null then we're started from service and already playing
        PlaylistEntry entry = (PlaylistEntry) getIntent().getSerializableExtra(RadioPlayerService.EXTRA_PLAYLISTENTRY);
        if (entry != null) {
            setupFromEntry(entry);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(JamendoApplication.TAG, "RadioPlayerActivity.onResume");

        PlayerEngine pe = JamendoApplication.getInstance().getConcretePlayerEngine();
        if (!(pe instanceof RadioPlayerEngineImpl)) {
            JamendoApplication.getInstance().setConcretePlayerEngine(null);
        }

        JamendoApplication.getInstance().setPlayerEngineListener(mPlayerEngineListener);

        if (mCurrentTrack == null) {
            startPlayback();
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        JamendoApplication.getInstance().setPlayerEngineListener(null);
        bindListener();

        Log.i(JamendoApplication.TAG, "RadioPlayerActivity.onPause");
    }

    /**
     * Makes 4-way media visible
     */
    private void setMediaVisible() {
        mPlayImageButton.setVisibility(View.VISIBLE);
        mStopImageButton.setVisibility(View.VISIBLE);
    }

    /**
     * Makes 4-way media gone
     */
    private void setMediaGone() {
        mPlayImageButton.setVisibility(View.GONE);
        mStopImageButton.setVisibility(View.GONE);
    }

    /**
     * Sets fade out animation to 4-way media
     */
    private void setFadeOutAnimation() {
        mPlayImageButton.setAnimation(mFadeOutAnimation);
        mStopImageButton.setAnimation(mFadeOutAnimation);
    }

    /**
     * Sets fade out animation to 4-way media
     */
    private void setFadeInAnimation() {
        mPlayImageButton.setAnimation(mFadeInAnimation);
        mStopImageButton.setAnimation(mFadeInAnimation);
    }

    /**
     * Launches fade in/out sequence
     */
    private OnClickListener mCoverOnClickListener = new OnClickListener() {

        @Override
        public void onClick(View v) {

            if (mPlayImageButton.getVisibility() == View.GONE) {
                setMediaVisible();
                setFadeInAnimation();
                mPlayImageButton.startAnimation(mFadeInAnimation);
            }
        }

    };

    /**
     * on click play/pause and open playlist if necessary
     */
    private OnClickListener mPlayOnClickListener = new OnClickListener() {

        @Override
        public void onClick(View v) {
            Log.d(JamendoApplication.TAG, "RadioPlayerActivity::PlayOnClick");

            if (getPlayerEngine().isPlaying()) {
                getPlayerEngine().pause();
            } else {
                startPlayback();
                // getPlayerEngine().play();
            }
        }

    };

    /**
     * stop button action
     */
    private OnClickListener mStopOnClickListener = new OnClickListener() {

        @Override
        public void onClick(View v) {
            Log.d(JamendoApplication.TAG, "RadioPlayerActivity::StopOnClick");
            stopPlayback();
        }

    };

    /**
     * PlayerEngineListener implementation, manipulates UI
     */
    private PlayerEngineListener mPlayerEngineListener = new PlayerEngineListener() {

        @Override
        public void onTrackChanged(PlaylistEntry playlistEntry) {
            setupFromEntry(playlistEntry);

            if (mLoadingDialog != null && mLoadingDialog.isShowing()) {
                mLoadingDialog.dismiss();
            }
        }

        @Override
        public void onTrackProgress(int seconds) {
            mCurrentTimeTextView.setText(Helper.secondsToString(seconds));
            // mProgressBar.setProgress(seconds);
        }

        @Override
        public void onTrackBuffering(int percent) {
            Log.d(JamendoApplication.TAG, "RadioPlayerActivity::onTrackBuffering is " + Integer.toString(percent));
            
            // int secondaryProgress = (int) (((float)percent/100)*mProgressBar.getMax());
            // mProgressBar.setSecondaryProgress(secondaryProgress);
            if (mLoadingDialog != null && mLoadingDialog.isShowing() && percent >= 100) {
                mLoadingDialog.dismiss();
            }
        }

        @Override
        public void onTrackStop() {
            mPlayImageButton.setImageResource(R.drawable.player_play_light);
            mLoadingDialog = null;
        }

        @Override
        public boolean onTrackStart() {
            Log.d(JamendoApplication.TAG, "RadioPlayerActivity::onTrackStart()");

            mPlayImageButton.setImageResource(R.drawable.player_pause_light);

            if (mLoadingDialog == null) {
                mLoadingDialog = new ProgressDialog(RadioPlayerActivity.this);
                mLoadingDialog.setCancelable(true);
                mLoadingDialog.setOnCancelListener(new OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        stopPlayback();
                        RadioPlayerActivity.this.finish();
                    }
                });
                mLoadingDialog.setTitle(R.string.label_loading_radio_channel);
                mLoadingDialog.setMessage(getResources().getText(R.string.message_loading_radio_channel));
                mLoadingDialog.show();
            }
            
            return true;
        }

        @Override
        public void onTrackPause() {
            mPlayImageButton.setImageResource(R.drawable.player_play_light);
        }

        @Override
        public void onTrackStreamError() {
            Toast.makeText(RadioPlayerActivity.this, R.string.stream_error, Toast.LENGTH_LONG).show();
        }

    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void albumClickHandler(View target) {
        AlbumActivity.launch(this, getPlayerEngine().getPlaylist().getSelectedTrack().getAlbum());
    }

    public void artistClickHandler(View target) {
        ArtistActivity.launch(this, getPlayerEngine().getPlaylist().getSelectedTrack().getAlbum().getArtistName());
    }

    public void onStartSeekToProcess() {
        mHandlerOfFadeOutAnimation.removeCallbacks(mRunnableOfFadeOutAnimation);
    }

    public void onFinishSeekToProcess() {
        mHandlerOfFadeOutAnimation.removeCallbacks(mRunnableOfFadeOutAnimation);
        mHandlerOfFadeOutAnimation.postDelayed(mRunnableOfFadeOutAnimation, 7500);
    }

    /**
     * Order the service to start playback Shows loading dialog which, if canceled, will also finish activity
     */
    private void startPlayback() {
        bindListener();

        Intent i = new Intent(RadioPlayerActivity.this, RadioPlayerService.class);
        i.setAction(PlayerService.ACTION_PLAY);
        i.putExtra(EXTRA_RADIO, mRadioChannel);
        startService(i);
    }

    /**
     * Order the service to bind to listener
     */
    private void bindListener() {
        Intent i = new Intent(RadioPlayerActivity.this, RadioPlayerService.class);
        i.setAction(PlayerService.ACTION_BIND_LISTENER);
        i.putExtra(EXTRA_RADIO, mRadioChannel);
        startService(i);
    }

    /**
     * Order the service to stop playback
     */
    private void stopPlayback() {
        Intent i = new Intent(RadioPlayerActivity.this, RadioPlayerService.class);
        i.setAction(PlayerService.ACTION_STOP);
        i.putExtra(EXTRA_RADIO, mRadioChannel);
        startService(i);
    }

    /**
     * Initialize views from playlist
     * 
     * @param playlistEntry
     */
    private void setupFromEntry(PlaylistEntry playlistEntry) {
        mCurrentTrack = playlistEntry;
        mArtistTextView.setText(playlistEntry.getAlbum().getArtistName());
        mSongTextView.setText(playlistEntry.getTrack().getName());
        // mCurrentTimeTextView.setText(Helper.secondsToString(0));
        mTotalTimeTextView.setText(Helper.secondsToString(playlistEntry.getTrack().getDuration()));
        mCoverImageView.setImageUrl(playlistEntry.getAlbum().getImage().replaceAll("1.100.jpg", mBetterRes)); // Get
                                                                                                              // higher
                                                                                                              // resolution
                                                                                                              // image
                                                                                                              // 300x300
        mCoverImageView.performClick();

        if (getPlayerEngine() != null) {
            if (getPlayerEngine().isPlaying()) {
                mPlayImageButton.setImageResource(R.drawable.player_pause_light);
            } else {
                mPlayImageButton.setImageResource(R.drawable.player_play_light);
            }
        }
    }
}
