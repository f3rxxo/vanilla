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

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

/**
 * Sets the currently playing song's album art as the lock screen wallpaper.
 * Only touches the lock screen wallpaper (WallpaperManager.FLAG_LOCK on
 * Android N+), never the home screen wallpaper.
 */
public class AlbumArtWallpaper {

	private static final String TAG = "VanillaMusic";

	/**
	 * Updates the lock screen wallpaper to the given song's album art, if
	 * one is available. Silently does nothing if the song has no cover art,
	 * or if setting the wallpaper fails for any reason (e.g. permission
	 * revoked, or the device does not support a separate lock wallpaper).
	 * Involves bitmap decoding and IPC, so this should be called from a
	 * background thread.
	 *
	 * @param context the context to use
	 * @param song the song whose cover art should become the wallpaper
	 */
	public static void update(Context context, Song song) {
		if (song == null)
			return;

		Bitmap cover = song.getLargeCover(context);
		if (cover == null)
			return;

		try {
			WallpaperManager manager = WallpaperManager.getInstance(context);
			// Deliberately not using manager.getDesiredMinimumWidth/Height():
			// those are often larger than the actual screen (extra width for
			// home-screen parallax scrolling), so an image composed at that
			// size gets center-cropped by the system to fit the real lock
			// screen viewport - which looked like unwanted zoom. The actual
			// screen size avoids that entirely.
			DisplayMetrics metrics = context.getResources().getDisplayMetrics();
			int width = metrics.widthPixels;
			int height = metrics.heightPixels;
			if (width <= 0 || height <= 0) {
				width = cover.getWidth();
				height = cover.getHeight();
			}

			Bitmap wallpaper = composeFullscreenBitmap(cover, width, height);
			if (wallpaper == null)
				return;

			if (Build.VERSION.SDK_INT >= 24) {
				// Android 7+: sets only the lock screen wallpaper, leaving
				// the home screen wallpaper untouched.
				manager.setBitmap(wallpaper, null, true, WallpaperManager.FLAG_LOCK);
			} else {
				// No separate lock screen wallpaper API pre-N: falls back
				// to setting the regular (single) system wallpaper.
				manager.setBitmap(wallpaper);
			}
		} catch (Exception e) {
			// Wallpaper permission may have been revoked, or the device may
			// not support this - this is a cosmetic feature, so just log
			// and move on rather than disrupting playback.
			Log.w(TAG, "Failed to set album art wallpaper", e);
		}
	}

	/**
	 * Composes a wallpaper-sized bitmap: just the cover art itself, scaled
	 * and cropped to completely fill the given dimensions edge-to-edge. No
	 * separate background layer (no blur, no color fill) - the artwork is
	 * the whole image.
	 */
	private static Bitmap composeFullscreenBitmap(Bitmap source, int width, int height) {
		if (source == null || width < 1 || height < 1)
			return null;

		int sourceWidth = source.getWidth();
		int sourceHeight = source.getHeight();
		float scale = Math.max((float)width / sourceWidth, (float)height / sourceHeight);
		int scaledWidth = Math.round(sourceWidth * scale);
		int scaledHeight = Math.round(sourceHeight * scale);

		Bitmap scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);
		Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		int left = (width - scaledWidth) / 2;
		int top = (height - scaledHeight) / 2;
		canvas.drawBitmap(scaled, left, top, new Paint());
		return bitmap;
	}

}
