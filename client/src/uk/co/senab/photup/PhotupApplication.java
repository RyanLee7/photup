package uk.co.senab.photup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;

import uk.co.senab.bitmapcache.BitmapLruCache;
import uk.co.senab.photup.facebook.Session;
import uk.co.senab.photup.model.Album;
import uk.co.senab.photup.model.FbUser;
import uk.co.senab.photup.model.PhotoSelection;
import uk.co.senab.photup.tasks.AlbumsAsyncTask;
import uk.co.senab.photup.tasks.FriendsAsyncTask;
import uk.co.senab.photup.tasks.MediaStoreAsyncTask;
import uk.co.senab.photup.tasks.AlbumsAsyncTask.AlbumsResultListener;
import uk.co.senab.photup.tasks.FriendsAsyncTask.FriendsResultListener;
import uk.co.senab.photup.tasks.MediaStoreAsyncTask.MediaStoreResultListener;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import com.facebook.android.FacebookError;

@ReportsCrashes(formKey = Constants.ACRA_GOOGLE_DOC_ID, mode = ReportingInteractionMode.TOAST, resToastText = R.string.crash_toast)
public class PhotupApplication extends Application implements FriendsResultListener, AlbumsResultListener,
		MediaStoreResultListener {
	
	public static final String THREAD_FILTERS = "filters_thread";

	static final int EXECUTOR_CORE_POOL_SIZE_PER_CORE = 1;
	static final int EXECUTOR_MAX_POOL_SIZE_PER_CORE = 4;

	private ExecutorService mMultiThreadExecutor, mSingleThreadExecutor;
	private BitmapLruCache mImageCache;

	private FriendsResultListener mFriendsListener;
	private ArrayList<FbUser> mFriends;

	private AlbumsResultListener mAlbumsListener;
	private ArrayList<Album> mAlbums;

	private MediaStoreAsyncTask mMediaStoreAsyncTask;
	private MediaStoreResultListener mMediaStoreListener;
	private final ArrayList<PhotoSelection> mMediaStorePhotos = new ArrayList<PhotoSelection>();

	private final PhotoUploadController mPhotoController = new PhotoUploadController();

	public static PhotupApplication getApplication(Context context) {
		return (PhotupApplication) context.getApplicationContext();
	}

	public ExecutorService getMultiThreadExecutorService() {
		if (null == mMultiThreadExecutor) {
			mMultiThreadExecutor = createExecutor();
		}
		return mMultiThreadExecutor;
	}

	public ExecutorService getSingleThreadExecutorService() {
		if (null == mSingleThreadExecutor) {
			mSingleThreadExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
				
				public Thread newThread(Runnable r) {
					return new Thread(r, THREAD_FILTERS);
				}
			});
		}
		return mSingleThreadExecutor;
	}

	public BitmapLruCache getImageCache() {
		if (null == mImageCache) {
			mImageCache = new BitmapLruCache(this, Constants.IMAGE_CACHE_HEAP_PERCENTAGE);
		}
		return mImageCache;
	}

	public PhotoUploadController getPhotoUploadController() {
		return mPhotoController;
	}

	private static ExecutorService createExecutor() {
		final int numCores = Runtime.getRuntime().availableProcessors();

		return new ThreadPoolExecutor(numCores * EXECUTOR_CORE_POOL_SIZE_PER_CORE, numCores
				* EXECUTOR_MAX_POOL_SIZE_PER_CORE, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
	}

	@SuppressWarnings("deprecation")
	public int getSmallestScreenDimension() {
		WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
		Display display = wm.getDefaultDisplay();
		return Math.min(display.getHeight(), display.getWidth());
	}

	@Override
	public void onCreate() {
		if (!BuildConfig.DEBUG) {
			ACRA.init(this);
		}

		super.onCreate();
		mFriends = new ArrayList<FbUser>();
		mAlbums = new ArrayList<Album>();

		// TODO Need to check for Facebook login
		Session session = Session.restore(this);
		if (null != session) {
			getFriends(null);
			getAlbums(null, false);
		}
	}

	public void getAlbums(AlbumsResultListener listener, boolean forceRefresh) {
		if (forceRefresh || mAlbums.isEmpty()) {
			mAlbumsListener = listener;
			new AlbumsAsyncTask(this, this).execute();
		} else {
			listener.onAlbumsLoaded(mAlbums);
		}
	}

	public void getFriends(FriendsResultListener listener) {
		if (mFriends.isEmpty()) {
			mFriendsListener = listener;
			new FriendsAsyncTask(this, this).execute();
		} else {
			listener.onFriendsLoaded(mFriends);
		}
	}

	@Override
	public void onLowMemory() {
		super.onLowMemory();

		if (null != mImageCache) {
			mImageCache.trimMemory();
		}
	}

	public void onFriendsLoaded(List<FbUser> friends) {
		mFriends.clear();

		if (null != friends) {
			mFriends.addAll(friends);

			if (null != mFriendsListener && mFriendsListener != this) {
				mFriendsListener.onFriendsLoaded(mFriends);
				mFriendsListener = null;
			}
		}
	}

	public void onAlbumsLoaded(List<Album> albums) {
		mAlbums.clear();

		if (null != albums) {
			mAlbums.addAll(albums);

			if (null != mAlbumsListener && mAlbumsListener != this) {
				mAlbumsListener.onAlbumsLoaded(mAlbums);
				mAlbumsListener = null;
			}
		}
	}

	public List<PhotoSelection> getMediaStorePhotos(MediaStoreResultListener listener) {
		if (null == mMediaStoreAsyncTask) {
			mMediaStoreListener = listener;
			mMediaStoreAsyncTask = new MediaStoreAsyncTask(this, this);
			mMediaStoreAsyncTask.execute();
		} else {
			listener.onPhotosLoaded(new ArrayList<PhotoSelection>(mMediaStorePhotos));
		}

		return new ArrayList<PhotoSelection>(mMediaStorePhotos);
	}

	public void onPhotosLoaded(List<PhotoSelection> friends) {
		mMediaStorePhotos.clear();

		if (null != friends) {
			mMediaStorePhotos.addAll(friends);

			if (null != mMediaStoreListener && mMediaStoreListener != this) {
				mMediaStoreListener.onPhotosLoaded(new ArrayList<PhotoSelection>(mMediaStorePhotos));
				mMediaStoreListener = null;
			}
		}
	}

	public void onFacebookError(FacebookError e) {
		Log.e("PhotupApplication", "FacebookError");
		e.printStackTrace();

		Session.clearSavedSession(this);
		Intent intent = new Intent(this, MainActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(intent);
	}

}
