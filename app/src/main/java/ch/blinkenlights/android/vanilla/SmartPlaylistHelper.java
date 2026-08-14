/*
 * Copyright (C) 2026 Kiko
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package ch.blinkenlights.android.vanilla;

import ch.blinkenlights.android.medialibrary.MediaLibrary;

import android.content.Context;
import android.database.Cursor;
import java.util.ArrayList;

/**
 * Provides the song-id queries backing the automatically maintained smart
 * playlists: Recently played, On repeat, Hidden gems, and Recently added.
 *
 * These mirror the pattern used by {@link PlayCountsHelper#getTopSongs}: a
 * plain query over the songs table returning ids, which callers then feed
 * into Playlist.createPlaylist()/addToPlaylist() to materialize as a real,
 * browsable playlist.
 */
public class SmartPlaylistHelper {

	/** Songs added in the last 14 days count as "recently added". */
	private static final long RECENTLY_ADDED_WINDOW_SECONDS = 14L * 24 * 60 * 60;
	/** Songs need to have existed at least this long to count as a "hidden gem" candidate. */
	private static final long HIDDEN_GEM_MIN_AGE_SECONDS = 14L * 24 * 60 * 60;
	/** Songs with at most this many plays are eligible as "hidden gems". */
	private static final int HIDDEN_GEM_MAX_PLAYCOUNT = 2;

	private static long nowSeconds() {
		return System.currentTimeMillis() / 1000;
	}

	private static ArrayList<Long> collectIds(Cursor cursor, int limit) {
		ArrayList<Long> result = new ArrayList<Long>();
		if (cursor != null) {
			while (cursor.moveToNext() && (limit <= 0 || result.size() < limit)) {
				result.add(cursor.getLong(0));
			}
			cursor.close();
		}
		return result;
	}

	/**
	 * Songs last played most recently, most recent first.
	 *
	 * @param limit maximum number of songs to return, or 0 for no limit
	 */
	public static ArrayList<Long> getRecentlyPlayedSongs(Context context, int limit) {
		String selection = MediaLibrary.SongColumns.LASTPLAYED + " > 0";
		String order = MediaLibrary.SongColumns.LASTPLAYED + " DESC";
		Cursor cursor = MediaLibrary.queryLibrary(context, MediaLibrary.TABLE_SONGS,
			new String[]{ MediaLibrary.SongColumns._ID }, selection, null, order);
		return collectIds(cursor, limit);
	}

	/** "This week" window, in seconds. */
	public static final long WINDOW_THIS_WEEK = 7L * 24 * 60 * 60;
	/** "This month" window, in seconds. */
	public static final long WINDOW_THIS_MONTH = 30L * 24 * 60 * 60;

	/** Valid values for the "On repeat" timeframe preference/selector. */
	public static final String TIMEFRAME_WEEK = "week";
	public static final String TIMEFRAME_MONTH = "month";
	public static final String TIMEFRAME_ALLTIME = "alltime";

	/**
	 * Songs played most often within the given time window, based on actual
	 * per-play history (not just the running lifetime total), most played
	 * first.
	 *
	 * @param limit maximum number of songs to return, or 0 for no limit
	 * @param windowSeconds how far back to look, e.g. {@link #WINDOW_THIS_WEEK}
	 */
	public static ArrayList<Long> getOnRepeatSongsForWindow(Context context, int limit, long windowSeconds) {
		long cutoff = nowSeconds() - windowSeconds;
		String sql = "SELECT " + MediaLibrary.PlayHistoryColumns.SONG_ID
			+ " FROM " + MediaLibrary.TABLE_PLAY_HISTORY
			+ " WHERE " + MediaLibrary.PlayHistoryColumns.TIMESTAMP + " > ?"
			+ " AND " + MediaLibrary.PlayHistoryColumns.SONG_ID + " IN (SELECT " + MediaLibrary.SongColumns._ID + " FROM " + MediaLibrary.TABLE_SONGS + ")"
			+ " GROUP BY " + MediaLibrary.PlayHistoryColumns.SONG_ID
			+ " ORDER BY COUNT(*) DESC"
			+ (limit > 0 ? " LIMIT " + limit : "");
		Cursor cursor = MediaLibrary.rawQuery(context, sql, new String[]{ String.valueOf(cutoff) });
		return collectIds(cursor, limit);
	}

	/**
	 * All-time most played songs. Uses the exact lifetime playcount rather
	 * than play_history, since that total is already tracked precisely.
	 *
	 * @param limit maximum number of songs to return, or 0 for no limit
	 */
	public static ArrayList<Long> getOnRepeatSongsAllTime(Context context, int limit) {
		return PlayCountsHelper.getTopSongs(context, limit <= 0 ? 100 : limit);
	}

	/**
	 * Songs for the "On repeat" smart playlist under the given timeframe.
	 *
	 * @param timeframe one of {@link #TIMEFRAME_WEEK}, {@link #TIMEFRAME_MONTH}, {@link #TIMEFRAME_ALLTIME}
	 * @param limit maximum number of songs to return, or 0 for no limit
	 */
	public static ArrayList<Long> getOnRepeatSongs(Context context, String timeframe, int limit) {
		if (TIMEFRAME_MONTH.equals(timeframe)) {
			return getOnRepeatSongsForWindow(context, limit, WINDOW_THIS_MONTH);
		} else if (TIMEFRAME_ALLTIME.equals(timeframe)) {
			return getOnRepeatSongsAllTime(context, limit);
		} else {
			return getOnRepeatSongsForWindow(context, limit, WINDOW_THIS_WEEK);
		}
	}

