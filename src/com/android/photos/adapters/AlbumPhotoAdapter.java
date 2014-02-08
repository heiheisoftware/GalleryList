package com.android.photos.adapters;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.text.Layout;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import com.android.gallery3d.R;
import com.android.gallery3d.common.Utils;
import com.android.gallery3d.data.ContentListener;
import com.android.gallery3d.data.MediaItem;
import com.android.gallery3d.data.MediaObject;
import com.android.gallery3d.data.MediaSet;
import com.android.gallery3d.data.Path;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class AlbumPhotoAdapter extends BaseAdapter {

    @Override
    public int getCount() {
        return mSource.getMediaItemCount();
    }

    @Override
    public MediaItem getItem(int position) {
        return get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        
        ViewHolder holder = null;
        if (convertView == null) {
            holder = new ViewHolder();
            convertView = mInflater.inflate(R.layout.photo_set_item, parent, false);
            holder.iv = (ImageView) convertView.findViewById(R.id.thumbnail);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        
//        holder.iv
        
        return convertView;
    }
    
    static class ViewHolder {
        ImageView iv;
    }
    
    @SuppressWarnings("unused")
    private static final String TAG = "AlbumDataAdapter";
    private static final int DATA_CACHE_SIZE = 1000;

    private static final int MSG_LOAD_START = 1;
    private static final int MSG_LOAD_FINISH = 2;
    private static final int MSG_RUN_OBJECT = 3;

    private static final int MIN_LOAD_COUNT = 32;
    private static final int MAX_LOAD_COUNT = 64;

    private final MediaItem[] mData;
    private final long[] mItemVersion;
    private final long[] mSetVersion;

    public static interface DataListener {
        public void onContentChanged(int index);
        public void onSizeChanged(int size);
    }

    private int mActiveStart = 0;
    private int mActiveEnd = 0;

    private int mContentStart = 0;
    private int mContentEnd = 0;

    private Context mContext;
    private LayoutInflater mInflater;
    private final MediaSet mSource;
    private long mSourceVersion = MediaObject.INVALID_DATA_VERSION;

    private final Handler mMainHandler;
    private int mSize = 0;

    private DataListener mDataListener;
    private MySourceListener mSourceListener = new MySourceListener();
//    private LoadingListener mLoadingListener;

    private ReloadTask mReloadTask;
    // the data version on which last loading failed
    private long mFailedVersion = MediaObject.INVALID_DATA_VERSION;

    public AlbumPhotoAdapter(Activity context, MediaSet mediaSet) {
        mSource = mediaSet;
        mContext = context;
        mInflater = LayoutInflater.from(context);
        
        mData = new MediaItem[DATA_CACHE_SIZE];
        mItemVersion = new long[DATA_CACHE_SIZE];
        mSetVersion = new long[DATA_CACHE_SIZE];
        Arrays.fill(mItemVersion, MediaObject.INVALID_DATA_VERSION);
        Arrays.fill(mSetVersion, MediaObject.INVALID_DATA_VERSION);

        mMainHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_RUN_OBJECT:
                        ((Runnable) message.obj).run();
                        return;
                    case MSG_LOAD_START:
//                        if (mLoadingListener != null) mLoadingListener.onLoadingStarted();
                        return;
                    case MSG_LOAD_FINISH:
//                        if (mLoadingListener != null) {
//                            boolean loadingFailed =
//                                    (mFailedVersion != MediaObject.INVALID_DATA_VERSION);
//                            mLoadingListener.onLoadingFinished(loadingFailed);
//                        }
                        return;
                }
            }
        };
    }

    public void resume() {
        mSource.addContentListener(mSourceListener);
        mReloadTask = new ReloadTask();
        mReloadTask.start();
    }

    public void pause() {
        mReloadTask.terminate();
        mReloadTask = null;
        mSource.removeContentListener(mSourceListener);
    }

    public MediaItem get(int index) {
        if (!isActive(index)) {
            return mSource.getMediaItem(index, 1).get(0);
        }
        return mData[index % mData.length];
    }

    public int getActiveStart() {
        return mActiveStart;
    }

    public boolean isActive(int index) {
        return index >= mActiveStart && index < mActiveEnd;
    }

    public int size() {
        return mSize;
    }

    // Returns the index of the MediaItem with the given path or
    // -1 if the path is not cached
    public int findItem(Path id) {
        for (int i = mContentStart; i < mContentEnd; i++) {
            MediaItem item = mData[i % DATA_CACHE_SIZE];
            if (item != null && id == item.getPath()) {
                return i;
            }
        }
        return -1;
    }

    private void clearSlot(int slotIndex) {
        mData[slotIndex] = null;
        mItemVersion[slotIndex] = MediaObject.INVALID_DATA_VERSION;
        mSetVersion[slotIndex] = MediaObject.INVALID_DATA_VERSION;
    }

    private void setContentWindow(int contentStart, int contentEnd) {
        if (contentStart == mContentStart && contentEnd == mContentEnd) return;
        int end = mContentEnd;
        int start = mContentStart;

        // We need change the content window before calling reloadData(...)
        synchronized (this) {
            mContentStart = contentStart;
            mContentEnd = contentEnd;
        }
        long[] itemVersion = mItemVersion;
        long[] setVersion = mSetVersion;
        if (contentStart >= end || start >= contentEnd) {
            for (int i = start, n = end; i < n; ++i) {
                clearSlot(i % DATA_CACHE_SIZE);
            }
        } else {
            for (int i = start; i < contentStart; ++i) {
                clearSlot(i % DATA_CACHE_SIZE);
            }
            for (int i = contentEnd, n = end; i < n; ++i) {
                clearSlot(i % DATA_CACHE_SIZE);
            }
        }
        if (mReloadTask != null) mReloadTask.notifyDirty();
    }

    public void setActiveWindow(int start, int end) {
        if (start == mActiveStart && end == mActiveEnd) return;

        Utils.assertTrue(start <= end
                && end - start <= mData.length && end <= mSize);

        int length = mData.length;
        mActiveStart = start;
        mActiveEnd = end;

        // If no data is visible, keep the cache content
        if (start == end) return;

        int contentStart = Utils.clamp((start + end) / 2 - length / 2,
                0, Math.max(0, mSize - length));
        int contentEnd = Math.min(contentStart + length, mSize);
        if (mContentStart > start || mContentEnd < end
                || Math.abs(contentStart - mContentStart) > MIN_LOAD_COUNT) {
            setContentWindow(contentStart, contentEnd);
        }
    }

    private class MySourceListener implements ContentListener {
        @Override
        public void onContentDirty() {
            if (mReloadTask != null) mReloadTask.notifyDirty();
        }
    }

    public void setDataListener(DataListener listener) {
        mDataListener = listener;
    }

