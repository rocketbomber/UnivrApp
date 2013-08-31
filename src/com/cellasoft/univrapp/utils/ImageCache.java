/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.cellasoft.univrapp.utils;

import static com.cellasoft.univrapp.utils.LogUtils.LOGD;
import static com.cellasoft.univrapp.utils.LogUtils.LOGE;
import static com.cellasoft.univrapp.utils.LogUtils.makeLogTag;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Iterator;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.util.LruCache;

import com.cellasoft.univrapp.BuildConfig;

/**
 * This class handles disk and memory caching of bitmaps in conjunction with the
 * {@link ImageWorker} class and its subclasses. Use
 * {@link ImageCache#getInstance(FragmentManager, ImageCacheParams)} to get an
 * instance of this class, although usually a cache should be added directly to
 * an {@link ImageWorker} by calling
 * {@link ImageWorker#addImageCache(FragmentManager, ImageCacheParams)}.
 */
public class ImageCache {
	private static final String TAG = makeLogTag(ImageCache.class);

	// Default memory cache size as a percent of device memory class
	private static final float DEFAULT_MEM_CACHE_PERCENT = 0.15f;

	// Default disk cache size
	private static final int DEFAULT_DISK_CACHE_SIZE = 1024 * 1024 * 10; // 10MB

	// Default disk cache directory name
	private static final String DEFAULT_DISK_CACHE_DIR = "images";

	// Compression settings when writing images to disk cache
	private static final CompressFormat DEFAULT_COMPRESS_FORMAT = CompressFormat.JPEG;
	private static final int DEFAULT_COMPRESS_QUALITY = 75;
	private static final int DISK_CACHE_INDEX = 0;

	// Constants to easily toggle various caches
	private static final boolean DEFAULT_MEM_CACHE_ENABLED = true;
	private static final boolean DEFAULT_DISK_CACHE_ENABLED = true;
	private static final boolean DEFAULT_CLEAR_DISK_CACHE_ON_START = false;
	private static final boolean DEFAULT_INIT_DISK_CACHE_ON_CREATE = false;

	private DiskLruCache mDiskLruCache;
	private LruCache<String, BitmapDrawable> memoryCache;
	private ImageCacheParams mCacheParams;
	private final Object mDiskCacheLock = new Object();
	private boolean mDiskCacheStarting = true;

	private HashSet<SoftReference<Bitmap>> mReusableBitmaps;

	/**
	 * Creating a new ImageCache object using the specified parameters.
	 * 
	 * @param cacheParams
	 *            The cache parameters to use to initialize the cache
	 */
	public ImageCache(ImageCacheParams cacheParams) {
		init(cacheParams);
	}

	/**
	 * Find and return an existing ImageCache stored in a {@link RetainFragment}
	 * , if not found a new one is created using the supplied params and saved
	 * to a {@link RetainFragment}.
	 * 
	 * @param fragmentManager
	 *            The fragment manager to use when dealing with the retained
	 *            fragment.
	 * @param cacheParams
	 *            The cache parameters to use if creating the ImageCache
	 * @return An existing retained ImageCache object or a new one if one did
	 *         not exist
	 */
	public static ImageCache findOrCreateCache(ImageCacheParams cacheParams) {

		// Search for, or create an instance of the non-UI RetainFragment
		// final RetainFragment mRetainFragment =
		// findOrCreateRetainFragment(fragmentManager);

		// See if we already have an ImageCache stored in RetainFragment
		// ImageCache imageCache = (ImageCache) mRetainFragment.getObject();

		// No existing ImageCache, create one and store it in RetainFragment
		// if (imageCache == null) {
		// imageCache = new ImageCache(cacheParams);
		// mRetainFragment.setObject(imageCache);
		// }
		ImageCache imageCache = new ImageCache(cacheParams);
		return imageCache;
	}

