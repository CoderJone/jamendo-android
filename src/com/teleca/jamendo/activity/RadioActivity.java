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

import java.util.ArrayList;
import java.util.List;

import org.json.JSONException;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.gesture.GestureOverlayView;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.Window;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Gallery;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.ViewFlipper;

import com.teleca.jamendo.JamendoApplication;
import com.teleca.jamendo.R;
import com.teleca.jamendo.adapter.ArrayListAdapter;
import com.teleca.jamendo.adapter.RadioAdapter;
import com.teleca.jamendo.adapter.RadioChannelAdapter;
import com.teleca.jamendo.api.Radio;
import com.teleca.jamendo.api.WSError;
import com.teleca.jamendo.api.impl.JamendoGet2ApiImpl;
import com.teleca.jamendo.widget.FailureBar;
import com.teleca.jamendo.widget.ProgressBar;

/**
 * Radio streaming activity
 * 
 * Unfortunately due to no support for radio channels in current API
 * access to streaming and metadata is mostly hardcoded
 * 
 * @author Marcin Gil
 */
public class RadioActivity extends Activity {

    
    private final static String RADIO_STREAMING_URL = "http://streaming.radionomy.com/";
    private final static String RADIO_STREAMING_META = "http://www.jamendo.com/en/radios/0/rnproxy?mount=";

    /**
     * Internal enum type to represent a radio channel.
     * 
     * In future this might become non-enum type that retrieves data using JamApi.
     */
    public static enum RadioChannel {
        SONGWRITING("JamSongwriting", "Songwriting", R.drawable.radio_jamsongwriting),
        POP("JamPop", "Pop", R.drawable.radio_jampop),
        CLASSICAL("JamClassical", "Classical", R.drawable.radio_jamclassical),
        JAZZ("JamJazz", "Jazz", R.drawable.radio_jamjazz),
        WORLD("JamWorld", "World", R.drawable.radio_jamworld),
        HIPHOP("JamHipHop", "Hip Hop", R.drawable.radio_jamhiphop),
        ELECTRO("JamElectro", "Electronic", R.drawable.radio_jamelectro),
        ROCK("JamRock", "Rock", R.drawable.radio_jamrock),
        LOUNGE("JamLounge", "Lounge", R.drawable.radio_jamlounge),
        BESTOF("JamBestOf", "Best of", R.drawable.radio_jambestof);
        
        private String name;
        private String title;
        private int iconId;
        
        RadioChannel(String name, String title, int iconId) {
            this.name = name;
            this.title = title;
            this.iconId = iconId;
        }
        
        public String getName() {
            return name;
        }
        
        public String getTitle() {
            return title;
        }
        
        public int getIconId() {
            return iconId;
        }
        
        public String getStreamUrl() {
            return RADIO_STREAMING_URL + name;
        }
        
        public String getMetaUrl() {
            return RADIO_STREAMING_META + name;
        }
    }
    
    private static final int VIEWFLIPPER_PROGRESS = 0;
    private static final int VIEWFLIPPER_GALLERY = 1;
    private static final int VIEWFLIPPER_FAILURE = 2;
    
	/**
	 * Launch this Activity from the outside
	 *
	 * @param c context from which Activity should be started
	 */
	public static void launch(Context c){
		Intent intent = new Intent(c, RadioActivity.class);
		c.startActivity(intent);
	}

	private ListView mRadioListView;
	private ViewFlipper mViewFlipper;
	private GestureOverlayView mGestureOverlayView;
	private Gallery mGallery;
	private ProgressBar mProgressBar;
	private FailureBar mFailureBar;
	private ArrayListAdapter<RadioChannel> mRadioAdapter;
	
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.radio);

		mRadioListView = (ListView)findViewById(R.id.HomeListView);
		mRadioListView.setOnItemClickListener(mRadioListListener);

		mRadioAdapter = new RadioChannelAdapter(this);
		mRadioAdapter.setList(RadioChannel.values());
		
		mRadioListView.setAdapter(mRadioAdapter);
		
//		mGallery = (Gallery)findViewById(R.id.Gallery);
        mProgressBar = (ProgressBar)findViewById(R.id.ProgressBar);
        mFailureBar = (FailureBar)findViewById(R.id.FailureBar);
        mViewFlipper = (ViewFlipper)findViewById(R.id.ViewFlipper);

//		mGestureOverlayView = (GestureOverlayView) findViewById(R.id.gestures);
//		mGestureOverlayView.addOnGesturePerformedListener(JamendoApplication
//				.getInstance().getPlayerGestureHandler());

        mViewFlipper.setDisplayedChild(VIEWFLIPPER_GALLERY);
	}

	@Override
	protected void onResume() {
		super.onResume();
//		boolean gesturesEnabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("gestures", true);
//		mGestureOverlayView.setEnabled(gesturesEnabled);
	}

	/**
	 * Launch radio
	 */
	private OnItemClickListener mRadioListListener = new OnItemClickListener(){

		@Override
		public void onItemClick(AdapterView<?> arg0, View arg1, int position,
				long arg3) {
			RadioChannel radio = (RadioChannel)mRadioAdapter.getItem(position);
			RadioPlayerActivity.launch(RadioActivity.this, radio);
		}
	};
}