//    public void setLoadingListener(LoadingListener listener) {
//        mLoadingListener = listener;
//    }

    private <T> T executeAndWait(Callable<T> callable) {
        FutureTask<T> task = new FutureTask<T>(callable);
        mMainHandler.sendMessage(
                mMainHandler.obtainMessage(MSG_RUN_OBJECT, task));
        try {
            return task.get();
        } catch (InterruptedException e) {
            return null;
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static class UpdateInfo {
        public long version;
        public int reloadStart;
        public int reloadCount;

        public int size;
        public ArrayList<MediaItem> items;
    }

    private class GetUpdateInfo implements Callable<UpdateInfo> {
        private final long mVersion;

        public GetUpdateInfo(long version) {
            mVersion = version;
        }

        @Override
        public UpdateInfo call() throws Exception {
            if (mFailedVersion == mVersion) {
                // previous loading failed, return null to pause loading
                return null;
            }
            UpdateInfo info = new UpdateInfo();
            long version = mVersion;
            info.version = mSourceVersion;
            info.size = mSize;
            long setVersion[] = mSetVersion;
            for (int i = mContentStart, n = mContentEnd; i < n; ++i) {
                int index = i % DATA_CACHE_SIZE;
                if (setVersion[index] != version) {
                    info.reloadStart = i;
                    info.reloadCount = Math.min(MAX_LOAD_COUNT, n - i);
                    return info;
                }
            }
            return mSourceVersion == mVersion ? null : info;
        }
    }

    private class UpdateContent implements Callable<Void> {

        private UpdateInfo mUpdateInfo;

        public UpdateContent(UpdateInfo info) {
            mUpdateInfo = info;
        }

        @Override
        public Void call() throws Exception {
            UpdateInfo info = mUpdateInfo;
            mSourceVersion = info.version;
            if (mSize != info.size) {
                mSize = info.size;
                if (mDataListener != null) mDataListener.onSizeChanged(mSize);
                if (mContentEnd > mSize) mContentEnd = mSize;
                if (mActiveEnd > mSize) mActiveEnd = mSize;
            }

            ArrayList<MediaItem> items = info.items;

            mFailedVersion = MediaObject.INVALID_DATA_VERSION;
            if ((items == null) || items.isEmpty()) {
                if (info.reloadCount > 0) {
                    mFailedVersion = info.version;
                    Log.d(TAG, "loading failed: " + mFailedVersion);
                }
                return null;
            }
            int start = Math.max(info.reloadStart, mContentStart);
            int end = Math.min(info.reloadStart + items.size(), mContentEnd);

            for (int i = start; i < end; ++i) {
                int index = i % DATA_CACHE_SIZE;
                mSetVersion[index] = info.version;
                MediaItem updateItem = items.get(i - info.reloadStart);
                long itemVersion = updateItem.getDataVersion();
                if (mItemVersion[index] != itemVersion) {
                    mItemVersion[index] = itemVersion;
                    mData[index] = updateItem;
                    if (mDataListener != null && i >= mActiveStart && i < mActiveEnd) {
                        mDataListener.onContentChanged(i);
                    }
                }
            }
            return null;
        }
    }

    /*
     * The thread model of ReloadTask
     *      *
     * [Reload Task]       [Main Thread]
     *       |                   |
     * getUpdateInfo() -->       |           (synchronous call)
     *     (wait) <----    getUpdateInfo()
     *       |                   |
     *   Load Data               |
     *       |                   |
     * updateContent() -->       |           (synchronous call)
     *     (wait)          updateContent()
     *       |                   |
     *       |                   |
     */
    private class ReloadTask extends Thread {

        private volatile boolean mActive = true;
        private volatile boolean mDirty = true;
        private boolean mIsLoading = false;

        private void updateLoading(boolean loading) {
            if (mIsLoading == loading) return;
            mIsLoading = loading;
            mMainHandler.sendEmptyMessage(loading ? MSG_LOAD_START : MSG_LOAD_FINISH);
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            boolean updateComplete = false;
            while (mActive) {
                synchronized (this) {
                    if (mActive && !mDirty && updateComplete) {
                        updateLoading(false);
                        if (mFailedVersion != MediaObject.INVALID_DATA_VERSION) {
                            Log.d(TAG, "reload pause");
                        }
                        Utils.waitWithoutInterrupt(this);
                        if (mActive && (mFailedVersion != MediaObject.INVALID_DATA_VERSION)) {
                            Log.d(TAG, "reload resume");
                        }
                        continue;
                    }
                    mDirty = false;
                }
                updateLoading(true);
                long version = mSource.reload();
                UpdateInfo info = executeAndWait(new GetUpdateInfo(version));
                updateComplete = info == null;
                if (updateComplete) continue;
                if (info.version != version) {
                    info.size = mSource.getMediaItemCount();
                    info.version = version;
                }
                if (info.reloadCount > 0) {
                    info.items = mSource.getMediaItem(info.reloadStart, info.reloadCount);
                }
                executeAndWait(new UpdateContent(info));
            }
            updateLoading(false);
        }

        public synchronized void notifyDirty() {
            mDirty = true;
            notifyAll();
        }

        public synchronized void terminate() {
            mActive = false;
            notifyAll();
        }
    }


}