	/**
	 * Initialize the cache, providing all parameters.
	 * 
	 * @param cacheParams
	 *            The cache parameters to initialize the cache
	 */
	private void init(ImageCacheParams cacheParams) {
		mCacheParams = cacheParams;

		// Set up memory cache
		if (mCacheParams.memoryCacheEnabled) {
			LOGD(TAG, "Memory cache created (size = "
					+ mCacheParams.memCacheSize + ")");
			memoryCache = new LruCache<String, BitmapDrawable>(
					mCacheParams.memCacheSize) {
				/**
				 * Measure item size in kilobytes rather than units which is
				 * more practical for a bitmap cache
				 */
				@Override
				protected int sizeOf(String key, BitmapDrawable bitmap) {
					final int bitmapSize = getBitmapSize(bitmap) / 1024;
					return bitmapSize == 0 ? 1 : bitmapSize;
				}

				@Override
				protected void entryRemoved(boolean evicted, String key,
						BitmapDrawable oldValue, BitmapDrawable newBitmap) {

					if (RecyclingBitmapDrawable.class.isInstance(oldValue)) {
						// The removed entry is a recycling drawable, so notify
						// it
						// that it has been removed from the memory cache
						((RecyclingBitmapDrawable) oldValue).setIsCached(false);
					} else {
						// The removed entry is a standard BitmapDrawable

						if (UIUtils.hasHoneycomb()) {
							// We're running on Honeycomb or later, so add the
							// bitmap
							// to a SoftRefrence set for possible use with
							// inBitmap later
							mReusableBitmaps.add(new SoftReference<Bitmap>(
									oldValue.getBitmap()));
						}
					}

				}

			};
		}

		// By default the disk cache is not initialized here as it should be
		// initialized
		// on a separate thread due to disk access.
		if (cacheParams.initDiskCacheOnCreate) {
			// Set up disk cache
			initDiskCache();
		}
	}

	/**
	 * Initializes the disk cache. Note that this includes disk access so this
	 * should not be executed on the main/UI thread. By default an ImageCache
	 * does not initialize the disk cache when it is created, instead you should
	 * call initDiskCache() to initialize it on a background thread.
	 */
	public void initDiskCache() {
		// Set up disk cache
		synchronized (mDiskCacheLock) {
			if (mDiskLruCache == null || mDiskLruCache.isClosed()) {
				File diskCacheDir = mCacheParams.diskCacheDir;
				if (mCacheParams.diskCacheEnabled && diskCacheDir != null) {
					if (!diskCacheDir.exists()) {
						diskCacheDir.mkdirs();
					}
					if (getUsableSpace(diskCacheDir) > mCacheParams.diskCacheSize) {
						try {
							mDiskLruCache = DiskLruCache.open(diskCacheDir, 1,
									1, mCacheParams.diskCacheSize);
							LOGD(TAG, "Disk cache initialized");
						} catch (final IOException e) {
							mCacheParams.diskCacheDir = null;
							LOGE(TAG, "initDiskCache - " + e);
						}
					}
				}
			}
			mDiskCacheStarting = false;
			mDiskCacheLock.notifyAll();
		}
	}

	/**
	 * @param candidate
	 *            - Bitmap to check
	 * @param targetOptions
	 *            - Options that have the out* value populated
	 * @return true if <code>candidate</code> can be used for inBitmap re-use
	 *         with <code>targetOptions</code>
	 */
	private static boolean canUseForInBitmap(Bitmap candidate,
			BitmapFactory.Options targetOptions) {
		int width = targetOptions.outWidth / targetOptions.inSampleSize;
		int height = targetOptions.outHeight / targetOptions.inSampleSize;

		return candidate.getWidth() == width && candidate.getHeight() == height;
	}

