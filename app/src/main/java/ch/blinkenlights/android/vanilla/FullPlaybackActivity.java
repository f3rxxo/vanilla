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

		// Let this open directly over the lock screen (e.g. tapping the
		// notification or a widget) instead of bouncing through an unlock
		// prompt first. This is always in response to the user tapping
		// something, so it doesn't need USE_FULL_SCREEN_INTENT - that
		// permission is only required for the OS auto-launching an activity
		// with no user interaction at all (like an incoming call).
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			setShowWhenLocked(true);
			setTurnScreenOn(true);
		} else {
			getWindow().addFlags(
				WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
				| WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
				| WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}

		// Whether this instance was launched while the device was locked
		// (e.g. tapping the notification from the lock screen). Used to
		// give the lock screen case a different look: cropped edge-to-edge
		// art with no blurred background, vs. the normal in-app view's
		// natural-size art over a blurred background.
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
			// fall through
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

	@Override
	public void onResume()
	{
		super.onResume();

		// Re-check lock state every time this becomes visible again: if
		// this activity instance was already running (e.g. opened via
		// normal in-app navigation while unlocked) and the device gets
		// locked/unlocked while it's showing - screen off then back on -
		// onCreate() never runs again, since setShowWhenLocked() keeps this
		// same instance visible right through that, instead of recreating
		// it. Without this, the lock-vs-in-app style choice made once in
		// onCreate() goes stale and never updates.
		if (mDisplayMode == DISPLAY_INFO_FULLSCREEN) {
			KeyguardManager keyguardManager = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
			boolean isLocked = keyguardManager != null && keyguardManager.isKeyguardLocked();
			if (isLocked != mOpenedFromLockScreen) {
				mOpenedFromLockScreen = isLocked;
				if (mCoverView != null) {
					mCoverView.setStyle(mOpenedFromLockScreen ? CoverBitmap.STYLE_FULLSCREEN_CROP : CoverBitmap.STYLE_FULLSCREEN);
				}
				if (mOpenedFromLockScreen) {
					// Crop-fill already covers the whole screen - clear any
					// blurred background so it doesn't show through or
					// waste work maintaining it.
					getWindow().setBackgroundDrawable(null);
				} else if (mCurrentSong != null) {
					updateFullscreenBackground(mCurrentSong);
				}
			}
		}
	}

	/**
	 * Makes the system status/navigation bars transparent and hides the
	 * action bar, so the blurred background reaches the true screen edges
	 * with no blue action bar or default theme color showing through
	 * (an earlier version of this used FLAG_LAYOUT_NO_LIMITS plus the
	 * deprecated fullscreen system-UI flags, which changed how the cover art
	 * was measured and made it render incorrectly - this version does not).
	 */
	private void setupEdgeToEdge()
	{
		Window window = getWindow();

		// Let content draw genuinely behind the status/nav bars, not just
		// make their color transparent. Without this, Android still
		// auto-insets the content view below the status bar, leaving a gap
		// there filled by the plain window background instead of the art
		// continuing through - this was the source of the grey bezel at the
		// top of the screen. Using the direct platform method (API 30+)
		// rather than the deprecated systemUiVisibility flags: those caused
		// a real rendering bug earlier, but that bug turned out later to be
		// an unrelated NullPointerException crash, not actually caused by
		// those flags - so this is worth trying properly now with the
		// modern, non-deprecated API.
		if (Build.VERSION.SDK_INT >= 30) {
			window.setDecorFitsSystemWindows(false);
		}

		window.setStatusBarColor(Color.TRANSPARENT);
		if (Build.VERSION.SDK_INT >= 21) {
			window.setNavigationBarColor(Color.TRANSPARENT);
		}
		if (getActionBar() != null) {
			getActionBar().hide();
		}

		// SlidingView (the container for the controls panel + queue) paints
		// its own opaque theme background color across the full screen by
		// default - this, not the controls panel itself, was the real
		// source of the grey strip.
		View slidingView = findViewById(R.id.sliding_view);
		if (slidingView != null) {
			slidingView.setBackgroundColor(Color.TRANSPARENT);
			// The text-ghosting bug affects the elapsed-time counter (which
			// updates every second, completely unrelated to song changes)
			// just as much as song-change text - ruling out a fix targeted
			// only at specific update call sites. Forcing software
			// rendering here sidesteps GPU hardware-layer caching, which
			// is the most likely actual mechanism: with this many
			// overlapping transparent views, a stale cached layer can be
			// shown instead of a fresh one being composited.
			slidingView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
		}

		View seekBar = findViewById(R.id.fullscreen_seek_bar);
		if (seekBar != null) {
			seekBar.setBackgroundColor(Color.TRANSPARENT);
			seekBar.setElevation(0f);
		}
		View controls = findViewById(R.id.queue_slider);
		if (controls != null) {
			controls.setBackgroundColor(Color.TRANSPARENT);
			controls.setElevation(0f);
		}
	}

	/**
	 * Fetches the given song's cover art on a background thread, then hands
	 * it off to {@link #updateDynamicBackground} to build the fullscreen
	 * mode's background.
	 *
	 * @param song the song whose cover art should be used, may be null
	 */
	private void updateFullscreenBackground(final Song song)
	{
		new Thread(new Runnable() {
			@Override
			public void run() {
				// Use the small cover variant: this only feeds a heavily
				// blurred background, so decoding (and Palette analyzing) a
				// large bitmap here was wasted work and likely the biggest
				// remaining chunk of the update delay. The sharp foreground
				// art is unaffected - CoverView fetches its own copy at the
				// resolution it actually needs.
				// NOTE: like getLargeCover(), this returns a bitmap owned by
				// Song's shared static cache, not a fresh copy - it must
				// never be recycled here, only read from.
				Bitmap coverArt = song == null ? null : song.getSmallCover(FullPlaybackActivity.this);
				updateDynamicBackground(coverArt);
			}
		}).start();
	}

	/**
	 * Generates a Spotify/Apple Music-style dynamic background from the album art bitmap.
	 * Extracts vibrant colors, generates a multi-color gradient, and applies a heavy blur.
	 *
	 * @param coverArt The album art bitmap to sample, may be null. Read-only - never recycled.
	 */
	private void updateDynamicBackground(final Bitmap coverArt) {
		if (coverArt == null) {
			// Fallback to a neutral dark gradient if no art exists
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					getWindow().setBackgroundDrawable(new GradientDrawable(
						GradientDrawable.Orientation.TL_BR,
						new int[]{ 0xff121212, 0xff1c1c1e }));
				}
			});
			return;
		}

		// Extract colors on a background thread to prevent UI stuttering
		// Cap the area Palette analyzes: getLargeCover() can return a fairly
		// large bitmap (sized for fullscreen display), and Palette's default
		// resize limit is tuned for typical thumbnail sizes. Without an
		// explicit cap here, the histogram pass over a large bitmap was
		// almost certainly the source of the multi-second delay before the
		// background/song info appeared.
		Palette.from(coverArt).resizeBitmapArea(112 * 112).generate(new Palette.PaletteAsyncListener() {
			@Override
			public void onGenerated(Palette palette) {
				if (palette == null) return;

				// 1. Extract dynamic color swatches (Vibrant, Light, Muted)
				int defaultColor = 0xff2c2c2c;
				int primaryColor = palette.getVibrantColor(palette.getDominantColor(defaultColor));
				int secondaryColor = palette.getDarkVibrantColor(palette.getMutedColor(defaultColor));
				int tertiaryColor = palette.getLightVibrantColor(palette.getLightMutedColor(defaultColor));

				// 2. Generate a base canvas to paint our multi-color blob mesh.
				// Small resolution (300x300): the heavy blur softens it anyway,
				// and it drastically saves GPU/CPU memory. The resulting
				// drawable is stretched to fill the window by Android.
				int width = 300;
				int height = 300;
				Bitmap gradientMesh = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
				Canvas canvas = new Canvas(gradientMesh);

				// 3. Paint overlapping soft radial shapes (Apple Music approach)
				Paint paint = new Paint();
				paint.setAntiAlias(true);

				// Top-Left Primary Blob
				paint.setShader(new RadialGradient(0, 0, width * 0.9f,
					primaryColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
				canvas.drawRect(0, 0, width, height, paint);

				// Bottom-Right Secondary Blob
				paint.setShader(new RadialGradient(width, height, width * 1.1f,
					secondaryColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
				canvas.drawRect(0, 0, width, height, paint);

				// Top-Right Tertiary Accent Blob
				paint.setShader(new RadialGradient(width, 0, width * 0.7f,
					tertiaryColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
				canvas.drawRect(0, 0, width, height, paint);

				// 4. Apply a heavy blur to bleed the edges seamlessly
				Bitmap blurredBg = blurBitmap(gradientMesh, 8);

				// 5. Apply a slight dark tint layer so white text/controls remain readable
				Canvas finalCanvas = new Canvas(blurredBg);
				finalCanvas.drawColor(Color.argb(40, 0, 0, 0)); // 15% dark scrim overlay

				// 6. Set the processed bitmap as the window background on the main thread
				final Bitmap result = blurredBg;
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						BitmapDrawable backgroundDrawable = new BitmapDrawable(getResources(), result);
						getWindow().setBackgroundDrawable(backgroundDrawable);
					}
				});
			}
		});
	}

	/**
	 * A cheap, dependency-free approximation of a Gaussian blur: downscales
	 * the bitmap heavily, then scales it back up. The bilinear upscale
	 * softens hard edges into a convincing blur at a fraction of the cost of
	 * a real box/Gaussian blur pass.
	 *
	 * An earlier version of this used android.renderscript.ScriptIntrinsicBlur
	 * instead. RenderScript is deprecated (API 31+) and, on some newer
	 * devices/Android versions, throws on initialization - since that ran on
	 * a background thread with no try/catch, an uncaught exception there
	 * crashed the entire app the moment fullscreen mode tried to build a
	 * background. This version has no such dependency.
	 *
	 * @param bitmap bitmap to blur - always a small bitmap we generated
	 * ourselves (the gradient mesh), never the shared cached cover art, so
	 * it's always safe to treat as fully owned here.
	 * @param downscaleFactor how aggressively to downscale before scaling
	 * back up; higher values blur more.
	 */
	private Bitmap blurBitmap(Bitmap bitmap, int downscaleFactor) {
		int smallWidth = Math.max(1, bitmap.getWidth() / downscaleFactor);
		int smallHeight = Math.max(1, bitmap.getHeight() / downscaleFactor);
		Bitmap small = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true);
		Bitmap blurred = Bitmap.createScaledBitmap(small, bitmap.getWidth(), bitmap.getHeight(), true);
		if (small != blurred && small != bitmap) small.recycle();
		return blurred;
	}

	/**
	 * Hide the message overlay, if it exists.
	 */
	private void hideMessageOverlay()
	{
		if (mOverlayText != null)
			mOverlayText.setVisibility(View.GONE);
	}

	/**
	 * Show some text in a message overlay.
	 *
	 * @param text Resource id of the text to show.
	 */
	private void showOverlayMessage(int text)
	{
		if (mOverlayText == null) {
			TextView view = new TextView(this);
			// This will be drawn on top of all other controls, so we flood this view
			// with a non-alpha color
			view.setBackgroundColor(ThemeHelper.fetchThemeColor(this, android.R.attr.colorBackground));
			view.setGravity(Gravity.CENTER);
			view.setPadding(25, 25, 25, 25);
			// Make the view clickable so it eats touch events
			view.setClickable(true);
			view.setOnClickListener(this);
			addContentView(view,
					new ViewGroup.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
							LinearLayout.LayoutParams.MATCH_PARENT));
			mOverlayText = view;
		} else {
			mOverlayText.setVisibility(View.VISIBLE);
		}

		mOverlayText.setText(text);
	}

	@Override
	protected void onStateChange(int state, int toggled)
	{
		super.onStateChange(state, toggled);

		if ((toggled & (PlaybackService.FLAG_NO_MEDIA|PlaybackService.FLAG_EMPTY_QUEUE)) != 0) {
			if ((state & PlaybackService.FLAG_NO_MEDIA) != 0) {
				showOverlayMessage(R.string.no_songs);
			} else if ((state & PlaybackService.FLAG_EMPTY_QUEUE) != 0) {
				showOverlayMessage(R.string.empty_queue);
			} else {
				hideMessageOverlay();
			}
		}

		if (mQueuePosView != null)
			updateQueuePosition();
	}

	@Override
	protected void onSongChange(Song song) {
		if (mTitle != null) {
			if (song == null) {
				mTitle.setText(null);
				mAlbum.setText(null);
				mArtist.setText(null);
			} else {
				mTitle.setText(song.title);
				mAlbum.setText(song.album);
				mArtist.setText(song.artist);
			}
			if (mQueuePosView != null) {
				updateQueuePosition();
			}
		}

		mCurrentSong = song;

		if (mDisplayMode == DISPLAY_INFO_FULLSCREEN) {
			// Force a full redraw rather than relying on partial
			// invalidation: with this many overlapping transparent views
			// (SlidingView, the controls panel, the seek bar), Android's
			// dirty-region tracking appears to sometimes leave stale pixels
			// from the previous song's text/seek position visible until
			// something else forces a full redraw. This should be cheap
			// enough (once per song change, not per frame) to not matter
			// for performance.
			// invalidate() alone only forces a redraw (onDraw) using
			// whatever the view's current measured layout already is - if
			// the actual stale state is in the *layout* itself (e.g. a
			// TextView's internal Layout object not being recalculated for
			// the new text), invalidate() wouldn't fix that. requestLayout()
			// forces a full measure+layout+draw pass to be safe.
			getWindow().getDecorView().invalidate();
			getWindow().getDecorView().requestLayout();
			if (!mOpenedFromLockScreen) {
				// TEMPORARILY DISABLED for diagnosis: the blur/Palette
				// background pipeline is the prime suspect for the
				// real-time text-ghosting bug (it's the one thing that
				// differs between "no album art = fine" and "album art +
				// fullscreen mode = ghosts"). Commented out rather than
				// deleted so it's a one-line change to restore once we
				// confirm or rule this out.
				// updateFullscreenBackground(song);
			}
		}

		mHandler.sendEmptyMessage(MSG_LOAD_FAVOURITE_INFO);

		// All quick UI updates are done: Time to update the cover
		// and parse additional info
		if (mExtraInfoVisible) {
			mHandler.sendEmptyMessage(MSG_LOAD_EXTRA_INFO);
		}
		super.onSongChange(song);
	}

	/**
	 * Update the queue position display. mQueuePos must not be null.
	 */
	private void updateQueuePosition()
	{
		if (PlaybackService.finishAction(mState) == SongTimeline.FINISH_RANDOM) {
			// Not very useful in random mode; it will always show something
			// like 11/13 since the timeline is trimmed to 10 previous songs.
			// So just hide it.
			mQueuePosView.setText(null);
		} else {
			PlaybackService service = PlaybackService.get(this);
			mQueuePosView.setText((service.getTimelinePosition() + 1) + "/" + service.getTimelineLength());
		}
		mQueuePosView.requestLayout(); // ensure queue pos column has enough room
	}

	@Override
	public void onPositionInfoChanged()
	{
		if (mQueuePosView != null)
			mUiHandler.sendEmptyMessage(MSG_UPDATE_POSITION);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		super.onCreateOptionsMenu(menu);
		menu.add(0, MENU_DELETE, 30, R.string.delete);
		SubMenu enqueueMenu = menu.addSubMenu(0, MENU_ENQUEUE, 30, R.string.enqueue_current);
		SubMenu moreMenu = menu.addSubMenu(0, MENU_MORE, 30, R.string.more_from_current);
		menu.addSubMenu(0, MENU_ADD_TO_PLAYLIST, 30, R.string.add_to_playlist);
		menu.add(0, MENU_SHARE, 30, R.string.share);

		if (PluginUtils.checkPlugins(this)) {
			menu.add(0, MENU_PLUGINS, 30, R.string.plugins)
				.setIcon(R.drawable.plugin)
				.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		}

		mFavorites = menu.add(0, MENU_SONG_FAVORITE, 0, R.string.add_to_favorites)
			.setIcon(R.drawable.btn_rating_star_off_mtrl_alpha)
			.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);

		// Subitems of 'enqueue...'
		enqueueMenu.add(0, MENU_ENQUEUE_ALBUM, 30, R.string.album);
		enqueueMenu.add(0, MENU_ENQUEUE_ARTIST, 30, R.string.artist);
		enqueueMenu.add(0, MENU_ENQUEUE_GENRE, 30, R.string.genre);

		// Subitems of 'more from...'
		moreMenu.add(0, MENU_MORE_ALBUM, 30, R.string.album);
		moreMenu.add(0, MENU_MORE_ARTIST, 30, R.string.artist);
		moreMenu.add(0, MENU_MORE_GENRE, 30, R.string.genre);
		moreMenu.add(0, MENU_MORE_FOLDER, 30, R.string.folder);

		// ensure that mFavorites is updated
		mHandler.sendEmptyMessage(MSG_LOAD_FAVOURITE_INFO);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		final Song song = mCurrentSong;

		switch (item.getItemId()) {
		case android.R.id.home:
			openLibrary(null, -1);
			break;
		case MENU_MORE_ALBUM:
			openLibrary(song, MediaUtils.TYPE_ALBUM);
			break;
		case MENU_MORE_ARTIST:
			openLibrary(song, MediaUtils.TYPE_ARTIST);
			break;
		case MENU_MORE_GENRE:
			openLibrary(song, MediaUtils.TYPE_GENRE);
			break;
		case MENU_MORE_FOLDER:
			openLibrary(song, MediaUtils.TYPE_FILE);
			break;
		case MENU_ENQUEUE_ALBUM:
			PlaybackService.get(this).enqueueFromSong(song, MediaUtils.TYPE_ALBUM);
			break;
		case MENU_ENQUEUE_ARTIST:
			PlaybackService.get(this).enqueueFromSong(song, MediaUtils.TYPE_ARTIST);
			break;
		case MENU_ENQUEUE_GENRE:
			PlaybackService.get(this).enqueueFromSong(song, MediaUtils.TYPE_GENRE);
			break;
		case MENU_SONG_FAVORITE:
			long playlistId = Playlist.getFavoritesId(this, true);
			if (song != null) {
				PlaylistTask playlistTask = new PlaylistTask(playlistId, getString(R.string.playlist_favorites));
				playlistTask.audioIds = new ArrayList<Long>();
				playlistTask.audioIds.add(song.id);
				int action = Playlist.isInPlaylist(this, playlistId, song) ? MSG_REMOVE_FROM_PLAYLIST : MSG_ADD_TO_PLAYLIST;
				mHandler.sendMessage(mHandler.obtainMessage(action, playlistTask));
			}
			break;
		case MENU_ADD_TO_PLAYLIST:
			if (song != null) {
				Intent intent = new Intent();
				intent.putExtra("type", MediaUtils.TYPE_SONG);
				intent.putExtra("id", song.id);
				PlaylistDialog dialog = PlaylistDialog.newInstance(this, intent, null, song);
				dialog.show(getFragmentManager(), "PlaylistDialog");
			}
			break;
		case MENU_SHARE:
			if (song != null)
				MediaUtils.shareMedia(this, song);
			break;
		case MENU_DELETE:
			final PlaybackService playbackService = PlaybackService.get(this);
			final PlaybackActivity activity = this;

			if (song != null) {
				String delete_message = getString(R.string.delete_file, song.title);
				AlertDialog.Builder dialog = new AlertDialog.Builder(this);
				dialog.setTitle(R.string.delete);
				dialog
					.setMessage(delete_message)
					.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							// MSG_DELETE expects an intent (usually called from listview)
							Intent intent = new Intent();
							intent.putExtra(LibraryAdapter.DATA_TYPE, MediaUtils.TYPE_SONG);
							intent.putExtra(LibraryAdapter.DATA_ID, song.id);
							mHandler.sendMessage(mHandler.obtainMessage(MSG_DELETE, intent));
						}
					})
					.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
						}
					});
				dialog.create().show();
			}
			break;
			case MENU_PLUGINS:
				if (song != null) {
					Intent songIntent = new Intent();
					songIntent.putExtra("id", song.id);
					showPluginMenu(songIntent);
				}
				break;
		default:
			return super.onOptionsItemSelected(item);
		}

		return true;
	}

	@Override
	public boolean onSearchRequested()
	{
		openLibrary(null, -1);
		return false;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event)
	{
		switch (keyCode) {
		case KeyEvent.KEYCODE_DPAD_RIGHT:
			shiftCurrentSong(SongTimeline.SHIFT_NEXT_SONG);
			findViewById(R.id.next).requestFocus();
			break;
		case KeyEvent.KEYCODE_DPAD_LEFT:
			shiftCurrentSong(SongTimeline.SHIFT_PREVIOUS_SONG);
			findViewById(R.id.previous).requestFocus();
			break;
		case KeyEvent.KEYCODE_SEARCH:
			Intent librarySearch = new Intent(this, LibraryActivity.class);
			librarySearch.putExtra("launch_search", true);
			startActivity(librarySearch);
			break;
		default:
			return super.onKeyDown(keyCode, event);
		}
		return true;
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event)
	{
		switch (keyCode) {
			case KeyEvent.KEYCODE_DPAD_CENTER:
			case KeyEvent.KEYCODE_ENTER:
				setControlsVisible(!mControlsVisible);
				mHandler.sendEmptyMessage(MSG_SAVE_CONTROLS);
				return true;
			case KeyEvent.KEYCODE_BACK:
				if (mSlidingView.isShrinkable()) {
					mSlidingView.hideSlide();
					return true;
				}
		}

		return super.onKeyUp(keyCode, event);
	}

	/**
	 * Set the visibility of the controls views.
	 *
	 * @param visible True to show, false to hide
	 */
	private void setControlsVisible(boolean visible)
	{
		int mode = visible ? View.VISIBLE : View.GONE;
		mSlidingView.setVisibility(mode);
		mControlsVisible = visible;

		if (visible) {
			mPlayPauseButton.requestFocus();
		}
	}

	/**
	 * Set the visibility of the extra metadata view.
	 *
	 * @param visible True to show, false to hide
	 */
	private void setExtraInfoVisible(boolean visible)
	{
		TableLayout table = mInfoTable;
		if (table == null)
			return;

		table.setColumnCollapsed(0, !visible);
		// Make title, album, and artist multi-line when extra info is visible
		boolean singleLine = !visible;
		for (int i = 0; i != 3; ++i) {
			TableRow row = (TableRow)table.getChildAt(i);
			((TextView)row.getChildAt(1)).setSingleLine(singleLine);
		}
		// toggle visibility of all but the first three rows (the title/artist/
		// album rows)
		int visibility = visible ? View.VISIBLE : View.GONE;
		for (int i = table.getChildCount() - 1; i > 2 ; i--) {
			table.getChildAt(i).setVisibility(visibility);
		}
		mExtraInfoVisible = visible;
		if (visible && !mHandler.hasMessages(MSG_LOAD_EXTRA_INFO)) {
			mHandler.sendEmptyMessage(MSG_LOAD_EXTRA_INFO);
		}
	}

	/**
	 * Retrieve the extra metadata for the current song.
	 */
	private void loadExtraInfo()
	{
		Song song = mCurrentSong;

		mGenre = null;
		mTrack = null;
		mYear = null;
		mComposer = null;
		mPath = null;
		mFormat = null;
		mReplayGain = null;

		if(song != null) {
			MediaMetadataExtractor data = new MediaMetadataExtractor(song.path);

			mGenre = data.getFirst(MediaMetadataExtractor.GENRE);
			mTrack = song.getTrackAndDiscNumber();
			mComposer = data.getFirst(MediaMetadataExtractor.COMPOSER);
			mYear = data.getFirst(MediaMetadataExtractor.YEAR);
			mPath = song.path;

			mFormat = data.getFormat();

			BastpUtil.GainValues rg = PlaybackService.get(this).getReplayGainValues(song.path);
			mReplayGain = String.format("found=%s, track=%.2f, album=%.2f", rg.found, rg.track, rg.album);
		}

		mUiHandler.sendEmptyMessage(MSG_COMMIT_INFO);
	}

	/**
	 * Save the hidden_controls preference to storage.
	 */
	private static final int MSG_SAVE_CONTROLS = 10;
	/**
	 * Call {@link #loadExtraInfo()}.
	 */
	private static final int MSG_LOAD_EXTRA_INFO = 11;
	/**
	 * Pass obj to mExtraInfo.setText()
	 */
	private static final int MSG_COMMIT_INFO = 12;
	/**
	 * Calls {@link #updateQueuePosition()}.
	 */
	private static final int MSG_UPDATE_POSITION = 13;
	/**
	 * Check if passed song is a favorite
	 */
	private static final int MSG_LOAD_FAVOURITE_INFO = 14;
	/**
	 * Updates the favorites state
	 */
	private static final int MSG_COMMIT_FAVOURITE_INFO = 15;

	@Override
	public boolean handleMessage(Message message)
	{
		switch (message.what) {
		case MSG_SAVE_CONTROLS: {
			SharedPreferences.Editor editor = SharedPrefHelper.getSettings(this).edit();
			editor.putBoolean(PrefKeys.VISIBLE_CONTROLS, mControlsVisible);
			editor.putBoolean(PrefKeys.VISIBLE_EXTRA_INFO, mExtraInfoVisible);
			editor.apply();
			break;
		}
		case MSG_LOAD_EXTRA_INFO:
			loadExtraInfo();
			break;
		case MSG_COMMIT_INFO: {
			mGenreView.setText(mGenre);
			mTrackView.setText(mTrack);
			mYearView.setText(mYear);
			mComposerView.setText(mComposer);
			mPathView.setText(mPath);
			mFormatView.setText(mFormat);
			mReplayGainView.setText(mReplayGain);
			break;
		}
		case MSG_UPDATE_POSITION:
			updateQueuePosition();
			break;
		case MSG_NOTIFY_PLAYLIST_CHANGED: // triggers a fav-refresh
		case MSG_LOAD_FAVOURITE_INFO:
			if (mCurrentSong != null) {
				boolean found = Playlist.isInPlaylist(this, Playlist.getFavoritesId(this, false), mCurrentSong);
				mUiHandler.sendMessage(mUiHandler.obtainMessage(MSG_COMMIT_FAVOURITE_INFO, found));
			}
			break;
		case MSG_COMMIT_FAVOURITE_INFO:
			if (mFavorites != null) {
				boolean found = (boolean)message.obj;
				mFavorites.setIcon(found ? R.drawable.btn_rating_star_on_mtrl_alpha: R.drawable.btn_rating_star_off_mtrl_alpha);
				mFavorites.setTitle(found ? R.string.remove_from_favorites : R.string.add_to_favorites);
			}
			break;
		default:
			return super.handleMessage(message);
		}

		return true;
	}

	@Override
	protected void performAction(Action action) {
		switch (action) {
			case ToggleControls:
				setControlsVisible(!mControlsVisible);
				mHandler.sendEmptyMessage(MSG_SAVE_CONTROLS);
				break;
			case ShowQueue:
				mSlidingView.expandSlide();
				break;
			default:
				super.performAction(action);
		}
	}

	@Override
	public void onClick(View view)
	{
		if (view == mOverlayText && (mState & PlaybackService.FLAG_EMPTY_QUEUE) != 0) {
			setState(PlaybackService.get(this).setFinishAction(SongTimeline.FINISH_RANDOM));
		} else if (view == mCoverView) {
			performAction(mCoverPressAction);
		} else if (view.getId() == R.id.info_table) {
			openLibrary(mCurrentSong, MediaUtils.TYPE_ALBUM);
		} else {
			super.onClick(view);
		}
	}

	@Override
	public boolean onLongClick(View view)
	{
		switch (view.getId()) {
		case R.id.cover_view:
			performAction(mCoverLongPressAction);
			break;
		case R.id.info_table:
			setExtraInfoVisible(!mExtraInfoVisible);
			mHandler.sendEmptyMessage(MSG_SAVE_CONTROLS);
			break;
		default:
			return false;
		}

		return true;
	}

	@Override
	public void onSlideExpansionChanged(int expansion) {
		super.onSlideExpansionChanged(expansion);

		setControlsVisible(true);
		if (expansion != SlidingView.EXPANSION_PARTIAL) {
			setExtraInfoVisible(false);
		} else {
			SharedPreferences settings = SharedPrefHelper.getSettings(this);
			setExtraInfoVisible(settings.getBoolean(PrefKeys.VISIBLE_EXTRA_INFO, PrefDefaults.VISIBLE_EXTRA_INFO));
		}
	}

}
