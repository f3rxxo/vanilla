/*
 * Copyright (C) 2018 Adrian Ulrich <adrian@blinkenlights.ch>
 * Copyright (C) 2012 Christopher Eby <kreed@kreed.org>
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

import ch.blinkenlights.android.vanilla.ui.FancyMenu;
import ch.blinkenlights.android.vanilla.ui.FancyMenuItem;
import ch.blinkenlights.android.vanilla.ext.CoordClickListener;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import com.mobeta.android.dslv.DragSortListView;


/**
 * The playlist activity where playlist songs can be viewed and reordered.
 */
public class PlaylistActivity extends Activity
	implements View.OnClickListener
			   , AbsListView.OnItemClickListener
			   , CoordClickListener.Callback
			   , DialogInterface.OnClickListener
			   , DragSortListView.DropListener
			   , DragSortListView.RemoveListener
			   , FancyMenu.Callback
{
	/**
	 * The SongTimeline play mode corresponding to each
	 * LibraryActivity.ACTION_*
	 */
	private static final int[] MODE_FOR_ACTION =
		{ SongTimeline.MODE_PLAY, SongTimeline.MODE_ENQUEUE, -1,
		  SongTimeline.MODE_PLAY_POS_FIRST, SongTimeline.MODE_ENQUEUE_POS_FIRST,
		  -1, -1, -1, SongTimeline.MODE_ENQUEUE_AS_NEXT };

	/**
	 * An event loop running on a worker thread.
	 */
	private Looper mLooper;
	private DragSortListView mListView;
	private PlaylistAdapter mAdapter;

	/**
	 * The id of the playlist this activity is currently viewing.
	 */
	private long mPlaylistId;
	/**
	 * The name of the playlist this activity is currently viewing.
	 */
	private String mPlaylistName;
	/**
	 * If true, then playlists songs can be dragged to reorder.
	 */
	private boolean mEditing;

	/**
	 * The item click action specified in the preferences.
	 */
	private int mDefaultAction;
	/**
	 * The last action used from the context menu, used to implement
	 * LAST_USED_ACTION action.
	 */
	private int mLastAction = LibraryActivity.ACTION_PLAY;

	private Button mEditButton;
	private Button mDeleteButton;
	/**
	 * Timeframe selector shown only when viewing the "On repeat" smart
	 * playlist - lets the timeframe be switched on the fly instead of
	 * cluttering the playlist list with a separate entry per timeframe.
	 */
	private android.widget.Spinner mTimeframeSpinner;
	/** Guards against the spurious onItemSelected() fired by setSelection(). */
	private boolean mSuppressSpinnerCallback;
	/** Timeframe values in the same order as the spinner's display labels. */
	private static final String[] TIMEFRAME_VALUES = {
		SmartPlaylistHelper.TIMEFRAME_WEEK,
		SmartPlaylistHelper.TIMEFRAME_MONTH,
		SmartPlaylistHelper.TIMEFRAME_ALLTIME
	};

	@Override
	public void onCreate(Bundle state)
	{
		ThemeHelper.setTheme(this, R.style.BackActionBar);
		super.onCreate(state);

		HandlerThread thread = new HandlerThread(getClass().getName());
		thread.start();

		setContentView(R.layout.playlist_activity);

		CoordClickListener ccl = new CoordClickListener(this);
		DragSortListView view = (DragSortListView)findViewById(R.id.list);
		ccl.registerForOnItemLongClickListener(view);
		view.setOnItemClickListener(this);
		view.setDropListener(this);
		view.setRemoveListener(this);
		mListView = view;

		View header = LayoutInflater.from(this).inflate(R.layout.playlist_buttons, null);
		mEditButton = (Button)header.findViewById(R.id.edit);
		mEditButton.setOnClickListener(this);
		mDeleteButton = (Button)header.findViewById(R.id.delete);
		mDeleteButton.setOnClickListener(this);
		mTimeframeSpinner = (android.widget.Spinner)header.findViewById(R.id.timeframe_spinner);
		android.widget.ArrayAdapter<CharSequence> spinnerAdapter = new android.widget.ArrayAdapter<CharSequence>(
			this, android.R.layout.simple_spinner_item,
			new CharSequence[]{
				getString(R.string.on_repeat_timeframe_week),
				getString(R.string.on_repeat_timeframe_month),
				getString(R.string.on_repeat_timeframe_alltime)
			});
		spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		mTimeframeSpinner.setAdapter(spinnerAdapter);
		view.addHeaderView(header, null, false);
		mLooper = thread.getLooper();
		mAdapter = new PlaylistAdapter(this, mLooper);
		view.setAdapter(mAdapter);

		onNewIntent(getIntent());
	}

	@Override
	public void onStart()
	{
		super.onStart();
		SharedPreferences settings = SharedPrefHelper.getSettings(this);
		mDefaultAction = Integer.parseInt(settings.getString(PrefKeys.DEFAULT_PLAYLIST_ACTION, PrefDefaults.DEFAULT_PLAYLIST_ACTION));
	}

	@Override
	public void onDestroy()
	{
		mLooper.quit();
		super.onDestroy();
	}

	@Override
	public void onNewIntent(Intent intent)
	{
		long id = intent.getLongExtra("playlist", 0);
		String title = intent.getStringExtra("title");
		mAdapter.setPlaylistId(id);
		setTitle(title);
		mPlaylistId = id;
		mPlaylistName = title;

		if (getString(R.string.smart_playlist_on_repeat).equals(title)) {
			SharedPreferences settings = SharedPrefHelper.getSettings(this);
			String currentTimeframe = settings.getString(PrefKeys.ON_REPEAT_TIMEFRAME, PrefDefaults.ON_REPEAT_TIMEFRAME);
			int position = 0;
			for (int i = 0; i < TIMEFRAME_VALUES.length; i++) {
				if (TIMEFRAME_VALUES[i].equals(currentTimeframe)) {
					position = i;
					break;
				}
			}
			// setSelection() would otherwise immediately fire
			// onItemSelected() below for this same, already-correct value -
			// suppress just that one spurious callback.
			mSuppressSpinnerCallback = true;
			mTimeframeSpinner.setSelection(position);
			mTimeframeSpinner.setVisibility(View.VISIBLE);
			mTimeframeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
				@Override
				public void onItemSelected(android.widget.AdapterView<?> parent, View v, int pos, long id) {
					if (mSuppressSpinnerCallback) {
						mSuppressSpinnerCallback = false;
						return;
					}
					onTimeframeSelected(TIMEFRAME_VALUES[pos]);
				}
				@Override
				public void onNothingSelected(android.widget.AdapterView<?> parent) {}
			});
		} else {
			mTimeframeSpinner.setVisibility(View.GONE);
			mTimeframeSpinner.setOnItemSelectedListener(null);
		}
	}

	/**
	 * Rebuilds the "On repeat" playlist for the newly selected timeframe and
	 * points this screen at the result. createPlaylist() deletes and
	 * recreates the playlist under the hood, so the id changes each time -
	 * the adapter needs the fresh id, not just a re-query of the old one.
	 */
	private void onTimeframeSelected(final String timeframe)
	{
		final Context appContext = getApplicationContext();
		new Thread(new Runnable() {
			@Override
			public void run() {
				final long newId = SmartPlaylistHelper.refreshOnRepeat(appContext, timeframe);
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						mPlaylistId = newId;
						mAdapter.setPlaylistId(newId);
					}
				});
			}
		}).start();
	}

	/**
	 * Enable or disable edit mode, which allows songs to be reordered and
	 * removed.
	 *
	 * @param editing True to enable edit mode.
	 */
	public void setEditing(boolean editing)
	{
		mListView.setDragEnabled(editing);
		mAdapter.setEditable(editing);
		int visible = editing ? View.GONE : View.VISIBLE;
		mDeleteButton.setVisibility(visible);
		mEditButton.setText(editing ? R.string.done : R.string.edit);
		mEditing = editing;
	}

	@Override
	public void onClick(View view)
	{
		switch (view.getId()) {
		case R.id.edit:
			setEditing(!mEditing);
			break;
		case R.id.delete: {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			String message = getResources().getString(R.string.delete_playlist, mPlaylistName);
			builder.setMessage(message);
			builder.setPositiveButton(R.string.delete, this);
			builder.setNegativeButton(android.R.string.cancel, this);
			builder.show();
			break;
		}
		}
	}

	private static final int MENU_PLAY = LibraryActivity.ACTION_PLAY;
	private static final int MENU_PLAY_ALL = LibraryActivity.ACTION_PLAY_ALL;
	private static final int MENU_ENQUEUE = LibraryActivity.ACTION_ENQUEUE;
	private static final int MENU_ENQUEUE_ALL = LibraryActivity.ACTION_ENQUEUE_ALL;
	private static final int MENU_ENQUEUE_AS_NEXT = LibraryActivity.ACTION_ENQUEUE_AS_NEXT;
	private static final int MENU_REMOVE = -1;
	private static final int MENU_SHOW_DETAILS = -2;

	@Override
	public boolean onItemLongClickWithCoords(AdapterView<?> parent, View view, int pos, long id, float x, float y) {
		ViewHolder holder = (ViewHolder)view.findViewById(R.id.text).getTag();
		Intent intent = new Intent();
		intent.putExtra("id", id);
		intent.putExtra("position", pos);
		intent.putExtra("audioId", holder.id);

		FancyMenu fm = new FancyMenu(this, this);
		fm.setHeaderTitle(holder.title);

		fm.add(MENU_PLAY, 0, R.drawable.menu_play, R.string.play).setIntent(intent);
		fm.add(MENU_PLAY_ALL, 0, R.drawable.menu_play_all, R.string.play_all).setIntent(intent);

		fm.addSpacer(0);
		fm.add(MENU_ENQUEUE_AS_NEXT, 0, R.drawable.menu_enqueue_as_next, R.string.enqueue_as_next).setIntent(intent);
		fm.add(MENU_ENQUEUE, 0, R.drawable.menu_enqueue, R.string.enqueue).setIntent(intent);
		fm.add(MENU_ENQUEUE_ALL, 0, R.drawable.menu_enqueue, R.string.enqueue_all).setIntent(intent);

		fm.addSpacer(0);
		fm.add(MENU_SHOW_DETAILS, 0, R.drawable.menu_details, R.string.details).setIntent(intent);
		fm.add(MENU_REMOVE, 0, R.drawable.menu_remove, R.string.remove).setIntent(intent);
		fm.show(view, x, y);
		return true;
	}

	@Override
	public boolean onFancyItemSelected(FancyMenuItem item)
	{
		int itemId = item.getItemId();
		Intent intent = item.getIntent();
		int pos = intent.getIntExtra("position", -1);

		if (itemId == MENU_REMOVE) {
			mAdapter.removeItem(pos - mListView.getHeaderViewsCount());
		} else if (itemId == MENU_SHOW_DETAILS) {
			long songId = intent.getLongExtra("audioId", -1);
			TrackDetailsDialog.show(getFragmentManager(), songId);
		} else {
			performAction(itemId, pos, intent.getLongExtra("audioId", -1));
		}

		return true;
	}

	/**
	 * Perform the specified action on the adapter row with the given id and
	 * position.
	 *
	 * @param action One of LibraryActivity.ACTION_*.
	 * @param position The position in the adapter.
	 * @param audioId The id of the selected song, for PLAY/ENQUEUE.
	 */
	private void performAction(int action, int position, long audioId)
	{
		if (action == LibraryActivity.ACTION_PLAY_OR_ENQUEUE)
			action = (PlaybackService.get(this).isPlaying() ? LibraryActivity.ACTION_ENQUEUE : LibraryActivity.ACTION_PLAY);

		if (action == LibraryActivity.ACTION_LAST_USED)
			action = mLastAction;

		switch (action) {
		case LibraryActivity.ACTION_PLAY:
		case LibraryActivity.ACTION_ENQUEUE:
		case LibraryActivity.ACTION_ENQUEUE_AS_NEXT: {
			QueryTask query = MediaUtils.buildQuery(MediaUtils.TYPE_SONG, audioId, Song.FILLED_PROJECTION, null);
			query.mode = MODE_FOR_ACTION[action];
			PlaybackService.get(this).addSongs(query);
			break;
		}
		case LibraryActivity.ACTION_PLAY_ALL:
		case LibraryActivity.ACTION_ENQUEUE_ALL: {
			QueryTask query = MediaUtils.buildPlaylistQuery(mPlaylistId, Song.FILLED_PLAYLIST_PROJECTION);
			query.mode = MODE_FOR_ACTION[action];
			query.data = position - mListView.getHeaderViewsCount();
			PlaybackService.get(this).addSongs(query);
			break;
		}
		}

		mLastAction = action;
	}

	@Override
	public void onItemClick(AdapterView<?> adapterView, View view, int position, long id)
	{
		if (!mEditing && mDefaultAction != LibraryActivity.ACTION_DO_NOTHING) {
			// A DSLV row was clicked, but we need to get the DraggableRow class.
			final View target = ((ViewGroup)view).getChildAt(0);
			final ViewHolder holder = (ViewHolder)target.getTag();
			performAction(mDefaultAction, position, holder.id);
		}
	}

	@Override
	public void onClick(DialogInterface dialog, int which)
	{
		if (which == DialogInterface.BUTTON_POSITIVE) {
			Playlist.deletePlaylist(this, mPlaylistId);
			finish();
		}
		dialog.dismiss();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		} else {
			return super.onOptionsItemSelected(item);
		}
	}

	/**
	 * Fired from adapter listview  if user moved an item
	 * @param from the item index that was dragged
	 * @param to the index where the item was dropped
	 */
	@Override
	public void drop(int from, int to) {
		mAdapter.moveItem(from, to);
	}

	/**
	 * Fired from adapter listview if user fling-removed an item
	 * @param position The position of the removed item
	 */
	@Override
	public void remove(int position) {
		mAdapter.removeItem(position);
	}

}