	/**
	 * Adds a bitmap to both memory and disk cache.
	 * 
	 * @param data
	 *            Unique identifier for the bitmap to store
	 * @param bitmap
	 *            The bitmap to store
	 */
	public void addBitmapToCache(String data, BitmapDrawable value) {
		if (String.valueOf(data) == null || value == null) {
			return;
		}

		// Add to memory cache
		if (memoryCache != null) {
			if (RecyclingBitmapDrawable.class.isInstance(value)) {
				// The removed entry is a recycling drawable, so notify it
				// that it has been added into the memory cache
				((RecyclingBitmapDrawable) value).setIsCached(true);
			}
			memoryCache.put(data, value);
		}

		synchronized (mDiskCacheLock) {
			// Add to disk cache
			if (mDiskLruCache != null) {
				final String key = hashKeyForDisk(data);
				OutputStream out = null;
				try {
					DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
					if (snapshot == null) {
						final DiskLruCache.Editor editor = mDiskLruCache
								.edit(key);
						if (editor != null) {
							out = editor.newOutputStream(DISK_CACHE_INDEX);
							value.getBitmap().compress(
									mCacheParams.compressFormat,
									mCacheParams.compressQuality, out);
							editor.commit();
						}
					} else {
						snapshot.getInputStream(DISK_CACHE_INDEX).close();
					}
				} catch (final IOException e) {
					LOGE(TAG, "addBitmapToCache - " + e);
				} catch (Exception e) {
					LOGE(TAG, "addBitmapToCache - " + e);
				} finally {
					StreamUtils.closeQuietly(out);
				}
			}
		}
	}

	/**
	 * Get from memory cache.
	 * 
	 * @param data
	 *            Unique identifier for which item to get
	 * @return The bitmap drawable if found in cache, null otherwise
	 */
	public BitmapDrawable getBitmapFromMemCache(String data) {
		BitmapDrawable memValue = null;

		if (memoryCache != null) {
			memValue = memoryCache.get(data);
		}

		if (BuildConfig.DEBUG && memValue != null) {
			LOGD(TAG, "Memory cache hit");
		}

		return memValue;
	}

	/**
	 * Get from disk cache.
	 * 
	 * @param data
	 *            Unique identifier for which item to get
	 * @return The bitmap if found in cache, null otherwise
	 */
	public Bitmap getBitmapFromDiskCache(String data) {
		final String key = hashKeyForDisk(data);
		Bitmap bitmap = null;

		synchronized (mDiskCacheLock) {
			while (mDiskCacheStarting) {
				try {
					mDiskCacheLock.wait();
				} catch (InterruptedException e) {
				}
			}
			if (mDiskLruCache != null) {
				InputStream inputStream = null;
				try {
					final DiskLruCache.Snapshot snapshot = mDiskLruCache
							.get(key);
					if (snapshot != null) {
						if (BuildConfig.DEBUG) {
							LOGD(TAG, "Disk cache hit");
						}
						inputStream = snapshot.getInputStream(DISK_CACHE_INDEX);
						if (inputStream != null) {
							FileDescriptor fd = ((FileInputStream) inputStream)
									.getFD();

							// Decode bitmap, but we don't want to sample so
							// give
							// MAX_VALUE as the target dimensions
							bitmap = ImageResizer
									.decodeSampledBitmapFromDescriptor(fd,
											Integer.MAX_VALUE,
											Integer.MAX_VALUE, this);
						}
					}
				} catch (final IOException e) {
					LOGE(TAG, "getBitmapFromDiskCache - " + e);
				} finally {
					StreamUtils.closeQuietly(inputStream);
				}
			}
			return bitmap;
		}
	}

	/**
	 * @param options
	 *            - BitmapFactory.Options with out* options populated
	 * @return Bitmap that case be used for inBitmap
	 */
	protected Bitmap getBitmapFromReusableSet(BitmapFactory.Options options) {
		Bitmap bitmap = null;

		if (mReusableBitmaps != null && !mReusableBitmaps.isEmpty()) {
			final Iterator<SoftReference<Bitmap>> iterator = mReusableBitmaps
					.iterator();
			Bitmap item;

			while (iterator.hasNext()) {
				item = iterator.next().get();

				if (null != item && item.isMutable()) {
					// Check to see it the item can be used for inBitmap
					if (canUseForInBitmap(item, options)) {
						bitmap = item;

						// Remove from reusable set so it can't be used again
						iterator.remove();
						break;
					}
				} else {
					// Remove from the set if the reference has been cleared.
					iterator.remove();
				}
			}
		}

		return bitmap;
	}