	/**
	 * Older songs (added at least {@link #HIDDEN_GEM_MIN_AGE_SECONDS} ago)
	 * that have few plays, least-played first. Excludes very recent adds so
	 * this doesn't just surface new, not-yet-played songs.
	 *
	 * @param limit maximum number of songs to return, or 0 for no limit
	 */
	public static ArrayList<Long> getHiddenGemSongs(Context context, int limit) {
		long cutoff = nowSeconds() - HIDDEN_GEM_MIN_AGE_SECONDS;
		String selection = MediaLibrary.SongColumns.PLAYCOUNT + " <= " + HIDDEN_GEM_MAX_PLAYCOUNT
			+ " AND " + MediaLibrary.SongColumns.MTIME + " > 0"
			+ " AND " + MediaLibrary.SongColumns.MTIME + " < " + cutoff;
		String order = MediaLibrary.SongColumns.PLAYCOUNT + " ASC, " + MediaLibrary.SongColumns.MTIME + " ASC";
		Cursor cursor = MediaLibrary.queryLibrary(context, MediaLibrary.TABLE_SONGS,
			new String[]{ MediaLibrary.SongColumns._ID }, selection, null, order);
		return collectIds(cursor, limit);
	}

	/**
	 * Songs added within the last {@link #RECENTLY_ADDED_WINDOW_SECONDS}, most recent first.
	 *
	 * @param limit maximum number of songs to return, or 0 for no limit
	 */
	public static ArrayList<Long> getRecentlyAddedSongs(Context context, int limit) {
		long cutoff = nowSeconds() - RECENTLY_ADDED_WINDOW_SECONDS;
		String selection = MediaLibrary.SongColumns.MTIME + " > " + cutoff;
		String order = MediaLibrary.SongColumns.MTIME + " DESC";
		Cursor cursor = MediaLibrary.queryLibrary(context, MediaLibrary.TABLE_SONGS,
			new String[]{ MediaLibrary.SongColumns._ID }, selection, null, order);
		return collectIds(cursor, limit);
	}

	/**
	 * Number of songs kept in each smart playlist.
	 */
	private static final int SMART_PLAYLIST_LIMIT = 100;

	/**
	 * Rebuilds all four smart playlists from scratch. Should be run on a
	 * background thread. Each playlist is fully replaced (matching the
	 * behavior of the existing Top-N autoplaylist feature), so this is safe
	 * to call repeatedly.
	 */
	public static void refreshSmartPlaylists(Context context) {
		refreshOne(context, context.getString(R.string.smart_playlist_recently_played),
			getRecentlyPlayedSongs(context, SMART_PLAYLIST_LIMIT));
		android.content.SharedPreferences settings = SharedPrefHelper.getSettings(context);
		String timeframe = settings.getString(PrefKeys.ON_REPEAT_TIMEFRAME, PrefDefaults.ON_REPEAT_TIMEFRAME);
		refreshOne(context, context.getString(R.string.smart_playlist_on_repeat),
			getOnRepeatSongs(context, timeframe, SMART_PLAYLIST_LIMIT));
		refreshOne(context, context.getString(R.string.smart_playlist_hidden_gems),
			getHiddenGemSongs(context, SMART_PLAYLIST_LIMIT));
		refreshOne(context, context.getString(R.string.smart_playlist_recently_added),
			getRecentlyAddedSongs(context, SMART_PLAYLIST_LIMIT));
	}

	/**
	 * Rebuilds just the "On repeat" playlist for the given timeframe and
	 * saves it as the new default timeframe for future automatic refreshes.
	 * Used by the dropdown in the playlist view to switch timeframes on the
	 * fly. Should be run on a background thread.
	 *
	 * @param timeframe one of {@link #TIMEFRAME_WEEK}, {@link #TIMEFRAME_MONTH}, {@link #TIMEFRAME_ALLTIME}
	 * @return the (possibly new) id of the "On repeat" playlist, since
	 * Playlist.createPlaylist() deletes and recreates it under the hood
	 */
	public static long refreshOnRepeat(Context context, String timeframe) {
		SharedPrefHelper.getSettings(context).edit()
			.putString(PrefKeys.ON_REPEAT_TIMEFRAME, timeframe)
			.apply();
		String name = context.getString(R.string.smart_playlist_on_repeat);
		refreshOne(context, name, getOnRepeatSongs(context, timeframe, SMART_PLAYLIST_LIMIT));
		return Playlist.getPlaylist(context, name);
	}

	private static void refreshOne(Context context, String playlistName, ArrayList<Long> songIds) {
		if (songIds.isEmpty()) {
			// Nothing to show yet (e.g. fresh install) - leave any existing
			// playlist with this name alone rather than emptying it out.
			return;
		}
		long id = Playlist.createPlaylist(context, playlistName);
		Playlist.addToPlaylist(context, id, songIds);
	}

}
