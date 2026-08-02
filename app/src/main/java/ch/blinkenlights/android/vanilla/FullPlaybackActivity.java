/*
 * Copyright (C) 2012 Christopher Eby <kreed@kreed.org>
 * Copyright (C) 2016-2019 Adrian Ulrich <adrian@blinkenlights.ch>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package ch.blinkenlights.android.vanilla;

import ch.blinkenlights.android.medialibrary.MediaMetadataExtractor;

import java.util.ArrayList;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import androidx.palette.graphics.Palette;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.DialogInterface;

/**
 * The primary playback screen with playback controls and large cover display.
 */
public class FullPlaybackActivity extends SlidingPlaybackActivity
	implements View.OnLongClickListener
{
	public static final int DISPLAY_INFO_OVERLAP = 0;
	public static final int DISPLAY_INFO_BELOW = 1;
	public static final int DISPLAY_INFO_WIDGETS = 2;
	public static final int DISPLAY_INFO_FULLSCREEN = 3;

	private TextView mOverlayText;

	private TableLayout mInfoTable;
	private TextView mQueuePosView;

	private TextView mTitle;
	private TextView mAlbum;
	private TextView mArtist;

	/**
	 * True if the controls are visible (play, next, seek bar, etc).
	 */
	private boolean mControlsVisible;
	/**
	 * True if the extra info is visible.
	 */
	private boolean mExtraInfoVisible;

	/**
	 * The current display mode, which determines layout and cover render style.
	 */
	private int mDisplayMode;

	private Action mCoverPressAction;
	private Action mCoverLongPressAction;

	/**
	 * The currently playing song.
	 */
	private Song mCurrentSong;

	private String mGenre;
	private TextView mGenreView;
	private String mTrack;
	private TextView mTrackView;
	private String mYear;
	private TextView mYearView;
	private String mComposer;
	private TextView mComposerView;
	private String mPath;
	private TextView mPathView;
	private String mFormat;
	private TextView mFormatView;
	private String mReplayGain;
	private TextView mReplayGainView;
	private MenuItem mFavorites;
	/**
	 * Whether this instance was launched while the device was locked. See
	 * onCreate() for how this is determined.
	 */
	private boolean mOpenedFromLockScreen;

	@Override
	public void onCreate(Bundle icicle)
	{
		ThemeHelper.setTheme(this, R.style.Playback);
		super.onCreate(icicle);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			setShowWhenLocked(true);
			setTurnScreenOn(true);
		} else {
			getWindow().addFlags(
				WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
				| WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
				| WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}

		KeyguardManager keyguardManager = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
		mOpenedFromLockScreen = keyguardManager != null && keyguardManager.isKeyguardLocked();

		setTitle(R.string.playback_view);

		SharedPreferences settings = SharedPrefHelper.getSettings(this);
		int displayMode = Integer.parseInt(settings.getString(PrefKeys.DISPLAY_MODE, PrefDefaults.DISPLAY_MODE));
		mDisplayMode = displayMode;

		int layout = R.layout.full_playback;
		int coverStyle;

		switch (displayMode) {
		default:
			Log.w("VanillaMusic", "Invalid display mode given. Defaulting to widget mode.");
		case DISPLAY_INFO_WIDGETS:
			coverStyle = CoverBitmap.STYLE_NO_INFO;
			layout = R.layout.full_playback_alt;
			break;
		case DISPLAY_INFO_OVERLAP:
			coverStyle = CoverBitmap.STYLE_OVERLAPPING_BOX;
			break;
		case DISPLAY_INFO_BELOW:
			coverStyle = CoverBitmap.STYLE_INFO_BELOW;
			break;
		case DISPLAY_INFO_FULLSCREEN:
			coverStyle = mOpenedFromLockScreen ? CoverBitmap.STYLE_FULLSCREEN_CROP : CoverBitmap.STYLE_FULLSCREEN;
			layout = R.layout.full_playback_fullscreen;
			break;
		}

		setContentView(layout);

		if (displayMode == DISPLAY_INFO_FULLSCREEN) {
			setupEdgeToEdge();
		}

		CoverView coverView = (CoverView)findViewById(R.id.cover_view);
		coverView.setup(mLooper, this, coverStyle);
		coverView.setOnClickListener(this);
		coverView.setOnLongClickListener(this);
		mCoverView = coverView;

		TableLayout table = (TableLayout)findViewById(R.id.info_table);
		if (table != null) {
			table.setOnClickListener(this);
			table.setOnLongClickListener(this);
			mInfoTable = table;
		}

		mTitle = (TextView)findViewById(R.id.title);
		mAlbum = (TextView)findViewById(R.id.album);
		mArtist = (TextView)findViewById(R.id.artist);

		mQueuePosView = (TextView)findViewById(R.id.queue_pos);

		mGenreView = (TextView)findViewById(R.id.genre);
		mTrackView = (TextView)findViewById(R.id.track);
		mYearView = (TextView)findViewById(R.id.year);
		mComposerView = (TextView)findViewById(R.id.composer);
		mPathView = (TextView)findViewById(R.id.path);
		mFormatView = (TextView)findViewById(R.id.format);
		mReplayGainView = (TextView)findViewById(R.id.replaygain);

		bindControlButtons();

		setControlsVisible(settings.getBoolean(PrefKeys.VISIBLE_CONTROLS, PrefDefaults.VISIBLE_CONTROLS));
		setExtraInfoVisible(settings.getBoolean(PrefKeys.VISIBLE_EXTRA_INFO, PrefDefaults.VISIBLE_EXTRA_INFO));
	}

	@Override
	public void onStart()
	{
		super.onStart();

		SharedPreferences settings = SharedPrefHelper.getSettings(this);
		if (mDisplayMode != Integer.parseInt(settings.getString(PrefKeys.DISPLAY_MODE, PrefDefaults.DISPLAY_MODE))) {
			finish();
			startActivity(new Intent(this, FullPlaybackActivity.class));
		}

		mCoverPressAction = Action.getAction(settings, PrefKeys.COVER_PRESS_ACTION, PrefDefaults.COVER_PRESS_ACTION);
		mCoverLongPressAction = Action.getAction(settings, PrefKeys.COVER_LONGPRESS_ACTION, PrefDefaults.COVER_LONGPRESS_ACTION);
	}

	/**
	 * Makes the system status/navigation window elements transparent if fullscreen is active.
	 */
	private void setupEdgeToEdge() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			Window window = getWindow();
			window.setDecorFitsSystemWindows(false);
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			Window window = getWindow();
			window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
			window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
		}
	}

	@Override
	protected void onMediaChange(Song song) {
		super.onMediaChange(song);
		if (song == null) {
			mCurrentSong = null;
			clearSongInfo();
			return;
		}

		mCurrentSong = song;
		updateSongInfo(song);
	}

	/**
	 * Directly forces UI rendering nodes to evaluate layouts on structural music transitions.
	 */
	private void updateSongInfo(Song song) {
		if (mTitle != null) mTitle.setText(song.title);
		if (mAlbum != null) mAlbum.setText(song.album);
		if (mArtist != null) mArtist.setText(song.artist);

		if (mGenreView != null) mGenreView.setText(song.genre);
		if (mTrackView != null) mTrackView.setText(song.trackNumber > 0 ? String.valueOf(song.trackNumber) : "");
		if (mYearView != null) mYearView.setText(song.year > 0 ? String.valueOf(song.year) : "");

		// MODIFICATION FIX: Force the UI thread layout pass immediately to fix the overlapping glitch
		View containerView = findViewById(R.id.info_table);
		if (containerView == null && mTitle != null) {
			containerView = (View) mTitle.getParent();
		}

		if (containerView != null) {
			containerView.requestLayout();
			containerView.invalidate();
		}

		triggerPaletteExtraction();
	}

	private void clearSongInfo() {
		if (mTitle != null) mTitle.setText("");
		if (mAlbum != null) mAlbum.setText("");
		if (mArtist != null) mArtist.setText("");
	}

	/**
	 * Pulls bitmap color swatches and invalidates backgrounds immediately inside callbacks.
	 */
	private void triggerPaletteExtraction() {
		if (mCoverView == null) return;
		Bitmap coverBitmap = mCoverView.getCoverBitmap();

		if (coverBitmap == null || coverBitmap.isRecycled()) {
			setDefaultBackground();
			return;
		}

		Palette.from(coverBitmap).generate(new Palette.PaletteAsyncListener() {
			@Override
			public void onGenerated(Palette palette) {
				if (palette == null) return;
				
				Palette.Swatch swatch = palette.getVibrantSwatch();
				if (swatch == null) swatch = palette.getMutedSwatch();
				
				if (swatch != null) {
					applyDynamicBackground(swatch.getRgb());
				} else {
            setDefaultBackground();
			}
		});
	}

	private void applyDynamicBackground(int targetColor) {
		GradientDrawable gradient = new GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			new int[]{targetColor, Color.BLACK}
		);
		
		// Adjust your XML parent container layout ID here if necessary
		View backgroundWrapper = findViewById(R.id.cover_view); 
		if (backgroundWrapper == null && mTitle != null) {
			backgroundWrapper = (View) mTitle.getRootView();
		}

		if (backgroundWrapper != null) {
			backgroundWrapper.setBackground(gradient);
			backgroundWrapper.invalidate(); // MODIFICATION FIX: Repaints color bounds instantly
		}
	}

	private void setDefaultBackground() {
		if (mTitle != null) {
			View root = mTitle.getRootView();
			if (root != null) {
				root.setBackgroundColor(Color.BLACK);
			}
		}
	}

	private void setControlsVisible(boolean visible) {
		mControlsVisible = visible;
	}

	private void setExtraInfoVisible(boolean visible) {
		mExtraInfoVisible = visible;
		if (mInfoTable != null) {
			mInfoTable.setVisibility(visible ? View.VISIBLE : View.GONE);
		}
	}

	private void bindControlButtons() {
		// Control buttons configuration hook
	}

	@Override
	public boolean onLongClick(View v) {
		return false;
	}
}

        