	/**
	 * Clears both the memory and disk cache associated with this ImageCache
	 * object. Note that this includes disk access so this should not be
	 * executed on the main/UI thread.
	 */
	public void clearCache() {
		if (memoryCache != null) {
			memoryCache.evictAll();
			if (BuildConfig.DEBUG) {
				LOGD(TAG, "Memory cache cleared");
			}
		}

		synchronized (mDiskCacheLock) {
			mDiskCacheStarting = true;
			if (mDiskLruCache != null && !mDiskLruCache.isClosed()) {
				try {
					mDiskLruCache.delete();
					LOGD(TAG, "Disk cache cleared");
				} catch (IOException e) {
					LOGE(TAG, "clearCache - " + e);
				}
				mDiskLruCache = null;
				initDiskCache();
			}
		}
	}

	/**
	 * Flushes the disk cache associated with this ImageCache object. Note that
	 * this includes disk access so this should not be executed on the main/UI
	 * thread.
	 */
	public void flush() {
		synchronized (mDiskCacheLock) {
			if (mDiskLruCache != null) {
				try {
					mDiskLruCache.flush();
					if (BuildConfig.DEBUG) {
						LOGD(TAG, "Disk cache flushed");
					}
				} catch (IOException e) {
					LOGE(TAG, "flush - " + e);
				}
			}
		}
	}

	/**
	 * Closes the disk cache associated with this ImageCache object. Note that
	 * this includes disk access so this should not be executed on the main/UI
	 * thread.
	 */
	public void close() {
		synchronized (mDiskCacheLock) {
			if (mDiskLruCache != null) {
				try {
					if (!mDiskLruCache.isClosed()) {
						mDiskLruCache.close();
						mDiskLruCache = null;

						if (mReusableBitmaps != null) {
							mReusableBitmaps.clear();
							mReusableBitmaps = null;
						}

						mCacheParams = null;

						if (BuildConfig.DEBUG) {
							LOGD(TAG, "Disk cache closed");
						}
					}
				} catch (IOException e) {
					LOGE(TAG, "close - " + e);
				}
			}
		}
	}

	/**
	 * A holder class that contains cache parameters.
	 */
	public static class ImageCacheParams {
		final Context context;
		final int memCacheSize;
		final int diskCacheSize;
		public File diskCacheDir;
		final CompressFormat compressFormat;
		final int compressQuality;
		final boolean memoryCacheEnabled;
		final boolean diskCacheEnabled;
		final boolean clearDiskCacheOnStart;
		final boolean initDiskCacheOnCreate;

		public ImageCacheParams(Builder builder) {
			this.context = builder.context;
			this.memCacheSize = Math.round(builder.memCacheSizePercent);
			this.diskCacheDir = getDiskCacheDir(context, builder.diskCacheDir);
			this.diskCacheSize = builder.diskCacheSize;
			this.compressFormat = builder.compressFormat;
			this.compressQuality = builder.compressQuality;
			this.memoryCacheEnabled = builder.memoryCacheEnabled;
			this.diskCacheEnabled = builder.diskCacheEnabled;
			this.clearDiskCacheOnStart = builder.clearDiskCacheOnStart;
			this.initDiskCacheOnCreate = builder.initDiskCacheOnCreate;
		}

		public static class Builder {
			private Context context;
			private float memCacheSizePercent = 0;
			private String diskCacheDir = null;
			private int diskCacheSize = 0;
			private CompressFormat compressFormat = null;
			private int compressQuality = 0;
			private boolean memoryCacheEnabled = DEFAULT_MEM_CACHE_ENABLED;
			private boolean diskCacheEnabled = DEFAULT_DISK_CACHE_ENABLED;
			private boolean clearDiskCacheOnStart = DEFAULT_CLEAR_DISK_CACHE_ON_START;
			private boolean initDiskCacheOnCreate = DEFAULT_INIT_DISK_CACHE_ON_CREATE;

			public Builder(Context context) {
				this.context = context.getApplicationContext();
			}

