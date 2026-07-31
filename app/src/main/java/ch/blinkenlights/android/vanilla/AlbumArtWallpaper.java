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
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Sets the currently playing song's album art as the lock screen wallpaper.
 * Only touches the lock screen wallpaper (WallpaperManager.FLAG_LOCK on
 * Android N+), never the home screen wallpaper.
 */
public class AlbumArtWallpaper {

	private static final String TAG = "VanillaMusic";
	private static final String ORIGINAL_WALLPAPER_FILENAME = "original_lock_wallpaper.png";

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

		saveOriginalIfNeeded(context);

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
	 * Restores whatever lock screen wallpaper was in place before this
	 * feature first changed it, if we managed to save a copy. If we never
	 * captured one (e.g. the original was a live/dynamic wallpaper, which
	 * can't be saved as a static image), falls back to
	 * WallpaperManager.clear(), which drops the lock-specific override so
	 * the lock screen reverts to matching the home screen wallpaper - not a
	 * perfect restore, but better than being stuck on the last album art.
	 * Should be called from a background thread.
	 *
	 * @param context the context to use
	 */
	public static void restoreOriginal(Context context) {
		File savedFile = new File(context.getFilesDir(), ORIGINAL_WALLPAPER_FILENAME);
		try {
			WallpaperManager manager = WallpaperManager.getInstance(context);
			if (Build.VERSION.SDK_INT < 24) {
				return;
			}
			if (savedFile.exists()) {
				Bitmap original = BitmapFactory.decodeFile(savedFile.getAbsolutePath());
				if (original != null) {
					manager.setBitmap(original, null, true, WallpaperManager.FLAG_LOCK);
					return;
				}
			}
			// No saved original (or it failed to decode): best effort, drop
			// our override rather than leaving stale album art in place.
			manager.clear(WallpaperManager.FLAG_LOCK);
		} catch (Exception e) {
			Log.w(TAG, "Failed to restore original lock screen wallpaper", e);
		}
	}

	/**
	 * Captures whatever lock screen wallpaper is currently set, the first
	 * time this feature is ever used, so it can be restored later. Does
	 * nothing on subsequent calls (so we never accidentally save one of our
	 * own album-art wallpapers as the "original"). Best-effort: if the
	 * current wallpaper is a live/dynamic one, there is nothing to save and
	 * this silently does nothing.
	 */
	private static void saveOriginalIfNeeded(Context context) {
		File savedFile = new File(context.getFilesDir(), ORIGINAL_WALLPAPER_FILENAME);
		if (savedFile.exists())
			return;

		if (Build.VERSION.SDK_INT < 24)
			return;

		ParcelFileDescriptor pfd = null;
		try {
			WallpaperManager manager = WallpaperManager.getInstance(context);
			pfd = manager.getWallpaperFile(WallpaperManager.FLAG_LOCK);
			if (pfd == null) {
				// No lock-specific wallpaper file: the lock screen is
				// currently just mirroring the home screen wallpaper.
				pfd = manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
			}
			if (pfd == null) {
				// Likely a live/dynamic wallpaper - nothing we can capture.
				return;
			}

			InputStream in = new FileInputStream(pfd.getFileDescriptor());
			OutputStream out = new FileOutputStream(savedFile);
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
			out.close();
			// Deliberately not closing `in`: it's backed by pfd, closed below.
		} catch (Exception e) {
			Log.w(TAG, "Failed to save original lock screen wallpaper", e);
		} finally {
			if (pfd != null) {
				try { pfd.close(); } catch (Exception ignored) {}
			}
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