			/**
			 * Sets the memory cache size based on a percentage of the max
			 * available VM memory. Eg. setting percent to 0.2 would set the
			 * memory cache to one fifth of the avilable memory. Throws
			 * {@link IllegalArgumentException} if percent is < 0.05 or > .8.
			 * memCacheSize is stored in kilobytes instead of bytes as this will
			 * eventually be passed to construct a LruCache which takes an int
			 * in its constructor.
			 * 
			 * This value should be chosen carefully based on a number of
			 * factors Refer to the corresponding Android Training class for
			 * more discussion:
			 * http://developer.android.com/training/displaying-bitmaps/
			 * 
			 * @param percent
			 *            Percent of memory class to use to size memory cache
			 */
			public Builder memoryCacheSizePercentage(float percent) {
				if (percent < 0.05f || percent > 0.8f) {
					throw new IllegalArgumentException(
							"setMemCacheSizePercent - percent must be "
									+ "between 0.05 and 0.8 (inclusive)");
				}

				this.memCacheSizePercent = percent * getMemoryClass(context)
						* 1024 * 1024;
				return this;
			}

			private static int getMemoryClass(Context context) {
				return ((ActivityManager) context
						.getSystemService(Context.ACTIVITY_SERVICE))
						.getMemoryClass();
			}

			public Builder diskCacheDir(String dir) {
				this.diskCacheDir = dir;
				return this;
			}

			public Builder diskCacheSize(int size) {
				this.diskCacheSize = size;
				return this;
			}

			public Builder compressFormat(CompressFormat format) {
				this.compressFormat = format;
				return this;
			}

			public Builder compressQuality(int quality) {
				this.compressQuality = quality;
				return this;
			}

			public Builder memoryCached(boolean value) {
				this.memoryCacheEnabled = value;
				return this;
			}

			public Builder diskCached(boolean value) {
				this.diskCacheEnabled = value;
				return this;
			}

			public Builder clearDiskCachedOnStart(boolean value) {
				this.clearDiskCacheOnStart = value;
				return this;
			}

			public Builder initDiskCacheOnCreate(boolean value) {
				this.initDiskCacheOnCreate = value;
				return this;
			}

			private void initEmptyFieldsWithDefaultValues() {
				if (memCacheSizePercent == 0) {
					memCacheSizePercent = DEFAULT_MEM_CACHE_PERCENT;
				}
				if (diskCacheDir == null) {
					diskCacheDir = DEFAULT_DISK_CACHE_DIR;
				}
				if (diskCacheSize == 0) {
					diskCacheSize = DEFAULT_DISK_CACHE_SIZE;
				}
				if (compressFormat == null) {
					compressFormat = DEFAULT_COMPRESS_FORMAT;
				}
				if (compressQuality == 0) {
					compressQuality = DEFAULT_COMPRESS_QUALITY;
				}
			}

			/** Builds configured {@link ImageLoaderConfiguration} object */
			public ImageCacheParams build() {
				initEmptyFieldsWithDefaultValues();
				return new ImageCacheParams(this);
			}
		}
	}

	/**
	 * Get a usable cache directory (external if available, internal otherwise).
	 * 
	 * @param context
	 *            The context to use
	 * @param uniqueName
	 *            A unique directory name to append to the cache dir
	 * @return The cache dir
	 */
	public static File getDiskCacheDir(Context context, String uniqueName) {
		// Check if media is mounted or storage is built-in, if so, try and use
		// external cache dir
		// otherwise use internal cache dir
		final String cachePath = Environment.MEDIA_MOUNTED.equals(Environment
				.getExternalStorageState()) || !isExternalStorageRemovable() ? getExternalCacheDir(
				context).getPath()
				: context.getCacheDir().getPath();

		return new File(cachePath + File.separator + uniqueName);
	}

	/**
	 * A hashing method that changes a string (like a URL) into a hash suitable
	 * for using as a disk filename.
	 */
	public static String hashKeyForDisk(String key) {
		String cacheKey;
		try {
			final MessageDigest mDigest = MessageDigest.getInstance("MD5");
			mDigest.update(key.getBytes());
			cacheKey = bytesToHexString(mDigest.digest());
		} catch (NoSuchAlgorithmException e) {
			cacheKey = String.valueOf(key.hashCode());
		} catch (Exception e) {
			cacheKey = String.valueOf(key.hashCode());
		}
		return cacheKey;
	}

	private static String bytesToHexString(byte[] bytes) {
		// http://stackoverflow.com/questions/332079
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < bytes.length; i++) {
			String hex = Integer.toHexString(0xFF & bytes[i]);
			if (hex.length() == 1) {
				sb.append('0');
			}
			sb.append(hex);
		}
		return sb.toString();
	}

	/**
	 * Get the size in bytes of a bitmap.
	 * 
	 * @param bitmap
	 * @return size in bytes
	 */
	@TargetApi(12)
	public static int getBitmapSize(BitmapDrawable value) {
		Bitmap bitmap = value.getBitmap();

		if (UIUtils.hasHoneycombMR1()) {
			return bitmap.getByteCount();
		}
		// Pre HC-MR1
		return bitmap.getRowBytes() * bitmap.getHeight();
	}

	/**
	 * Check if external storage is built-in or removable.
	 * 
	 * @return True if external storage is removable (like an SD card), false
	 *         otherwise.
	 */
	@TargetApi(9)
	public static boolean isExternalStorageRemovable() {
		if (UIUtils.hasGingerbread()) {
			return Environment.isExternalStorageRemovable();
		}
		return true;
	}

	/**
	 * Get the external app cache directory.
	 * 
	 * @param context
	 *            The context to use
	 * @return The external cache dir
	 */
	@TargetApi(8)
	public static File getExternalCacheDir(Context context) {
		if (UIUtils.hasFroyo()) {
			return context.getExternalCacheDir();
		}

		// Before Froyo we need to construct the external cache dir ourselves
		final String cacheDir = "/Android/data/" + context.getPackageName()
				+ "/cache/";
		return new File(Environment.getExternalStorageDirectory().getPath()
				+ cacheDir);
	}

	/**
	 * Check how much usable space is available at a given path.
	 * 
	 * @param path
	 *            The path to check
	 * @return The space available in bytes
	 */
	@TargetApi(9)
	public static long getUsableSpace(File path) {
		if (UIUtils.hasGingerbread()) {
			return path.getUsableSpace();
		}
		final StatFs stats = new StatFs(path.getPath());
		return (long) stats.getBlockSize() * (long) stats.getAvailableBlocks();
	}

	/**
	 * Locate an existing instance of this Fragment or if not found, create and
	 * add it using FragmentManager.
	 * 
	 * @param fm
	 *            The FragmentManager manager to use.
	 * @return The existing instance of the Fragment or the new instance if just
	 *         created.
	 */
	public static RetainFragment findOrCreateRetainFragment(FragmentManager fm) {
		// Check to see if we have retained the worker fragment.
		RetainFragment mRetainFragment = (RetainFragment) fm
				.findFragmentByTag(TAG);

		// If not retained (or first time running), we need to create and add
		// it.
		if (mRetainFragment == null) {
			mRetainFragment = new RetainFragment();
			fm.beginTransaction().add(mRetainFragment, TAG)
					.commitAllowingStateLoss();
		}

		return mRetainFragment;
	}

	/**
	 * A simple non-UI Fragment that stores a single Object and is retained over
	 * configuration changes. It will be used to retain the ImageCache object.
	 */
	public static class RetainFragment extends Fragment {
		private Object mObject;

		/**
		 * Empty constructor as per the Fragment documentation
		 */
		public RetainFragment() {
		}

		@Override
		public void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);

			// Make sure this Fragment is retained over a configuration change
			setRetainInstance(true);
		}

		/**
		 * Store a single object in this Fragment.
		 * 
		 * @param object
		 *            The object to store
		 */
		public void setObject(Object object) {
			mObject = object;
		}

		/**
		 * Get the stored object.
		 * 
		 * @return The stored object
		 */
		public Object getObject() {
			return mObject;
		}
	}
}
