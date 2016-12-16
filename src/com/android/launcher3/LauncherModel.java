/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.launcher3;

import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.Intent.ShortcutIconResource;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.MutableInt;

import com.android.launcher3.compat.AppWidgetManagerCompat;
import com.android.launcher3.compat.LauncherActivityInfoCompat;
import com.android.launcher3.compat.LauncherAppsCompat;
import com.android.launcher3.compat.PackageInstallerCompat;
import com.android.launcher3.compat.PackageInstallerCompat.PackageInstallInfo;
import com.android.launcher3.compat.UserManagerCompat;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.config.ProviderConfig;
import com.android.launcher3.dynamicui.ExtractionUtils;
import com.android.launcher3.folder.Folder;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.graphics.LauncherIcons;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.model.AddWorkspaceItemsTask;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.CacheDataUpdatedTask;
import com.android.launcher3.model.ExtendedModelTask;
import com.android.launcher3.model.GridSizeMigrationTask;
import com.android.launcher3.model.PackageInstallStateChangedTask;
import com.android.launcher3.model.PackageItemInfo;
import com.android.launcher3.model.PackageUpdatedTask;
import com.android.launcher3.model.SdCardAvailableReceiver;
import com.android.launcher3.model.ShortcutsChangedTask;
import com.android.launcher3.model.UserLockStateChangedTask;
import com.android.launcher3.model.WidgetItem;
import com.android.launcher3.model.WidgetsModel;
import com.android.launcher3.provider.ImportDataTask;
import com.android.launcher3.provider.LauncherDbUtils;
import com.android.launcher3.shortcuts.DeepShortcutManager;
import com.android.launcher3.shortcuts.ShortcutInfoCompat;
import com.android.launcher3.shortcuts.ShortcutKey;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.ContentWriter;
import com.android.launcher3.util.CursorIconInfo;
import com.android.launcher3.util.GridOccupancy;
import com.android.launcher3.util.ItemInfoMatcher;
import com.android.launcher3.util.LongArrayMap;
import com.android.launcher3.util.ManagedProfileHeuristic;
import com.android.launcher3.util.MultiHashMap;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.Provider;
import com.android.launcher3.util.Thunk;
import com.android.launcher3.util.ViewOnDrawExecutor;

import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Maintains in-memory state of the Launcher. It is expected that there should be only one
 * LauncherModel object held in a static. Also provide APIs for updating the database state
 * for the Launcher.
 */
public class LauncherModel extends BroadcastReceiver
        implements LauncherAppsCompat.OnAppsChangedCallbackCompat {
    static final boolean DEBUG_LOADERS = false;
    private static final boolean DEBUG_RECEIVER = false;

    static final String TAG = "Launcher.Model";

    private static final int ITEMS_CHUNK = 6; // batch size for the workspace icons
    private static final long INVALID_SCREEN_ID = -1L;

    @Thunk final LauncherAppState mApp;
    @Thunk final Object mLock = new Object();
    @Thunk DeferredHandler mHandler = new DeferredHandler();
    @Thunk LoaderTask mLoaderTask;
    @Thunk boolean mIsLoaderTaskRunning;
    @Thunk boolean mHasLoaderCompletedOnce;

    @Thunk static final HandlerThread sWorkerThread = new HandlerThread("launcher-loader");
    static {
        sWorkerThread.start();
    }
    @Thunk static final Handler sWorker = new Handler(sWorkerThread.getLooper());

    // We start off with everything not loaded.  After that, we assume that
    // our monitoring of the package manager provides all updates and we never
    // need to do a requery.  These are only ever touched from the loader thread.
    private boolean mWorkspaceLoaded;
    private boolean mAllAppsLoaded;
    private boolean mDeepShortcutsLoaded;

    /**
     * Set of runnables to be called on the background thread after the workspace binding
     * is complete.
     */
    static final ArrayList<Runnable> mBindCompleteRunnables = new ArrayList<Runnable>();

    @Thunk WeakReference<Callbacks> mCallbacks;

    // < only access in worker thread >
    private final AllAppsList mBgAllAppsList;
    // Entire list of widgets.
    private final WidgetsModel mBgWidgetsModel;

    private boolean mHasShortcutHostPermission;
    // Runnable to check if the shortcuts permission has changed.
    private final Runnable mShortcutPermissionCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (mDeepShortcutsLoaded) {
                boolean hasShortcutHostPermission =
                        DeepShortcutManager.getInstance(mApp.getContext()).hasHostPermission();
                if (hasShortcutHostPermission != mHasShortcutHostPermission) {
                    mApp.reloadWorkspace();
                }
            }
        }
    };

    /**
     * All the static data should be accessed on the background thread, A lock should be acquired
     * on this object when accessing any data from this model.
     */
    static final BgDataModel sBgDataModel = new BgDataModel();

    // </ only access in worker thread >

    private final IconCache mIconCache;

    private final LauncherAppsCompat mLauncherApps;
    private final UserManagerCompat mUserManager;

    public interface Callbacks {
        public boolean setLoadOnResume();
        public int getCurrentWorkspaceScreen();
        public void clearPendingBinds();
        public void startBinding();
        public void bindItems(ArrayList<ItemInfo> shortcuts, int start, int end,
                              boolean forceAnimateIcons);
        public void bindScreens(ArrayList<Long> orderedScreenIds);
        public void finishFirstPageBind(ViewOnDrawExecutor executor);
        public void finishBindingItems();
        public void bindAppWidget(LauncherAppWidgetInfo info);
        public void bindAllApplications(ArrayList<AppInfo> apps);
        public void bindAppsAdded(ArrayList<Long> newScreens,
                                  ArrayList<ItemInfo> addNotAnimated,
                                  ArrayList<ItemInfo> addAnimated,
                                  ArrayList<AppInfo> addedApps);
        public void bindAppsUpdated(ArrayList<AppInfo> apps);
        public void bindShortcutsChanged(ArrayList<ShortcutInfo> updated,
                ArrayList<ShortcutInfo> removed, UserHandle user);
        public void bindWidgetsRestored(ArrayList<LauncherAppWidgetInfo> widgets);
        public void bindRestoreItemsChange(HashSet<ItemInfo> updates);
        public void bindWorkspaceComponentsRemoved(
                HashSet<String> packageNames, HashSet<ComponentName> components,
                UserHandle user);
        public void bindAppInfosRemoved(ArrayList<AppInfo> appInfos);
        public void notifyWidgetProvidersChanged();
        public void bindAllWidgets(MultiHashMap<PackageItemInfo, WidgetItem> widgets);
        public void onPageBoundSynchronously(int page);
        public void executeOnNextDraw(ViewOnDrawExecutor executor);
        public void bindDeepShortcutMap(MultiHashMap<ComponentKey, String> deepShortcutMap);
    }

    LauncherModel(LauncherAppState app, IconCache iconCache, AppFilter appFilter) {
        Context context = app.getContext();
        mApp = app;
        mBgAllAppsList = new AllAppsList(iconCache, appFilter);
        mBgWidgetsModel = new WidgetsModel(iconCache, appFilter);
        mIconCache = iconCache;

        mLauncherApps = LauncherAppsCompat.getInstance(context);
        mUserManager = UserManagerCompat.getInstance(context);
    }

    /** Runs the specified runnable immediately if called from the main thread, otherwise it is
     * posted on the main thread handler. */
    private void runOnMainThread(Runnable r) {
        if (sWorkerThread.getThreadId() == Process.myTid()) {
            // If we are on the worker thread, post onto the main handler
            mHandler.post(r);
        } else {
            r.run();
        }
    }

    /** Runs the specified runnable immediately if called from the worker thread, otherwise it is
     * posted on the worker thread handler. */
    private static void runOnWorkerThread(Runnable r) {
        if (sWorkerThread.getThreadId() == Process.myTid()) {
            r.run();
        } else {
            // If we are not on the worker thread, then post to the worker handler
            sWorker.post(r);
        }
    }

    public void setPackageState(PackageInstallInfo installInfo) {
        enqueueModelUpdateTask(new PackageInstallStateChangedTask(installInfo));
    }

    /**
     * Updates the icons and label of all pending icons for the provided package name.
     */
    public void updateSessionDisplayInfo(final String packageName) {
        HashSet<String> packages = new HashSet<>();
        packages.add(packageName);
        enqueueModelUpdateTask(new CacheDataUpdatedTask(
                CacheDataUpdatedTask.OP_SESSION_UPDATE, Process.myUserHandle(), packages));
    }

    /**
     * Adds the provided items to the workspace.
     */
    public void addAndBindAddedWorkspaceItems(List<ItemInfo> workspaceApps) {
        addAndBindAddedWorkspaceItems(Provider.of(workspaceApps));
    }

    /**
     * Adds the provided items to the workspace.
     */
    public void addAndBindAddedWorkspaceItems(
            Provider<List<ItemInfo>> appsProvider) {
        enqueueModelUpdateTask(new AddWorkspaceItemsTask(appsProvider));
    }

    /**
     * Adds an item to the DB if it was not created previously, or move it to a new
     * <container, screen, cellX, cellY>
     */
    public static void addOrMoveItemInDatabase(Context context, ItemInfo item, long container,
            long screenId, int cellX, int cellY) {
        if (item.container == ItemInfo.NO_ID) {
            // From all apps
            addItemToDatabase(context, item, container, screenId, cellX, cellY);
        } else {
            // From somewhere else
            moveItemInDatabase(context, item, container, screenId, cellX, cellY);
        }
    }

    static void checkItemInfoLocked(
            final long itemId, final ItemInfo item, StackTraceElement[] stackTrace) {
        ItemInfo modelItem = sBgDataModel.itemsIdMap.get(itemId);
        if (modelItem != null && item != modelItem) {
            // check all the data is consistent
            if (modelItem instanceof ShortcutInfo && item instanceof ShortcutInfo) {
                ShortcutInfo modelShortcut = (ShortcutInfo) modelItem;
                ShortcutInfo shortcut = (ShortcutInfo) item;
                if (modelShortcut.title.toString().equals(shortcut.title.toString()) &&
                        modelShortcut.intent.filterEquals(shortcut.intent) &&
                        modelShortcut.id == shortcut.id &&
                        modelShortcut.itemType == shortcut.itemType &&
                        modelShortcut.container == shortcut.container &&
                        modelShortcut.screenId == shortcut.screenId &&
                        modelShortcut.cellX == shortcut.cellX &&
                        modelShortcut.cellY == shortcut.cellY &&
                        modelShortcut.spanX == shortcut.spanX &&
                        modelShortcut.spanY == shortcut.spanY) {
                    // For all intents and purposes, this is the same object
                    return;
                }
            }

            // the modelItem needs to match up perfectly with item if our model is
            // to be consistent with the database-- for now, just require
            // modelItem == item or the equality check above
            String msg = "item: " + ((item != null) ? item.toString() : "null") +
                    "modelItem: " +
                    ((modelItem != null) ? modelItem.toString() : "null") +
                    "Error: ItemInfo passed to checkItemInfo doesn't match original";
            RuntimeException e = new RuntimeException(msg);
            if (stackTrace != null) {
                e.setStackTrace(stackTrace);
            }
            throw e;
        }
    }

    static void checkItemInfo(final ItemInfo item) {
        final StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        final long itemId = item.id;
        Runnable r = new Runnable() {
            public void run() {
                synchronized (sBgDataModel) {
                    checkItemInfoLocked(itemId, item, stackTrace);
                }
            }
        };
        runOnWorkerThread(r);
    }

    static void updateItemInDatabaseHelper(Context context, final ContentWriter writer,
            final ItemInfo item, final String callingFunction) {
        final long itemId = item.id;
        final Uri uri = LauncherSettings.Favorites.getContentUri(itemId);
        final ContentResolver cr = context.getContentResolver();

        final StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        Runnable r = new Runnable() {
            public void run() {
                cr.update(uri, writer.getValues(), null, null);
                updateItemArrays(item, itemId, stackTrace);
            }
        };
        runOnWorkerThread(r);
    }

    static void updateItemsInDatabaseHelper(Context context, final ArrayList<ContentValues> valuesList,
            final ArrayList<ItemInfo> items, final String callingFunction) {
        final ContentResolver cr = context.getContentResolver();

        final StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        Runnable r = new Runnable() {
            public void run() {
                ArrayList<ContentProviderOperation> ops =
                        new ArrayList<ContentProviderOperation>();
                int count = items.size();
                for (int i = 0; i < count; i++) {
                    ItemInfo item = items.get(i);
                    final long itemId = item.id;
                    final Uri uri = LauncherSettings.Favorites.getContentUri(itemId);
                    ContentValues values = valuesList.get(i);

                    ops.add(ContentProviderOperation.newUpdate(uri).withValues(values).build());
                    updateItemArrays(item, itemId, stackTrace);

                }
                try {
                    cr.applyBatch(LauncherProvider.AUTHORITY, ops);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        runOnWorkerThread(r);
    }

    static void updateItemArrays(ItemInfo item, long itemId, StackTraceElement[] stackTrace) {
        // Lock on mBgLock *after* the db operation
        synchronized (sBgDataModel) {
            checkItemInfoLocked(itemId, item, stackTrace);

            if (item.container != LauncherSettings.Favorites.CONTAINER_DESKTOP &&
                    item.container != LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                // Item is in a folder, make sure this folder exists
                if (!sBgDataModel.folders.containsKey(item.container)) {
                    // An items container is being set to a that of an item which is not in
                    // the list of Folders.
                    String msg = "item: " + item + " container being set to: " +
                            item.container + ", not in the list of folders";
                    Log.e(TAG, msg);
                }
            }

            // Items are added/removed from the corresponding FolderInfo elsewhere, such
            // as in Workspace.onDrop. Here, we just add/remove them from the list of items
            // that are on the desktop, as appropriate
            ItemInfo modelItem = sBgDataModel.itemsIdMap.get(itemId);
            if (modelItem != null &&
                    (modelItem.container == LauncherSettings.Favorites.CONTAINER_DESKTOP ||
                     modelItem.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT)) {
                switch (modelItem.itemType) {
                    case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
                    case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                    case LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT:
                    case LauncherSettings.Favorites.ITEM_TYPE_FOLDER:
                        if (!sBgDataModel.workspaceItems.contains(modelItem)) {
                            sBgDataModel.workspaceItems.add(modelItem);
                        }
                        break;
                    default:
                        break;
                }
            } else {
                sBgDataModel.workspaceItems.remove(modelItem);
            }
        }
    }

    /**
     * Move an item in the DB to a new <container, screen, cellX, cellY>
     */
    public static void moveItemInDatabase(Context context, final ItemInfo item, final long container,
            final long screenId, final int cellX, final int cellY) {
        item.container = container;
        item.cellX = cellX;
        item.cellY = cellY;

        // We store hotseat items in canonical form which is this orientation invariant position
        // in the hotseat
        if (context instanceof Launcher && screenId < 0 &&
                container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
            item.screenId = Launcher.getLauncher(context).getHotseat()
                    .getOrderInHotseat(cellX, cellY);
        } else {
            item.screenId = screenId;
        }

        final ContentWriter writer = new ContentWriter(context)
                .put(LauncherSettings.Favorites.CONTAINER, item.container)
                .put(LauncherSettings.Favorites.CELLX, item.cellX)
                .put(LauncherSettings.Favorites.CELLY, item.cellY)
                .put(LauncherSettings.Favorites.RANK, item.rank)
                .put(LauncherSettings.Favorites.SCREEN, item.screenId);

        updateItemInDatabaseHelper(context, writer, item, "moveItemInDatabase");
    }

    /**
     * Move items in the DB to a new <container, screen, cellX, cellY>. We assume that the
     * cellX, cellY have already been updated on the ItemInfos.
     */
    public static void moveItemsInDatabase(Context context, final ArrayList<ItemInfo> items,
            final long container, final int screen) {

        ArrayList<ContentValues> contentValues = new ArrayList<ContentValues>();
        int count = items.size();

        for (int i = 0; i < count; i++) {
            ItemInfo item = items.get(i);
            item.container = container;

            // We store hotseat items in canonical form which is this orientation invariant position
            // in the hotseat
            if (context instanceof Launcher && screen < 0 &&
                    container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                item.screenId = Launcher.getLauncher(context).getHotseat().getOrderInHotseat(item.cellX,
                        item.cellY);
            } else {
                item.screenId = screen;
            }

            final ContentValues values = new ContentValues();
            values.put(LauncherSettings.Favorites.CONTAINER, item.container);
            values.put(LauncherSettings.Favorites.CELLX, item.cellX);
            values.put(LauncherSettings.Favorites.CELLY, item.cellY);
            values.put(LauncherSettings.Favorites.RANK, item.rank);
            values.put(LauncherSettings.Favorites.SCREEN, item.screenId);

            contentValues.add(values);
        }
        updateItemsInDatabaseHelper(context, contentValues, items, "moveItemInDatabase");
    }

    /**
     * Move and/or resize item in the DB to a new <container, screen, cellX, cellY, spanX, spanY>
     */
    static void modifyItemInDatabase(Context context, final ItemInfo item, final long container,
            final long screenId, final int cellX, final int cellY, final int spanX, final int spanY) {
        item.container = container;
        item.cellX = cellX;
        item.cellY = cellY;
        item.spanX = spanX;
        item.spanY = spanY;

        // We store hotseat items in canonical form which is this orientation invariant position
        // in the hotseat
        if (context instanceof Launcher && screenId < 0 &&
                container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
            item.screenId = Launcher.getLauncher(context).getHotseat()
                    .getOrderInHotseat(cellX, cellY);
        } else {
            item.screenId = screenId;
        }

        final ContentWriter writer = new ContentWriter(context)
                .put(LauncherSettings.Favorites.CONTAINER, item.container)
                .put(LauncherSettings.Favorites.CELLX, item.cellX)
                .put(LauncherSettings.Favorites.CELLY, item.cellY)
                .put(LauncherSettings.Favorites.RANK, item.rank)
                .put(LauncherSettings.Favorites.SPANX, item.spanX)
                .put(LauncherSettings.Favorites.SPANY, item.spanY)
                .put(LauncherSettings.Favorites.SCREEN, item.screenId);

        updateItemInDatabaseHelper(context, writer, item, "modifyItemInDatabase");
    }

    /**
     * Update an item to the database in a specified container.
     */
    public static void updateItemInDatabase(Context context, final ItemInfo item) {
        ContentWriter writer = new ContentWriter(context);
        item.onAddToDatabase(writer);
        updateItemInDatabaseHelper(context, writer, item, "updateItemInDatabase");
    }

    /**
     * Add an item to the database in a specified container. Sets the container, screen, cellX and
     * cellY fields of the item. Also assigns an ID to the item.
     */
    public static void addItemToDatabase(Context context, final ItemInfo item, final long container,
            final long screenId, final int cellX, final int cellY) {
        item.container = container;
        item.cellX = cellX;
        item.cellY = cellY;
        // We store hotseat items in canonical form which is this orientation invariant position
        // in the hotseat
        if (context instanceof Launcher && screenId < 0 &&
                container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
            item.screenId = Launcher.getLauncher(context).getHotseat()
                    .getOrderInHotseat(cellX, cellY);
        } else {
            item.screenId = screenId;
        }

        final ContentWriter writer = new ContentWriter(context);
        final ContentResolver cr = context.getContentResolver();
        item.onAddToDatabase(writer);

        item.id = LauncherSettings.Settings.call(cr, LauncherSettings.Settings.METHOD_NEW_ITEM_ID)
                .getLong(LauncherSettings.Settings.EXTRA_VALUE);

        writer.put(LauncherSettings.Favorites._ID, item.id);

        final StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        Runnable r = new Runnable() {
            public void run() {
                cr.insert(LauncherSettings.Favorites.CONTENT_URI, writer.getValues());

                synchronized (sBgDataModel) {
                    checkItemInfoLocked(item.id, item, stackTrace);
                    sBgDataModel.addItem(item, true);
                }
            }
        };
        runOnWorkerThread(r);
    }

    /**
     * Removes the specified item from the database
     */
    public static void deleteItemFromDatabase(Context context, final ItemInfo item) {
        ArrayList<ItemInfo> items = new ArrayList<>();
        items.add(item);
        deleteItemsFromDatabase(context, items);
    }

    /**
     * Removes all the items from the database matching {@param matcher}.
     */
    public static void deleteItemsFromDatabase(Context context, ItemInfoMatcher matcher) {
        deleteItemsFromDatabase(context, matcher.filterItemInfos(sBgDataModel.itemsIdMap));
    }

    /**
     * Removes the specified items from the database
     */
    public static void deleteItemsFromDatabase(Context context,
            final Iterable<? extends ItemInfo> items) {
        final ContentResolver cr = context.getContentResolver();
        Runnable r = new Runnable() {
            public void run() {
                for (ItemInfo item : items) {
                    final Uri uri = LauncherSettings.Favorites.getContentUri(item.id);
                    cr.delete(uri, null, null);

                    sBgDataModel.removeItem(item);
                }
            }
        };
        runOnWorkerThread(r);
    }

    /**
     * Update the order of the workspace screens in the database. The array list contains
     * a list of screen ids in the order that they should appear.
     */
    public static void updateWorkspaceScreenOrder(Context context, final ArrayList<Long> screens) {
        final ArrayList<Long> screensCopy = new ArrayList<Long>(screens);
        final ContentResolver cr = context.getContentResolver();
        final Uri uri = LauncherSettings.WorkspaceScreens.CONTENT_URI;

        // Remove any negative screen ids -- these aren't persisted
        Iterator<Long> iter = screensCopy.iterator();
        while (iter.hasNext()) {
            long id = iter.next();
            if (id < 0) {
                iter.remove();
            }
        }

        Runnable r = new Runnable() {
            @Override
            public void run() {
                ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
                // Clear the table
                ops.add(ContentProviderOperation.newDelete(uri).build());
                int count = screensCopy.size();
                for (int i = 0; i < count; i++) {
                    ContentValues v = new ContentValues();
                    long screenId = screensCopy.get(i);
                    v.put(LauncherSettings.WorkspaceScreens._ID, screenId);
                    v.put(LauncherSettings.WorkspaceScreens.SCREEN_RANK, i);
                    ops.add(ContentProviderOperation.newInsert(uri).withValues(v).build());
                }

                try {
                    cr.applyBatch(LauncherProvider.AUTHORITY, ops);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }

                synchronized (sBgDataModel) {
                    sBgDataModel.workspaceScreens.clear();
                    sBgDataModel.workspaceScreens.addAll(screensCopy);
                }
            }
        };
        runOnWorkerThread(r);
    }

    /**
     * Remove the specified folder and all its contents from the database.
     */
    public static void deleteFolderAndContentsFromDatabase(Context context, final FolderInfo info) {
        final ContentResolver cr = context.getContentResolver();

        Runnable r = new Runnable() {
            public void run() {
                cr.delete(LauncherSettings.Favorites.CONTENT_URI,
                        LauncherSettings.Favorites.CONTAINER + "=" + info.id, null);
                sBgDataModel.removeItem(info.contents);
                info.contents.clear();

                cr.delete(LauncherSettings.Favorites.getContentUri(info.id), null, null);
                sBgDataModel.removeItem(info);
            }
        };
        runOnWorkerThread(r);
    }

    /**
     * Set this as the current Launcher activity object for the loader.
     */
    public void initialize(Callbacks callbacks) {
        synchronized (mLock) {
            Preconditions.assertUIThread();
            // Remove any queued UI runnables
            mHandler.cancelAll();
            mCallbacks = new WeakReference<>(callbacks);
        }
    }

    @Override
    public void onPackageChanged(String packageName, UserHandle user) {
        int op = PackageUpdatedTask.OP_UPDATE;
        enqueueModelUpdateTask(new PackageUpdatedTask(op, user, packageName));
    }

    @Override
    public void onPackageRemoved(String packageName, UserHandle user) {
        onPackagesRemoved(user, packageName);
    }

    public void onPackagesRemoved(UserHandle user, String... packages) {
        int op = PackageUpdatedTask.OP_REMOVE;
        enqueueModelUpdateTask(new PackageUpdatedTask(op, user, packages));
    }

    @Override
    public void onPackageAdded(String packageName, UserHandle user) {
        int op = PackageUpdatedTask.OP_ADD;
        enqueueModelUpdateTask(new PackageUpdatedTask(op, user, packageName));
    }

    @Override
    public void onPackagesAvailable(String[] packageNames, UserHandle user,
            boolean replacing) {
        enqueueModelUpdateTask(
                new PackageUpdatedTask(PackageUpdatedTask.OP_UPDATE, user, packageNames));
    }

    @Override
    public void onPackagesUnavailable(String[] packageNames, UserHandle user,
            boolean replacing) {
        if (!replacing) {
            enqueueModelUpdateTask(new PackageUpdatedTask(
                    PackageUpdatedTask.OP_UNAVAILABLE, user, packageNames));
        }
    }

    @Override
    public void onPackagesSuspended(String[] packageNames, UserHandle user) {
        enqueueModelUpdateTask(new PackageUpdatedTask(
                PackageUpdatedTask.OP_SUSPEND, user, packageNames));
    }

    @Override
    public void onPackagesUnsuspended(String[] packageNames, UserHandle user) {
        enqueueModelUpdateTask(new PackageUpdatedTask(
                PackageUpdatedTask.OP_UNSUSPEND, user, packageNames));
    }

    @Override
    public void onShortcutsChanged(String packageName, List<ShortcutInfoCompat> shortcuts,
            UserHandle user) {
        enqueueModelUpdateTask(new ShortcutsChangedTask(packageName, shortcuts, user, true));
    }

    public void updatePinnedShortcuts(String packageName, List<ShortcutInfoCompat> shortcuts,
            UserHandle user) {
        enqueueModelUpdateTask(new ShortcutsChangedTask(packageName, shortcuts, user, false));
    }

    /**
     * Call from the handler for ACTION_PACKAGE_ADDED, ACTION_PACKAGE_REMOVED and
     * ACTION_PACKAGE_CHANGED.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (DEBUG_RECEIVER) Log.d(TAG, "onReceive intent=" + intent);

        final String action = intent.getAction();
        if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
            // If we have changed locale we need to clear out the labels in all apps/workspace.
            forceReload();
        } else if (Intent.ACTION_MANAGED_PROFILE_ADDED.equals(action)
                || Intent.ACTION_MANAGED_PROFILE_REMOVED.equals(action)) {
            UserManagerCompat.getInstance(context).enableAndResetCache();
            forceReload();
        } else if (Intent.ACTION_MANAGED_PROFILE_AVAILABLE.equals(action) ||
                Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE.equals(action) ||
                Intent.ACTION_MANAGED_PROFILE_UNLOCKED.equals(action)) {
            UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);
            if (user != null) {
                if (Intent.ACTION_MANAGED_PROFILE_AVAILABLE.equals(action) ||
                        Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE.equals(action)) {
                    enqueueModelUpdateTask(new PackageUpdatedTask(
                            PackageUpdatedTask.OP_USER_AVAILABILITY_CHANGE, user));
                }

                // ACTION_MANAGED_PROFILE_UNAVAILABLE sends the profile back to locked mode, so
                // we need to run the state change task again.
                if (Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE.equals(action) ||
                        Intent.ACTION_MANAGED_PROFILE_UNLOCKED.equals(action)) {
                    enqueueModelUpdateTask(new UserLockStateChangedTask(user));
                }
            }
        } else if (Intent.ACTION_WALLPAPER_CHANGED.equals(action)) {
            ExtractionUtils.startColorExtractionServiceIfNecessary(context);
        }
    }

    void forceReload() {
        resetLoadedState(true, true);

        // Do this here because if the launcher activity is running it will be restarted.
        // If it's not running startLoaderFromBackground will merely tell it that it needs
        // to reload.
        startLoaderFromBackground();
    }

    public void resetLoadedState(boolean resetAllAppsLoaded, boolean resetWorkspaceLoaded) {
        synchronized (mLock) {
            // Stop any existing loaders first, so they don't set mAllAppsLoaded or
            // mWorkspaceLoaded to true later
            stopLoaderLocked();
            if (resetAllAppsLoaded) mAllAppsLoaded = false;
            if (resetWorkspaceLoaded) mWorkspaceLoaded = false;
            // Always reset deep shortcuts loaded.
            // TODO: why?
            mDeepShortcutsLoaded = false;
        }
    }

    /**
     * When the launcher is in the background, it's possible for it to miss paired
     * configuration changes.  So whenever we trigger the loader from the background
     * tell the launcher that it needs to re-run the loader when it comes back instead
     * of doing it now.
     */
    public void startLoaderFromBackground() {
        Callbacks callbacks = getCallback();
        if (callbacks != null) {
            // Only actually run the loader if they're not paused.
            if (!callbacks.setLoadOnResume()) {
                startLoader(callbacks.getCurrentWorkspaceScreen());
            }
        }
    }

    /**
     * If there is already a loader task running, tell it to stop.
     */
    private void stopLoaderLocked() {
        LoaderTask oldTask = mLoaderTask;
        if (oldTask != null) {
            oldTask.stopLocked();
        }
    }

    public boolean isCurrentCallbacks(Callbacks callbacks) {
        return (mCallbacks != null && mCallbacks.get() == callbacks);
    }

    /**
     * Starts the loader. Tries to bind {@params synchronousBindPage} synchronously if possible.
     * @return true if the page could be bound synchronously.
     */
    public boolean startLoader(int synchronousBindPage) {
        // Enable queue before starting loader. It will get disabled in Launcher#finishBindingItems
        InstallShortcutReceiver.enableInstallQueue();
        synchronized (mLock) {
            // Don't bother to start the thread if we know it's not going to do anything
            if (mCallbacks != null && mCallbacks.get() != null) {
                final Callbacks oldCallbacks = mCallbacks.get();
                // Clear any pending bind-runnables from the synchronized load process.
                runOnMainThread(new Runnable() {
                    public void run() {
                        oldCallbacks.clearPendingBinds();
                    }
                });

                // If there is already one running, tell it to stop.
                stopLoaderLocked();
                mLoaderTask = new LoaderTask(mApp.getContext(), synchronousBindPage);
                // TODO: mDeepShortcutsLoaded does not need to be true for synchronous bind.
                if (synchronousBindPage != PagedView.INVALID_RESTORE_PAGE && mAllAppsLoaded
                        && mWorkspaceLoaded && mDeepShortcutsLoaded && !mIsLoaderTaskRunning) {
                    mLoaderTask.runBindSynchronousPage(synchronousBindPage);
                    return true;
                } else {
                    sWorkerThread.setPriority(Thread.NORM_PRIORITY);
                    sWorker.post(mLoaderTask);
                }
            }
        }
        return false;
    }

    public void stopLoader() {
        synchronized (mLock) {
            if (mLoaderTask != null) {
                mLoaderTask.stopLocked();
            }
        }
    }

    /**
     * Loads the workspace screen ids in an ordered list.
     */
    public static ArrayList<Long> loadWorkspaceScreensDb(Context context) {
        final ContentResolver contentResolver = context.getContentResolver();
        final Uri screensUri = LauncherSettings.WorkspaceScreens.CONTENT_URI;

        // Get screens ordered by rank.
        return LauncherDbUtils.getScreenIdsFromCursor(contentResolver.query(
                screensUri, null, null, null, LauncherSettings.WorkspaceScreens.SCREEN_RANK));
    }

    /**
     * Runnable for the thread that loads the contents of the launcher:
     *   - workspace icons
     *   - widgets
     *   - all apps icons
     *   - deep shortcuts within apps
     */
    private class LoaderTask implements Runnable {
        private Context mContext;
        private int mPageToBindFirst;

        @Thunk boolean mIsLoadingAndBindingWorkspace;
        private boolean mStopped;
        @Thunk boolean mLoadAndBindStepFinished;

        LoaderTask(Context context, int pageToBindFirst) {
            mContext = context;
            mPageToBindFirst = pageToBindFirst;
        }

        private void loadAndBindWorkspace() {
            mIsLoadingAndBindingWorkspace = true;

            // Load the workspace
            if (DEBUG_LOADERS) {
                Log.d(TAG, "loadAndBindWorkspace mWorkspaceLoaded=" + mWorkspaceLoaded);
            }

            if (!mWorkspaceLoaded) {
                loadWorkspace();
                synchronized (LoaderTask.this) {
                    if (mStopped) {
                        return;
                    }
                    mWorkspaceLoaded = true;
                }
            }

            // Bind the workspace
            bindWorkspace(mPageToBindFirst);
        }

        private void waitForIdle() {
            // Wait until the either we're stopped or the other threads are done.
            // This way we don't start loading all apps until the workspace has settled
            // down.
            synchronized (LoaderTask.this) {
                final long workspaceWaitTime = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;

                mHandler.postIdle(new Runnable() {
                        public void run() {
                            synchronized (LoaderTask.this) {
                                mLoadAndBindStepFinished = true;
                                if (DEBUG_LOADERS) {
                                    Log.d(TAG, "done with previous binding step");
                                }
                                LoaderTask.this.notify();
                            }
                        }
                    });

                while (!mStopped && !mLoadAndBindStepFinished) {
                    try {
                        // Just in case mFlushingWorkerThread changes but we aren't woken up,
                        // wait no longer than 1sec at a time
                        this.wait(1000);
                    } catch (InterruptedException ex) {
                        // Ignore
                    }
                }
                if (DEBUG_LOADERS) {
                    Log.d(TAG, "waited "
                            + (SystemClock.uptimeMillis()-workspaceWaitTime)
                            + "ms for previous step to finish binding");
                }
            }
        }

        void runBindSynchronousPage(int synchronousBindPage) {
            if (synchronousBindPage == PagedView.INVALID_RESTORE_PAGE) {
                // Ensure that we have a valid page index to load synchronously
                throw new RuntimeException("Should not call runBindSynchronousPage() without " +
                        "valid page index");
            }
            if (!mAllAppsLoaded || !mWorkspaceLoaded) {
                // Ensure that we don't try and bind a specified page when the pages have not been
                // loaded already (we should load everything asynchronously in that case)
                throw new RuntimeException("Expecting AllApps and Workspace to be loaded");
            }
            synchronized (mLock) {
                if (mIsLoaderTaskRunning) {
                    // Ensure that we are never running the background loading at this point since
                    // we also touch the background collections
                    throw new RuntimeException("Error! Background loading is already running");
                }
            }

            // XXX: Throw an exception if we are already loading (since we touch the worker thread
            //      data structures, we can't allow any other thread to touch that data, but because
            //      this call is synchronous, we can get away with not locking).

            // The LauncherModel is static in the LauncherAppState and mHandler may have queued
            // operations from the previous activity.  We need to ensure that all queued operations
            // are executed before any synchronous binding work is done.
            mHandler.flush();

            // Divide the set of loaded items into those that we are binding synchronously, and
            // everything else that is to be bound normally (asynchronously).
            bindWorkspace(synchronousBindPage);
            // XXX: For now, continue posting the binding of AllApps as there are other issues that
            //      arise from that.
            onlyBindAllApps();

            bindDeepShortcuts();
        }

        public void run() {
            synchronized (mLock) {
                if (mStopped) {
                    return;
                }
                mIsLoaderTaskRunning = true;
            }
            // Optimize for end-user experience: if the Launcher is up and // running with the
            // All Apps interface in the foreground, load All Apps first. Otherwise, load the
            // workspace first (default).
            keep_running: {
                if (DEBUG_LOADERS) Log.d(TAG, "step 1: loading workspace");
                loadAndBindWorkspace();

                if (mStopped) {
                    break keep_running;
                }

                waitForIdle();

                // second step
                if (DEBUG_LOADERS) Log.d(TAG, "step 2: loading all apps");
                loadAndBindAllApps();

                waitForIdle();

                // third step
                if (DEBUG_LOADERS) Log.d(TAG, "step 3: loading deep shortcuts");
                loadAndBindDeepShortcuts();
            }

            // Clear out this reference, otherwise we end up holding it until all of the
            // callback runnables are done.
            mContext = null;

            synchronized (mLock) {
                // If we are still the last one to be scheduled, remove ourselves.
                if (mLoaderTask == this) {
                    mLoaderTask = null;
                }
                mIsLoaderTaskRunning = false;
                mHasLoaderCompletedOnce = true;
            }
        }

        public void stopLocked() {
            synchronized (LoaderTask.this) {
                mStopped = true;
                this.notify();
            }
        }

        /**
         * Gets the callbacks object.  If we've been stopped, or if the launcher object
         * has somehow been garbage collected, return null instead.  Pass in the Callbacks
         * object that was around when the deferred message was scheduled, and if there's
         * a new Callbacks object around then also return null.  This will save us from
         * calling onto it with data that will be ignored.
         */
        Callbacks tryGetCallbacks(Callbacks oldCallbacks) {
            synchronized (mLock) {
                if (mStopped) {
                    return null;
                }

                if (mCallbacks == null) {
                    return null;
                }

                final Callbacks callbacks = mCallbacks.get();
                if (callbacks != oldCallbacks) {
                    return null;
                }
                if (callbacks == null) {
                    Log.w(TAG, "no mCallbacks");
                    return null;
                }

                return callbacks;
            }
        }

        // check & update map of what's occupied; used to discard overlapping/invalid items
        private boolean checkItemPlacement(LongArrayMap<GridOccupancy> occupied, ItemInfo item,
                   ArrayList<Long> workspaceScreens) {
            LauncherAppState app = LauncherAppState.getInstance();
            InvariantDeviceProfile profile = app.getInvariantDeviceProfile();

            long containerIndex = item.screenId;
            if (item.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                // Return early if we detect that an item is under the hotseat button
                if (!FeatureFlags.NO_ALL_APPS_ICON &&
                        profile.isAllAppsButtonRank((int) item.screenId)) {
                    Log.e(TAG, "Error loading shortcut into hotseat " + item
                            + " into position (" + item.screenId + ":" + item.cellX + ","
                            + item.cellY + ") occupied by all apps");
                    return false;
                }

                final GridOccupancy hotseatOccupancy =
                        occupied.get((long) LauncherSettings.Favorites.CONTAINER_HOTSEAT);

                if (item.screenId >= profile.numHotseatIcons) {
                    Log.e(TAG, "Error loading shortcut " + item
                            + " into hotseat position " + item.screenId
                            + ", position out of bounds: (0 to " + (profile.numHotseatIcons - 1)
                            + ")");
                    return false;
                }

                if (hotseatOccupancy != null) {
                    if (hotseatOccupancy.cells[(int) item.screenId][0]) {
                        Log.e(TAG, "Error loading shortcut into hotseat " + item
                                + " into position (" + item.screenId + ":" + item.cellX + ","
                                + item.cellY + ") already occupied");
                            return false;
                    } else {
                        hotseatOccupancy.cells[(int) item.screenId][0] = true;
                        return true;
                    }
                } else {
                    final GridOccupancy occupancy = new GridOccupancy(profile.numHotseatIcons, 1);
                    occupancy.cells[(int) item.screenId][0] = true;
                    occupied.put((long) LauncherSettings.Favorites.CONTAINER_HOTSEAT, occupancy);
                    return true;
                }
            } else if (item.container == LauncherSettings.Favorites.CONTAINER_DESKTOP) {
                if (!workspaceScreens.contains((Long) item.screenId)) {
                    // The item has an invalid screen id.
                    return false;
                }
            } else {
                // Skip further checking if it is not the hotseat or workspace container
                return true;
            }

            final int countX = profile.numColumns;
            final int countY = profile.numRows;
            if (item.container == LauncherSettings.Favorites.CONTAINER_DESKTOP &&
                    item.cellX < 0 || item.cellY < 0 ||
                    item.cellX + item.spanX > countX || item.cellY + item.spanY > countY) {
                Log.e(TAG, "Error loading shortcut " + item
                        + " into cell (" + containerIndex + "-" + item.screenId + ":"
                        + item.cellX + "," + item.cellY
                        + ") out of screen bounds ( " + countX + "x" + countY + ")");
                return false;
            }

            if (!occupied.containsKey(item.screenId)) {
                GridOccupancy screen = new GridOccupancy(countX + 1, countY + 1);
                if (item.screenId == Workspace.FIRST_SCREEN_ID) {
                    // Mark the first row as occupied (if the feature is enabled)
                    // in order to account for the QSB.
                    screen.markCells(0, 0, countX + 1, 1, FeatureFlags.QSB_ON_FIRST_SCREEN);
                }
                occupied.put(item.screenId, screen);
            }
            final GridOccupancy occupancy = occupied.get(item.screenId);

            // Check if any workspace icons overlap with each other
            if (occupancy.isRegionVacant(item.cellX, item.cellY, item.spanX, item.spanY)) {
                occupancy.markCells(item, true);
                return true;
            } else {
                Log.e(TAG, "Error loading shortcut " + item
                        + " into cell (" + containerIndex + "-" + item.screenId + ":"
                        + item.cellX + "," + item.cellX + "," + item.spanX + "," + item.spanY
                        + ") already occupied");
                return false;
            }
        }

        private void loadWorkspace() {
            if (LauncherAppState.PROFILE_STARTUP) {
                Trace.beginSection("Loading Workspace");
            }
            final long t = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;

            final Context context = mContext;
            final ContentResolver contentResolver = context.getContentResolver();
            final PackageManager manager = context.getPackageManager();
            final boolean isSafeMode = manager.isSafeMode();
            final LauncherAppsCompat launcherApps = LauncherAppsCompat.getInstance(context);
            final DeepShortcutManager shortcutManager = DeepShortcutManager.getInstance(context);
            final boolean isSdCardReady = Utilities.isBootCompleted();
            final MultiHashMap<UserHandle, String> pendingPackages = new MultiHashMap<>();

            LauncherAppState app = LauncherAppState.getInstance();
            InvariantDeviceProfile profile = app.getInvariantDeviceProfile();
            int countX = profile.numColumns;
            int countY = profile.numRows;

            boolean clearDb = false;
            try {
                ImportDataTask.performImportIfPossible(context);
            } catch (Exception e) {
                // Migration failed. Clear workspace.
                clearDb = true;
            }

            if (!clearDb && GridSizeMigrationTask.ENABLED &&
                    !GridSizeMigrationTask.migrateGridIfNeeded(mContext)) {
                // Migration failed. Clear workspace.
                clearDb = true;
            }

            if (clearDb) {
                Log.d(TAG, "loadWorkspace: resetting launcher database");
                LauncherSettings.Settings.call(contentResolver,
                        LauncherSettings.Settings.METHOD_DELETE_DB);
            }

            Log.d(TAG, "loadWorkspace: loading default favorites");
            LauncherSettings.Settings.call(contentResolver,
                    LauncherSettings.Settings.METHOD_LOAD_DEFAULT_FAVORITES);

            synchronized (sBgDataModel) {
                sBgDataModel.clear();

                final HashMap<String, Integer> installingPkgs = PackageInstallerCompat
                        .getInstance(mContext).updateAndGetActiveSessionCache();
                sBgDataModel.workspaceScreens.addAll(loadWorkspaceScreensDb(mContext));

                final ArrayList<Long> itemsToRemove = new ArrayList<>();
                final ArrayList<Long> restoredRows = new ArrayList<>();
                Map<ShortcutKey, ShortcutInfoCompat> shortcutKeyToPinnedShortcuts = new HashMap<>();
                final Uri contentUri = LauncherSettings.Favorites.CONTENT_URI;
                if (DEBUG_LOADERS) Log.d(TAG, "loading model from " + contentUri);
                final Cursor c = contentResolver.query(contentUri, null, null, null, null);

                // +1 for the hotseat (it can be larger than the workspace)
                // Load workspace in reverse order to ensure that latest items are loaded first (and
                // before any earlier duplicates)
                final LongArrayMap<GridOccupancy> occupied = new LongArrayMap<>();
                HashMap<ComponentKey, AppWidgetProviderInfo> widgetProvidersMap = null;

                try {
                    final int idIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites._ID);
                    final int intentIndex = c.getColumnIndexOrThrow
                            (LauncherSettings.Favorites.INTENT);
                    final int containerIndex = c.getColumnIndexOrThrow(
                            LauncherSettings.Favorites.CONTAINER);
                    final int itemTypeIndex = c.getColumnIndexOrThrow(
                            LauncherSettings.Favorites.ITEM_TYPE);
                    final int appWidgetIdIndex = c.getColumnIndexOrThrow(
                            LauncherSettings.Favorites.APPWIDGET_ID);
                    final int appWidgetProviderIndex = c.getColumnIndexOrThrow(
                            LauncherSettings.Favorites.APPWIDGET_PROVIDER);
                    final int screenIndex = c.getColumnIndexOrThrow(
                            LauncherSettings.Favorites.SCREEN);
                    final int cellXIndex = c.getColumnIndexOrThrow
                            (LauncherSettings.Favorites.CELLX);
                    final int cellYIndex = c.getColumnIndexOrThrow
                            (LauncherSettings.Favorites.CELLY);
                    final int spanXIndex = c.getColumnIndexOrThrow
                            (LauncherSettings.Favorites.SPANX);
                    final int spanYIndex = c.getColumnIndexOrThrow(
                            LauncherSettings.Favorites.SPANY);
                    final int rankIndex = c.getColumnIndexOrThrow(
                            LauncherSettings.Favorites.RANK);
                    final int restoredIndex = c.getColumnIndexOrThrow(
                            LauncherSettings.Favorites.RESTORED);
                    final int profileIdIndex = c.getColumnIndexOrThrow(
                            LauncherSettings.Favorites.PROFILE_ID);
                    final int optionsIndex = c.getColumnIndexOrThrow(
                            LauncherSettings.Favorites.OPTIONS);
                    final CursorIconInfo cursorIconInfo = new CursorIconInfo(mContext, c);

                    final LongSparseArray<UserHandle> allUsers = new LongSparseArray<>();
                    final LongSparseArray<Boolean> quietMode = new LongSparseArray<>();
                    final LongSparseArray<Boolean> unlockedUsers = new LongSparseArray<>();
                    for (UserHandle user : mUserManager.getUserProfiles()) {
                        long serialNo = mUserManager.getSerialNumberForUser(user);
                        allUsers.put(serialNo, user);
                        quietMode.put(serialNo, mUserManager.isQuietModeEnabled(user));

                        boolean userUnlocked = mUserManager.isUserUnlocked(user);

                        // We can only query for shortcuts when the user is unlocked.
                        if (userUnlocked) {
                            List<ShortcutInfoCompat> pinnedShortcuts =
                                    shortcutManager.queryForPinnedShortcuts(null, user);
                            if (shortcutManager.wasLastCallSuccess()) {
                                for (ShortcutInfoCompat shortcut : pinnedShortcuts) {
                                    shortcutKeyToPinnedShortcuts.put(ShortcutKey.fromInfo(shortcut),
                                            shortcut);
                                }
                            } else {
                                // Shortcut manager can fail due to some race condition when the
                                // lock state changes too frequently. For the purpose of the loading
                                // shortcuts, consider the user is still locked.
                                userUnlocked = false;
                            }
                        }
                        unlockedUsers.put(serialNo, userUnlocked);
                    }

                    ShortcutInfo info;
                    String intentDescription;
                    LauncherAppWidgetInfo appWidgetInfo;
                    int container;
                    long id;
                    long serialNumber;
                    Intent intent;
                    UserHandle user;
                    String targetPackage;

                    while (!mStopped && c.moveToNext()) {
                        try {
                            int itemType = c.getInt(itemTypeIndex);
                            boolean restored = 0 != c.getInt(restoredIndex);
                            boolean allowMissingTarget = false;
                            container = c.getInt(containerIndex);

                            switch (itemType) {
                            case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
                            case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                            case LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT:
                                id = c.getLong(idIndex);
                                intentDescription = c.getString(intentIndex);
                                serialNumber = c.getInt(profileIdIndex);
                                user = allUsers.get(serialNumber);
                                int promiseType = c.getInt(restoredIndex);
                                int disabledState = 0;
                                boolean itemReplaced = false;
                                targetPackage = null;
                                if (user == null) {
                                    // User has been deleted remove the item.
                                    itemsToRemove.add(id);
                                    continue;
                                }
                                try {
                                    intent = Intent.parseUri(intentDescription, 0);
                                    ComponentName cn = intent.getComponent();
                                    if (cn != null && cn.getPackageName() != null) {
                                        boolean validPkg = launcherApps.isPackageEnabledForProfile(
                                                cn.getPackageName(), user);
                                        boolean validComponent = validPkg &&
                                                launcherApps.isActivityEnabledForProfile(cn, user);
                                        if (validPkg) {
                                            targetPackage = cn.getPackageName();
                                        }

                                        if (validComponent) {
                                            if (restored) {
                                                // no special handling necessary for this item
                                                restoredRows.add(id);
                                                restored = false;
                                            }
                                            if (quietMode.get(serialNumber)) {
                                                disabledState = ShortcutInfo.FLAG_DISABLED_QUIET_USER;
                                            }
                                        } else if (validPkg) {
                                            intent = null;
                                            if ((promiseType & ShortcutInfo.FLAG_AUTOINTALL_ICON) != 0) {
                                                // We allow auto install apps to have their intent
                                                // updated after an install.
                                                intent = manager.getLaunchIntentForPackage(
                                                        cn.getPackageName());
                                                if (intent != null) {
                                                    ContentValues values = new ContentValues();
                                                    values.put(LauncherSettings.Favorites.INTENT,
                                                            intent.toUri(0));
                                                    updateItem(id, values);
                                                }
                                            }

                                            if (intent == null) {
                                                // The app is installed but the component is no
                                                // longer available.
                                                FileLog.d(TAG, "Invalid component removed: " + cn);
                                                itemsToRemove.add(id);
                                                continue;
                                            } else {
                                                // no special handling necessary for this item
                                                restoredRows.add(id);
                                                restored = false;
                                            }
                                        } else if (restored) {
                                            // Package is not yet available but might be
                                            // installed later.
                                            FileLog.d(TAG, "package not yet restored: " + cn);

                                            if ((promiseType & ShortcutInfo.FLAG_RESTORE_STARTED) != 0) {
                                                // Restore has started once.
                                            } else if (installingPkgs.containsKey(cn.getPackageName())) {
                                                // App restore has started. Update the flag
                                                promiseType |= ShortcutInfo.FLAG_RESTORE_STARTED;
                                                ContentValues values = new ContentValues();
                                                values.put(LauncherSettings.Favorites.RESTORED,
                                                        promiseType);
                                                updateItem(id, values);
                                            } else if ((promiseType & ShortcutInfo.FLAG_RESTORED_APP_TYPE) != 0) {
                                                // This is a common app. Try to replace this.
                                                int appType = CommonAppTypeParser.decodeItemTypeFromFlag(promiseType);
                                                CommonAppTypeParser parser = new CommonAppTypeParser(id, appType, context);
                                                if (parser.findDefaultApp()) {
                                                    // Default app found. Replace it.
                                                    intent = parser.parsedIntent;
                                                    cn = intent.getComponent();
                                                    ContentValues values = parser.parsedValues;
                                                    values.put(LauncherSettings.Favorites.RESTORED, 0);
                                                    updateItem(id, values);
                                                    restored = false;
                                                    itemReplaced = true;

                                                } else {
                                                    FileLog.d(TAG, "Unrestored package removed: " + cn);
                                                    itemsToRemove.add(id);
                                                    continue;
                                                }
                                            } else {
                                                FileLog.d(TAG, "Unrestored package removed: " + cn);
                                                itemsToRemove.add(id);
                                                continue;
                                            }
                                        } else if (PackageManagerHelper.isAppOnSdcard(
                                                manager, cn.getPackageName())) {
                                            // Package is present but not available.
                                            allowMissingTarget = true;
                                            disabledState = ShortcutInfo.FLAG_DISABLED_NOT_AVAILABLE;
                                        } else if (!isSdCardReady) {
                                            // SdCard is not ready yet. Package might get available,
                                            // once it is ready.
                                            Log.d(TAG, "Invalid package: " + cn + " (check again later)");
                                            pendingPackages.addToList(user, cn.getPackageName());
                                            allowMissingTarget = true;
                                            // Add the icon on the workspace anyway.

                                        } else {
                                            // Do not wait for external media load anymore.
                                            // Log the invalid package, and remove it
                                            FileLog.d(TAG, "Invalid package removed: " + cn);
                                            itemsToRemove.add(id);
                                            continue;
                                        }
                                    } else if (cn == null) {
                                        // For shortcuts with no component, keep them as they are
                                        restoredRows.add(id);
                                        restored = false;
                                    }
                                } catch (URISyntaxException e) {
                                    FileLog.d(TAG, "Invalid uri: " + intentDescription);
                                    itemsToRemove.add(id);
                                    continue;
                                }

                                boolean useLowResIcon = container >= 0 &&
                                        c.getInt(rankIndex) >= FolderIcon.NUM_ITEMS_IN_PREVIEW;

                                if (itemReplaced) {
                                    if (user.equals(Process.myUserHandle())) {
                                        info = getAppShortcutInfo(intent, user, null,
                                                cursorIconInfo, false, useLowResIcon);
                                    } else {
                                        // Don't replace items for other profiles.
                                        itemsToRemove.add(id);
                                        continue;
                                    }
                                } else if (restored) {
                                    if (user.equals(Process.myUserHandle())) {
                                        info = getRestoredItemInfo(c, intent,
                                                promiseType, itemType, cursorIconInfo);
                                        intent = getRestoredItemIntent(c, context, intent);
                                    } else {
                                        // Don't restore items for other profiles.
                                        itemsToRemove.add(id);
                                        continue;
                                    }
                                } else if (itemType ==
                                        LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) {
                                    info = getAppShortcutInfo(intent, user, c,
                                            cursorIconInfo, allowMissingTarget, useLowResIcon);
                                } else if (itemType ==
                                        LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT) {

                                    ShortcutKey key = ShortcutKey.fromIntent(intent, user);
                                    if (unlockedUsers.get(serialNumber)) {
                                        ShortcutInfoCompat pinnedShortcut =
                                                shortcutKeyToPinnedShortcuts.get(key);
                                        if (pinnedShortcut == null) {
                                            // The shortcut is no longer valid.
                                            itemsToRemove.add(id);
                                            continue;
                                        }
                                        info = new ShortcutInfo(pinnedShortcut, context);
                                        intent = info.intent;
                                    } else {
                                        // Create a shortcut info in disabled mode for now.
                                        info = new ShortcutInfo();
                                        info.user = user;
                                        info.itemType = itemType;
                                        loadInfoFromCursor(info, c, cursorIconInfo);

                                        info.isDisabled |= ShortcutInfo.FLAG_DISABLED_LOCKED_USER;
                                    }
                                } else { // item type == ITEM_TYPE_SHORTCUT
                                    info = getShortcutInfo(c, cursorIconInfo);

                                    // Shortcuts are only available on the primary profile
                                    if (PackageManagerHelper.isAppSuspended(manager, targetPackage)) {
                                        disabledState |= ShortcutInfo.FLAG_DISABLED_SUSPENDED;
                                    }

                                    // App shortcuts that used to be automatically added to Launcher
                                    // didn't always have the correct intent flags set, so do that
                                    // here
                                    if (intent.getAction() != null &&
                                        intent.getCategories() != null &&
                                        intent.getAction().equals(Intent.ACTION_MAIN) &&
                                        intent.getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
                                        intent.addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK |
                                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                                    }
                                }

                                if (info != null) {
                                    info.id = id;
                                    info.intent = intent;
                                    info.container = container;
                                    info.screenId = c.getInt(screenIndex);
                                    info.cellX = c.getInt(cellXIndex);
                                    info.cellY = c.getInt(cellYIndex);
                                    info.rank = c.getInt(rankIndex);
                                    info.spanX = 1;
                                    info.spanY = 1;
                                    info.intent.putExtra(ItemInfo.EXTRA_PROFILE, serialNumber);
                                    if (info.promisedIntent != null) {
                                        info.promisedIntent.putExtra(ItemInfo.EXTRA_PROFILE, serialNumber);
                                    }
                                    info.isDisabled |= disabledState;
                                    if (isSafeMode && !Utilities.isSystemApp(context, intent)) {
                                        info.isDisabled |= ShortcutInfo.FLAG_DISABLED_SAFEMODE;
                                    }

                                    // check & update map of what's occupied
                                    if (!checkItemPlacement(occupied, info, sBgDataModel.workspaceScreens)) {
                                        itemsToRemove.add(id);
                                        break;
                                    }

                                    if (restored) {
                                        ComponentName cn = info.getTargetComponent();
                                        if (cn != null) {
                                            Integer progress = installingPkgs.get(cn.getPackageName());
                                            if (progress != null) {
                                                info.setInstallProgress(progress);
                                            } else {
                                                info.status &= ~ShortcutInfo.FLAG_INSTALL_SESSION_ACTIVE;
                                            }
                                        }
                                    }

                                    sBgDataModel.addItem(info, false);
                                } else {
                                    throw new RuntimeException("Unexpected null ShortcutInfo");
                                }
                                break;

                            case LauncherSettings.Favorites.ITEM_TYPE_FOLDER:
                                id = c.getLong(idIndex);
                                FolderInfo folderInfo = sBgDataModel.findOrMakeFolder(id);

                                // Do not trim the folder label, as is was set by the user.
                                folderInfo.title = c.getString(cursorIconInfo.titleIndex);
                                folderInfo.id = id;
                                folderInfo.container = container;
                                folderInfo.screenId = c.getInt(screenIndex);
                                folderInfo.cellX = c.getInt(cellXIndex);
                                folderInfo.cellY = c.getInt(cellYIndex);
                                folderInfo.spanX = 1;
                                folderInfo.spanY = 1;
                                folderInfo.options = c.getInt(optionsIndex);

                                // check & update map of what's occupied
                                if (!checkItemPlacement(occupied, folderInfo, sBgDataModel.workspaceScreens)) {
                                    itemsToRemove.add(id);
                                    break;
                                }
                                if (restored) {
                                    // no special handling required for restored folders
                                    restoredRows.add(id);
                                }

                                sBgDataModel.addItem(folderInfo, false);
                                break;

                            case LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET:
                            case LauncherSettings.Favorites.ITEM_TYPE_CUSTOM_APPWIDGET:
                                // Read all Launcher-specific widget details
                                boolean customWidget = itemType ==
                                    LauncherSettings.Favorites.ITEM_TYPE_CUSTOM_APPWIDGET;

                                int appWidgetId = c.getInt(appWidgetIdIndex);
                                serialNumber = c.getLong(profileIdIndex);
                                String savedProvider = c.getString(appWidgetProviderIndex);
                                id = c.getLong(idIndex);
                                user = allUsers.get(serialNumber);
                                if (user == null) {
                                    itemsToRemove.add(id);
                                    continue;
                                }

                                final ComponentName component =
                                        ComponentName.unflattenFromString(savedProvider);

                                final int restoreStatus = c.getInt(restoredIndex);
                                final boolean isIdValid = (restoreStatus &
                                        LauncherAppWidgetInfo.FLAG_ID_NOT_VALID) == 0;
                                final boolean wasProviderReady = (restoreStatus &
                                        LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY) == 0;

                                if (widgetProvidersMap == null) {
                                    widgetProvidersMap = AppWidgetManagerCompat
                                            .getInstance(mContext).getAllProvidersMap();
                                }
                                final AppWidgetProviderInfo provider = widgetProvidersMap.get(
                                        new ComponentKey(
                                                ComponentName.unflattenFromString(savedProvider),
                                                user));

                                final boolean isProviderReady = isValidProvider(provider);
                                if (!isSafeMode && !customWidget &&
                                        wasProviderReady && !isProviderReady) {
                                    FileLog.d(TAG, "Deleting widget that isn't installed anymore: "
                                            + provider);
                                    itemsToRemove.add(id);
                                } else {
                                    if (isProviderReady) {
                                        appWidgetInfo = new LauncherAppWidgetInfo(appWidgetId,
                                                provider.provider);

                                        // The provider is available. So the widget is either
                                        // available or not available. We do not need to track
                                        // any future restore updates.
                                        int status = restoreStatus &
                                                ~LauncherAppWidgetInfo.FLAG_RESTORE_STARTED;
                                        if (!wasProviderReady) {
                                            // If provider was not previously ready, update the
                                            // status and UI flag.

                                            // Id would be valid only if the widget restore broadcast was received.
                                            if (isIdValid) {
                                                status |= LauncherAppWidgetInfo.FLAG_UI_NOT_READY;
                                            } else {
                                                status &= ~LauncherAppWidgetInfo
                                                        .FLAG_PROVIDER_NOT_READY;
                                            }
                                        }
                                        appWidgetInfo.restoreStatus = status;
                                    } else {
                                        Log.v(TAG, "Widget restore pending id=" + id
                                                + " appWidgetId=" + appWidgetId
                                                + " status =" + restoreStatus);
                                        appWidgetInfo = new LauncherAppWidgetInfo(appWidgetId,
                                                component);
                                        appWidgetInfo.restoreStatus = restoreStatus;
                                        Integer installProgress = installingPkgs.get(component.getPackageName());

                                        if ((restoreStatus & LauncherAppWidgetInfo.FLAG_RESTORE_STARTED) != 0) {
                                            // Restore has started once.
                                        } else if (installProgress != null) {
                                            // App restore has started. Update the flag
                                            appWidgetInfo.restoreStatus |=
                                                    LauncherAppWidgetInfo.FLAG_RESTORE_STARTED;
                                        } else if (!isSafeMode) {
                                            FileLog.d(TAG, "Unrestored widget removed: " + component);
                                            itemsToRemove.add(id);
                                            continue;
                                        }

                                        appWidgetInfo.installProgress =
                                                installProgress == null ? 0 : installProgress;
                                    }
                                    if (appWidgetInfo.hasRestoreFlag(
                                            LauncherAppWidgetInfo.FLAG_DIRECT_CONFIG)) {
                                        intentDescription = c.getString(intentIndex);
                                        if (!TextUtils.isEmpty(intentDescription)) {
                                            appWidgetInfo.bindOptions =
                                                    Intent.parseUri(intentDescription, 0);
                                        }
                                    }

                                    appWidgetInfo.id = id;
                                    appWidgetInfo.screenId = c.getInt(screenIndex);
                                    appWidgetInfo.cellX = c.getInt(cellXIndex);
                                    appWidgetInfo.cellY = c.getInt(cellYIndex);
                                    appWidgetInfo.spanX = c.getInt(spanXIndex);
                                    appWidgetInfo.spanY = c.getInt(spanYIndex);
                                    appWidgetInfo.user = user;

                                    if (container != LauncherSettings.Favorites.CONTAINER_DESKTOP &&
                                        container != LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                                        Log.e(TAG, "Widget found where container != " +
                                                "CONTAINER_DESKTOP nor CONTAINER_HOTSEAT - ignoring!");
                                        itemsToRemove.add(id);
                                        continue;
                                    }

                                    appWidgetInfo.container = container;
                                    // check & update map of what's occupied
                                    if (!checkItemPlacement(occupied, appWidgetInfo, sBgDataModel.workspaceScreens)) {
                                        itemsToRemove.add(id);
                                        break;
                                    }

                                    if (!customWidget) {
                                        String providerName =
                                                appWidgetInfo.providerName.flattenToString();
                                        if (!providerName.equals(savedProvider) ||
                                                (appWidgetInfo.restoreStatus != restoreStatus)) {
                                            ContentValues values = new ContentValues();
                                            values.put(
                                                    LauncherSettings.Favorites.APPWIDGET_PROVIDER,
                                                    providerName);
                                            values.put(LauncherSettings.Favorites.RESTORED,
                                                    appWidgetInfo.restoreStatus);
                                            updateItem(id, values);
                                        }
                                    }
                                    sBgDataModel.addItem(appWidgetInfo, false);
                                }
                                break;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Desktop items loading interrupted", e);
                        }
                    }
                } finally {
                    Utilities.closeSilently(c);
                }

                // Break early if we've stopped loading
                if (mStopped) {
                    sBgDataModel.clear();
                    return;
                }

                if (itemsToRemove.size() > 0) {
                    // Remove dead items
                    contentResolver.delete(LauncherSettings.Favorites.CONTENT_URI,
                            Utilities.createDbSelectionQuery(
                                    LauncherSettings.Favorites._ID, itemsToRemove), null);
                    if (DEBUG_LOADERS) {
                        Log.d(TAG, "Removed = " + Utilities.createDbSelectionQuery(
                                LauncherSettings.Favorites._ID, itemsToRemove));
                    }

                    // Remove any empty folder
                    ArrayList<Long> deletedFolderIds = (ArrayList<Long>) LauncherSettings.Settings
                            .call(contentResolver,
                                    LauncherSettings.Settings.METHOD_DELETE_EMPTY_FOLDERS)
                            .getSerializable(LauncherSettings.Settings.EXTRA_VALUE);
                    for (long folderId : deletedFolderIds) {
                        sBgDataModel.workspaceItems.remove(sBgDataModel.folders.get(folderId));
                        sBgDataModel.folders.remove(folderId);
                        sBgDataModel.itemsIdMap.remove(folderId);
                    }
                }

                // Unpin shortcuts that don't exist on the workspace.
                HashSet<ShortcutKey> pendingShortcuts =
                        InstallShortcutReceiver.getPendingShortcuts(context);
                for (ShortcutKey key : shortcutKeyToPinnedShortcuts.keySet()) {
                    MutableInt numTimesPinned = sBgDataModel.pinnedShortcutCounts.get(key);
                    if ((numTimesPinned == null || numTimesPinned.value == 0)
                            && !pendingShortcuts.contains(key)) {
                        // Shortcut is pinned but doesn't exist on the workspace; unpin it.
                        shortcutManager.unpinShortcut(key);
                    }
                }

                // Sort all the folder items and make sure the first 3 items are high resolution.
                for (FolderInfo folder : sBgDataModel.folders) {
                    Collections.sort(folder.contents, Folder.ITEM_POS_COMPARATOR);
                    int pos = 0;
                    for (ShortcutInfo info : folder.contents) {
                        if (info.usingLowResIcon &&
                                info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) {
                            mIconCache.getTitleAndIcon(info, false);
                        }
                        pos ++;
                        if (pos >= FolderIcon.NUM_ITEMS_IN_PREVIEW) {
                            break;
                        }
                    }
                }

                if (restoredRows.size() > 0) {
                    // Update restored items that no longer require special handling
                    ContentValues values = new ContentValues();
                    values.put(LauncherSettings.Favorites.RESTORED, 0);
                    contentResolver.update(LauncherSettings.Favorites.CONTENT_URI, values,
                            Utilities.createDbSelectionQuery(
                                    LauncherSettings.Favorites._ID, restoredRows), null);
                }

                if (!isSdCardReady && !pendingPackages.isEmpty()) {
                    context.registerReceiver(
                            new SdCardAvailableReceiver(
                                    LauncherModel.this, mContext, pendingPackages),
                            new IntentFilter(Intent.ACTION_BOOT_COMPLETED),
                            null,
                            sWorker);
                }

                // Remove any empty screens
                ArrayList<Long> unusedScreens = new ArrayList<Long>(sBgDataModel.workspaceScreens);
                for (ItemInfo item: sBgDataModel.itemsIdMap) {
                    long screenId = item.screenId;
                    if (item.container == LauncherSettings.Favorites.CONTAINER_DESKTOP &&
                            unusedScreens.contains(screenId)) {
                        unusedScreens.remove(screenId);
                    }
                }

                // If there are any empty screens remove them, and update.
                if (unusedScreens.size() != 0) {
                    sBgDataModel.workspaceScreens.removeAll(unusedScreens);
                    updateWorkspaceScreenOrder(context, sBgDataModel.workspaceScreens);
                }

                if (DEBUG_LOADERS) {
                    Log.d(TAG, "loaded workspace in " + (SystemClock.uptimeMillis()-t) + "ms");
                    Log.d(TAG, "workspace layout: ");
                    int nScreens = occupied.size();
                    for (int y = 0; y < countY; y++) {
                        String line = "";

                        for (int i = 0; i < nScreens; i++) {
                            long screenId = occupied.keyAt(i);
                            if (screenId > 0) {
                                line += " | ";
                            }
                        }
                        Log.d(TAG, "[ " + line + " ]");
                    }
                }
            }
            if (LauncherAppState.PROFILE_STARTUP) {
                Trace.endSection();
            }
        }

        /**
         * Partially updates the item without any notification. Must be called on the worker thread.
         */
        private void updateItem(long itemId, ContentValues update) {
            mContext.getContentResolver().update(
                    LauncherSettings.Favorites.CONTENT_URI,
                    update,
                    BaseColumns._ID + "= ?",
                    new String[]{Long.toString(itemId)});
        }

        /** Filters the set of items who are directly or indirectly (via another container) on the
         * specified screen. */
        private void filterCurrentWorkspaceItems(long currentScreenId,
                ArrayList<ItemInfo> allWorkspaceItems,
                ArrayList<ItemInfo> currentScreenItems,
                ArrayList<ItemInfo> otherScreenItems) {
            // Purge any null ItemInfos
            Iterator<ItemInfo> iter = allWorkspaceItems.iterator();
            while (iter.hasNext()) {
                ItemInfo i = iter.next();
                if (i == null) {
                    iter.remove();
                }
            }

            // Order the set of items by their containers first, this allows use to walk through the
            // list sequentially, build up a list of containers that are in the specified screen,
            // as well as all items in those containers.
            Set<Long> itemsOnScreen = new HashSet<Long>();
            Collections.sort(allWorkspaceItems, new Comparator<ItemInfo>() {
                @Override
                public int compare(ItemInfo lhs, ItemInfo rhs) {
                    return Utilities.longCompare(lhs.container, rhs.container);
                }
            });
            for (ItemInfo info : allWorkspaceItems) {
                if (info.container == LauncherSettings.Favorites.CONTAINER_DESKTOP) {
                    if (info.screenId == currentScreenId) {
                        currentScreenItems.add(info);
                        itemsOnScreen.add(info.id);
                    } else {
                        otherScreenItems.add(info);
                    }
                } else if (info.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                    currentScreenItems.add(info);
                    itemsOnScreen.add(info.id);
                } else {
                    if (itemsOnScreen.contains(info.container)) {
                        currentScreenItems.add(info);
                        itemsOnScreen.add(info.id);
                    } else {
                        otherScreenItems.add(info);
                    }
                }
            }
        }

        /** Filters the set of widgets which are on the specified screen. */
        private void filterCurrentAppWidgets(long currentScreenId,
                ArrayList<LauncherAppWidgetInfo> appWidgets,
                ArrayList<LauncherAppWidgetInfo> currentScreenWidgets,
                ArrayList<LauncherAppWidgetInfo> otherScreenWidgets) {

            for (LauncherAppWidgetInfo widget : appWidgets) {
                if (widget == null) continue;
                if (widget.container == LauncherSettings.Favorites.CONTAINER_DESKTOP &&
                        widget.screenId == currentScreenId) {
                    currentScreenWidgets.add(widget);
                } else {
                    otherScreenWidgets.add(widget);
                }
            }
        }

        /** Sorts the set of items by hotseat, workspace (spatially from top to bottom, left to
         * right) */
        private void sortWorkspaceItemsSpatially(ArrayList<ItemInfo> workspaceItems) {
            final LauncherAppState app = LauncherAppState.getInstance();
            final InvariantDeviceProfile profile = app.getInvariantDeviceProfile();
            final int screenCols = profile.numColumns;
            final int screenCellCount = profile.numColumns * profile.numRows;
            Collections.sort(workspaceItems, new Comparator<ItemInfo>() {
                @Override
                public int compare(ItemInfo lhs, ItemInfo rhs) {
                    if (lhs.container == rhs.container) {
                        // Within containers, order by their spatial position in that container
                        switch ((int) lhs.container) {
                            case LauncherSettings.Favorites.CONTAINER_DESKTOP: {
                                long lr = (lhs.screenId * screenCellCount +
                                        lhs.cellY * screenCols + lhs.cellX);
                                long rr = (rhs.screenId * screenCellCount +
                                        rhs.cellY * screenCols + rhs.cellX);
                                return Utilities.longCompare(lr, rr);
                            }
                            case LauncherSettings.Favorites.CONTAINER_HOTSEAT: {
                                // We currently use the screen id as the rank
                                return Utilities.longCompare(lhs.screenId, rhs.screenId);
                            }
                            default:
                                if (ProviderConfig.IS_DOGFOOD_BUILD) {
                                    throw new RuntimeException("Unexpected container type when " +
                                            "sorting workspace items.");
                                }
                                return 0;
                        }
                    } else {
                        // Between containers, order by hotseat, desktop
                        return Utilities.longCompare(lhs.container, rhs.container);
                    }
                }
            });
        }

        private void bindWorkspaceScreens(final Callbacks oldCallbacks,
                final ArrayList<Long> orderedScreens) {
            final Runnable r = new Runnable() {
                @Override
                public void run() {
                    Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.bindScreens(orderedScreens);
                    }
                }
            };
            runOnMainThread(r);
        }

        private void bindWorkspaceItems(final Callbacks oldCallbacks,
                final ArrayList<ItemInfo> workspaceItems,
                final ArrayList<LauncherAppWidgetInfo> appWidgets,
                final Executor executor) {

            // Bind the workspace items
            int N = workspaceItems.size();
            for (int i = 0; i < N; i += ITEMS_CHUNK) {
                final int start = i;
                final int chunkSize = (i+ITEMS_CHUNK <= N) ? ITEMS_CHUNK : (N-i);
                final Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                        if (callbacks != null) {
                            callbacks.bindItems(workspaceItems, start, start+chunkSize,
                                    false);
                        }
                    }
                };
                executor.execute(r);
            }

            // Bind the widgets, one at a time
            N = appWidgets.size();
            for (int i = 0; i < N; i++) {
                final LauncherAppWidgetInfo widget = appWidgets.get(i);
                final Runnable r = new Runnable() {
                    public void run() {
                        Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                        if (callbacks != null) {
                            callbacks.bindAppWidget(widget);
                        }
                    }
                };
                executor.execute(r);
            }
        }

        /**
         * Binds all loaded data to actual views on the main thread.
         */
        private void bindWorkspace(int synchronizeBindPage) {
            final long t = SystemClock.uptimeMillis();
            Runnable r;

            // Don't use these two variables in any of the callback runnables.
            // Otherwise we hold a reference to them.
            final Callbacks oldCallbacks = mCallbacks.get();
            if (oldCallbacks == null) {
                // This launcher has exited and nobody bothered to tell us.  Just bail.
                Log.w(TAG, "LoaderTask running with no launcher");
                return;
            }

            // Save a copy of all the bg-thread collections
            ArrayList<ItemInfo> workspaceItems = new ArrayList<>();
            ArrayList<LauncherAppWidgetInfo> appWidgets = new ArrayList<>();
            ArrayList<Long> orderedScreenIds = new ArrayList<>();

            synchronized (sBgDataModel) {
                workspaceItems.addAll(sBgDataModel.workspaceItems);
                appWidgets.addAll(sBgDataModel.appWidgets);
                orderedScreenIds.addAll(sBgDataModel.workspaceScreens);
            }

            final int currentScreen;
            {
                int currScreen = synchronizeBindPage != PagedView.INVALID_RESTORE_PAGE
                        ? synchronizeBindPage : oldCallbacks.getCurrentWorkspaceScreen();
                if (currScreen >= orderedScreenIds.size()) {
                    // There may be no workspace screens (just hotseat items and an empty page).
                    currScreen = PagedView.INVALID_RESTORE_PAGE;
                }
                currentScreen = currScreen;
            }
            final boolean validFirstPage = currentScreen >= 0;
            final long currentScreenId =
                    validFirstPage ? orderedScreenIds.get(currentScreen) : INVALID_SCREEN_ID;

            // Separate the items that are on the current screen, and all the other remaining items
            ArrayList<ItemInfo> currentWorkspaceItems = new ArrayList<>();
            ArrayList<ItemInfo> otherWorkspaceItems = new ArrayList<>();
            ArrayList<LauncherAppWidgetInfo> currentAppWidgets = new ArrayList<>();
            ArrayList<LauncherAppWidgetInfo> otherAppWidgets = new ArrayList<>();

            filterCurrentWorkspaceItems(currentScreenId, workspaceItems, currentWorkspaceItems,
                    otherWorkspaceItems);
            filterCurrentAppWidgets(currentScreenId, appWidgets, currentAppWidgets,
                    otherAppWidgets);
            sortWorkspaceItemsSpatially(currentWorkspaceItems);
            sortWorkspaceItemsSpatially(otherWorkspaceItems);

            // Tell the workspace that we're about to start binding items
            r = new Runnable() {
                public void run() {
                    Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.clearPendingBinds();
                        callbacks.startBinding();
                    }
                }
            };
            runOnMainThread(r);

            bindWorkspaceScreens(oldCallbacks, orderedScreenIds);

            Executor mainExecutor = new DeferredMainThreadExecutor();
            // Load items on the current page.
            bindWorkspaceItems(oldCallbacks, currentWorkspaceItems, currentAppWidgets, mainExecutor);

            // In case of validFirstPage, only bind the first screen, and defer binding the
            // remaining screens after first onDraw (and an optional the fade animation whichever
            // happens later).
            // This ensures that the first screen is immediately visible (eg. during rotation)
            // In case of !validFirstPage, bind all pages one after other.
            final Executor deferredExecutor =
                    validFirstPage ? new ViewOnDrawExecutor(mHandler) : mainExecutor;

            mainExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.finishFirstPageBind(
                                validFirstPage ? (ViewOnDrawExecutor) deferredExecutor : null);
                    }
                }
            });

            bindWorkspaceItems(oldCallbacks, otherWorkspaceItems, otherAppWidgets, deferredExecutor);

            // Tell the workspace that we're done binding items
            r = new Runnable() {
                public void run() {
                    Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.finishBindingItems();
                    }

                    mIsLoadingAndBindingWorkspace = false;

                    // Run all the bind complete runnables after workspace is bound.
                    if (!mBindCompleteRunnables.isEmpty()) {
                        synchronized (mBindCompleteRunnables) {
                            for (final Runnable r : mBindCompleteRunnables) {
                                runOnWorkerThread(r);
                            }
                            mBindCompleteRunnables.clear();
                        }
                    }

                    // If we're profiling, ensure this is the last thing in the queue.
                    if (DEBUG_LOADERS) {
                        Log.d(TAG, "bound workspace in "
                            + (SystemClock.uptimeMillis()-t) + "ms");
                    }

                }
            };
            deferredExecutor.execute(r);

            if (validFirstPage) {
                r = new Runnable() {
                    public void run() {
                        Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                        if (callbacks != null) {
                            // We are loading synchronously, which means, some of the pages will be
                            // bound after first draw. Inform the callbacks that page binding is
                            // not complete, and schedule the remaining pages.
                            if (currentScreen != PagedView.INVALID_RESTORE_PAGE) {
                                callbacks.onPageBoundSynchronously(currentScreen);
                            }
                            callbacks.executeOnNextDraw((ViewOnDrawExecutor) deferredExecutor);
                        }
                    }
                };
                runOnMainThread(r);
            }
        }

        private void loadAndBindAllApps() {
            if (DEBUG_LOADERS) {
                Log.d(TAG, "loadAndBindAllApps mAllAppsLoaded=" + mAllAppsLoaded);
            }
            if (!mAllAppsLoaded) {
                loadAllApps();
                synchronized (LoaderTask.this) {
                    if (mStopped) {
                        return;
                    }
                }
                updateIconCache();
                synchronized (LoaderTask.this) {
                    if (mStopped) {
                        return;
                    }
                    mAllAppsLoaded = true;
                }
            } else {
                onlyBindAllApps();
            }
        }

        private void updateIconCache() {
            // Ignore packages which have a promise icon.
            HashSet<String> packagesToIgnore = new HashSet<>();
            synchronized (sBgDataModel) {
                for (ItemInfo info : sBgDataModel.itemsIdMap) {
                    if (info instanceof ShortcutInfo) {
                        ShortcutInfo si = (ShortcutInfo) info;
                        if (si.isPromise() && si.getTargetComponent() != null) {
                            packagesToIgnore.add(si.getTargetComponent().getPackageName());
                        }
                    } else if (info instanceof LauncherAppWidgetInfo) {
                        LauncherAppWidgetInfo lawi = (LauncherAppWidgetInfo) info;
                        if (lawi.hasRestoreFlag(LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY)) {
                            packagesToIgnore.add(lawi.providerName.getPackageName());
                        }
                    }
                }
            }
            mIconCache.updateDbIcons(packagesToIgnore);
        }

        private void onlyBindAllApps() {
            final Callbacks oldCallbacks = mCallbacks.get();
            if (oldCallbacks == null) {
                // This launcher has exited and nobody bothered to tell us.  Just bail.
                Log.w(TAG, "LoaderTask running with no launcher (onlyBindAllApps)");
                return;
            }

            // shallow copy
            @SuppressWarnings("unchecked")
            final ArrayList<AppInfo> list
                    = (ArrayList<AppInfo>) mBgAllAppsList.data.clone();
            Runnable r = new Runnable() {
                public void run() {
                    final long t = SystemClock.uptimeMillis();
                    final Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.bindAllApplications(list);
                    }
                    if (DEBUG_LOADERS) {
                        Log.d(TAG, "bound all " + list.size() + " apps from cache in "
                                + (SystemClock.uptimeMillis() - t) + "ms");
                    }
                }
            };
            runOnMainThread(r);
        }

        private void loadAllApps() {
            final long loadTime = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;

            final Callbacks oldCallbacks = mCallbacks.get();
            if (oldCallbacks == null) {
                // This launcher has exited and nobody bothered to tell us.  Just bail.
                Log.w(TAG, "LoaderTask running with no launcher (loadAllApps)");
                return;
            }

            final List<UserHandle> profiles = mUserManager.getUserProfiles();

            // Clear the list of apps
            mBgAllAppsList.clear();
            for (UserHandle user : profiles) {
                // Query for the set of apps
                final long qiaTime = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;
                final List<LauncherActivityInfoCompat> apps = mLauncherApps.getActivityList(null, user);
                if (DEBUG_LOADERS) {
                    Log.d(TAG, "getActivityList took "
                            + (SystemClock.uptimeMillis()-qiaTime) + "ms for user " + user);
                    Log.d(TAG, "getActivityList got " + apps.size() + " apps for user " + user);
                }
                // Fail if we don't have any apps
                // TODO: Fix this. Only fail for the current user.
                if (apps == null || apps.isEmpty()) {
                    return;
                }
                boolean quietMode = mUserManager.isQuietModeEnabled(user);
                // Create the ApplicationInfos
                for (int i = 0; i < apps.size(); i++) {
                    LauncherActivityInfoCompat app = apps.get(i);
                    // This builds the icon bitmaps.
                    mBgAllAppsList.add(new AppInfo(mContext, app, user, mIconCache, quietMode));
                }

                final ManagedProfileHeuristic heuristic = ManagedProfileHeuristic.get(mContext, user);
                if (heuristic != null) {
                    final Runnable r = new Runnable() {

                        @Override
                        public void run() {
                            heuristic.processUserApps(apps);
                        }
                    };
                    runOnMainThread(new Runnable() {

                        @Override
                        public void run() {
                            // Check isLoadingWorkspace on the UI thread, as it is updated on
                            // the UI thread.
                            if (mIsLoadingAndBindingWorkspace) {
                                synchronized (mBindCompleteRunnables) {
                                    mBindCompleteRunnables.add(r);
                                }
                            } else {
                                runOnWorkerThread(r);
                            }
                        }
                    });
                }
            }
            // Huh? Shouldn't this be inside the Runnable below?
            final ArrayList<AppInfo> added = mBgAllAppsList.added;
            mBgAllAppsList.added = new ArrayList<AppInfo>();

            // Post callback on main thread
            mHandler.post(new Runnable() {
                public void run() {

                    final long bindTime = SystemClock.uptimeMillis();
                    final Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.bindAllApplications(added);
                        if (DEBUG_LOADERS) {
                            Log.d(TAG, "bound " + added.size() + " apps in "
                                    + (SystemClock.uptimeMillis() - bindTime) + "ms");
                        }
                    } else {
                        Log.i(TAG, "not binding apps: no Launcher activity");
                    }
                }
            });
            // Cleanup any data stored for a deleted user.
            ManagedProfileHeuristic.processAllUsers(profiles, mContext);
            if (DEBUG_LOADERS) {
                Log.d(TAG, "Icons processed in "
                        + (SystemClock.uptimeMillis() - loadTime) + "ms");
            }
        }

        private void loadAndBindDeepShortcuts() {
            if (DEBUG_LOADERS) {
                Log.d(TAG, "loadAndBindDeepShortcuts mDeepShortcutsLoaded=" + mDeepShortcutsLoaded);
            }
            if (!mDeepShortcutsLoaded) {
                sBgDataModel.deepShortcutMap.clear();
                DeepShortcutManager shortcutManager = DeepShortcutManager.getInstance(mContext);
                mHasShortcutHostPermission = shortcutManager.hasHostPermission();
                if (mHasShortcutHostPermission) {
                    for (UserHandle user : mUserManager.getUserProfiles()) {
                        if (mUserManager.isUserUnlocked(user)) {
                            List<ShortcutInfoCompat> shortcuts =
                                    shortcutManager.queryForAllShortcuts(user);
                            sBgDataModel.updateDeepShortcutMap(null, user, shortcuts);
                        }
                    }
                }
                synchronized (LoaderTask.this) {
                    if (mStopped) {
                        return;
                    }
                    mDeepShortcutsLoaded = true;
                }
            }
            bindDeepShortcuts();
        }

        public void dumpState() {
            synchronized (sBgDataModel) {
                Log.d(TAG, "mLoaderTask.mContext=" + mContext);
                Log.d(TAG, "mLoaderTask.mStopped=" + mStopped);
                Log.d(TAG, "mLoaderTask.mLoadAndBindStepFinished=" + mLoadAndBindStepFinished);
                Log.d(TAG, "mItems size=" + sBgDataModel.workspaceItems.size());
            }
        }
    }

    public void bindDeepShortcuts() {
        final MultiHashMap<ComponentKey, String> shortcutMapCopy =
                sBgDataModel.deepShortcutMap.clone();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Callbacks callbacks = getCallback();
                if (callbacks != null) {
                    callbacks.bindDeepShortcutMap(shortcutMapCopy);
                }
            }
        };
        runOnMainThread(r);
    }

    /**
     * Refreshes the cached shortcuts if the shortcut permission has changed.
     * Current implementation simply reloads the workspace, but it can be optimized to
     * use partial updates similar to {@link UserManagerCompat}
     */
    public void refreshShortcutsIfRequired() {
        if (Utilities.ATLEAST_NOUGAT_MR1) {
            sWorker.removeCallbacks(mShortcutPermissionCheckRunnable);
            sWorker.post(mShortcutPermissionCheckRunnable);
        }
    }

    /**
     * Called when the icons for packages have been updated in the icon cache.
     */
    public void onPackageIconsUpdated(HashSet<String> updatedPackages, UserHandle user) {
        // If any package icon has changed (app was updated while launcher was dead),
        // update the corresponding shortcuts.
        enqueueModelUpdateTask(new CacheDataUpdatedTask(
                CacheDataUpdatedTask.OP_CACHE_UPDATE, user, updatedPackages));
    }

    void enqueueModelUpdateTask(BaseModelUpdateTask task) {
        task.init(this);
        runOnWorkerThread(task);
    }

    /**
     * A task to be executed on the current callbacks on the UI thread.
     * If there is no current callbacks, the task is ignored.
     */
    public interface CallbackTask {

        void execute(Callbacks callbacks);
    }

    /**
     * A runnable which changes/updates the data model of the launcher based on certain events.
     */
    public static abstract class BaseModelUpdateTask implements Runnable {

        private LauncherModel mModel;
        private DeferredHandler mUiHandler;

        /* package private */
        void init(LauncherModel model) {
            mModel = model;
            mUiHandler = mModel.mHandler;
        }

        @Override
        public void run() {
            if (!mModel.mHasLoaderCompletedOnce) {
                // Loader has not yet run.
                return;
            }
            execute(mModel.mApp, sBgDataModel, mModel.mBgAllAppsList);
        }

        /**
         * Execute the actual task. Called on the worker thread.
         */
        public abstract void execute(
                LauncherAppState app, BgDataModel dataModel, AllAppsList apps);

        /**
         * Schedules a {@param task} to be executed on the current callbacks.
         */
        public final void scheduleCallbackTask(final CallbackTask task) {
            final Callbacks callbacks = mModel.getCallback();
            mUiHandler.post(new Runnable() {
                public void run() {
                    Callbacks cb = mModel.getCallback();
                    if (callbacks == cb && cb != null) {
                        task.execute(callbacks);
                    }
                }
            });
        }
    }

    /**
     * Repopulates the shortcut info, possibly updating any icon already on the workspace.
     */
    public void updateShortcutInfo(final ShortcutInfoCompat fullDetail, final ShortcutInfo info) {
        enqueueModelUpdateTask(new ExtendedModelTask() {
            @Override
            public void execute(LauncherAppState app, BgDataModel dataModel, AllAppsList apps) {
                info.updateFromDeepShortcutInfo(fullDetail, app.getContext());

                ArrayList<ShortcutInfo> update = new ArrayList<>();
                update.add(info);
                bindUpdatedShortcuts(update, fullDetail.getUserHandle());
            }
        });
    }

    private void bindWidgetsModel(final Callbacks callbacks) {
        final MultiHashMap<PackageItemInfo, WidgetItem> widgets
                = mBgWidgetsModel.getWidgetsMap().clone();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Callbacks cb = getCallback();
                if (callbacks == cb && cb != null) {
                    callbacks.bindAllWidgets(widgets);
                }
            }
        });
    }

    public void refreshAndBindWidgetsAndShortcuts(
            final Callbacks callbacks, final boolean bindFirst) {
        runOnWorkerThread(new Runnable() {
            @Override
            public void run() {
                if (bindFirst && !mBgWidgetsModel.isEmpty()) {
                    bindWidgetsModel(callbacks);
                }
                ArrayList<WidgetItem> allWidgets = mBgWidgetsModel.update(mApp.getContext());
                bindWidgetsModel(callbacks);

                // update the Widget entries inside DB on the worker thread.
                LauncherAppState.getInstance().getWidgetCache().removeObsoletePreviews(allWidgets);
            }
        });
    }

    /**
     * Make an ShortcutInfo object for a restored application or shortcut item that points
     * to a package that is not yet installed on the system.
     */
    public ShortcutInfo getRestoredItemInfo(Cursor c, Intent intent,
            int promiseType, int itemType, CursorIconInfo iconInfo) {
        final ShortcutInfo info = new ShortcutInfo();
        info.user = Process.myUserHandle();
        info.promisedIntent = intent;

        info.iconBitmap = iconInfo.loadIcon(c, info);
        // the fallback icon
        if (info.iconBitmap == null) {
            mIconCache.getTitleAndIcon(info, false /* useLowResIcon */);
        }

        if ((promiseType & ShortcutInfo.FLAG_RESTORED_ICON) != 0) {
            String title = iconInfo.getTitle(c);
            if (!TextUtils.isEmpty(title)) {
                info.title = Utilities.trim(title);
            }
        } else if  ((promiseType & ShortcutInfo.FLAG_AUTOINTALL_ICON) != 0) {
            if (TextUtils.isEmpty(info.title)) {
                info.title = iconInfo.getTitle(c);
            }
        } else {
            throw new InvalidParameterException("Invalid restoreType " + promiseType);
        }

        info.contentDescription = mUserManager.getBadgedLabelForUser(info.title, info.user);
        info.itemType = itemType;
        info.status = promiseType;
        return info;
    }

    /**
     * Make an Intent object for a restored application or shortcut item that points
     * to the market page for the item.
     */
    @Thunk Intent getRestoredItemIntent(Cursor c, Context context, Intent intent) {
        ComponentName componentName = intent.getComponent();
        return getMarketIntent(componentName.getPackageName());
    }

    static Intent getMarketIntent(String packageName) {
        return new Intent(Intent.ACTION_VIEW)
            .setData(new Uri.Builder()
                .scheme("market")
                .authority("details")
                .appendQueryParameter("id", packageName)
                .build());
    }

    /**
     * Make an ShortcutInfo object for a shortcut that is an application.
     *
     * If c is not null, then it will be used to fill in missing data like the title and icon.
     */
    public ShortcutInfo getAppShortcutInfo(Intent intent, UserHandle user, Cursor c,
            CursorIconInfo iconInfo, boolean allowMissingTarget, boolean useLowResIcon) {
        if (user == null) {
            Log.d(TAG, "Null user found in getShortcutInfo");
            return null;
        }

        ComponentName componentName = intent.getComponent();
        if (componentName == null) {
            Log.d(TAG, "Missing component found in getShortcutInfo");
            return null;
        }

        Intent newIntent = new Intent(intent.getAction(), null);
        newIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        newIntent.setComponent(componentName);
        LauncherActivityInfoCompat lai = mLauncherApps.resolveActivity(newIntent, user);
        if ((lai == null) && !allowMissingTarget) {
            Log.d(TAG, "Missing activity found in getShortcutInfo: " + componentName);
            return null;
        }

        final ShortcutInfo info = new ShortcutInfo();
        info.itemType = LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;
        info.user = user;
        info.intent = newIntent;

        mIconCache.getTitleAndIcon(info, lai, useLowResIcon);
        if (mIconCache.isDefaultIcon(info.iconBitmap, user) && c != null) {
            Bitmap icon = iconInfo.loadIcon(c);
            info.iconBitmap = icon != null ? icon : mIconCache.getDefaultIcon(user);
        }

        if (lai != null && PackageManagerHelper.isAppSuspended(lai.getApplicationInfo())) {
            info.isDisabled = ShortcutInfo.FLAG_DISABLED_SUSPENDED;
        }

        // from the db
        if (TextUtils.isEmpty(info.title) && c != null) {
            info.title = iconInfo.getTitle(c);
        }

        // fall back to the class name of the activity
        if (info.title == null) {
            info.title = componentName.getClassName();
        }

        info.contentDescription = mUserManager.getBadgedLabelForUser(info.title, info.user);
        return info;
    }

    /**
     * Make an ShortcutInfo object for a shortcut that isn't an application.
     */
    @Thunk ShortcutInfo getShortcutInfo(Cursor c, CursorIconInfo iconInfo) {
        final ShortcutInfo info = new ShortcutInfo();
        // Non-app shortcuts are only supported for current user.
        info.user = Process.myUserHandle();
        info.itemType = LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT;

        // TODO: If there's an explicit component and we can't install that, delete it.

        loadInfoFromCursor(info, c, iconInfo);
        return info;
    }

    /**
     * Make an ShortcutInfo object for a shortcut that isn't an application.
     */
    public void loadInfoFromCursor(ShortcutInfo info, Cursor c, CursorIconInfo iconInfo) {
        info.title = iconInfo.getTitle(c);
        info.iconBitmap = iconInfo.loadIcon(c, info);
        // the fallback icon
        if (info.iconBitmap == null) {
            info.iconBitmap = mIconCache.getDefaultIcon(info.user);
        }
    }

    ShortcutInfo infoFromShortcutIntent(Context context, Intent data) {
        Intent intent = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
        String name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);
        Parcelable bitmap = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON);

        if (intent == null) {
            // If the intent is null, we can't construct a valid ShortcutInfo, so we return null
            Log.e(TAG, "Can't construct ShorcutInfo with null intent");
            return null;
        }

        final ShortcutInfo info = new ShortcutInfo();

        // Only support intents for current user for now. Intents sent from other
        // users wouldn't get here without intent forwarding anyway.
        info.user = Process.myUserHandle();

        if (bitmap instanceof Bitmap) {
            info.iconBitmap = LauncherIcons.createIconBitmap((Bitmap) bitmap, context);
        } else {
            Parcelable extra = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE);
            if (extra instanceof ShortcutIconResource) {
                info.iconResource = (ShortcutIconResource) extra;
                info.iconBitmap = LauncherIcons.createIconBitmap(info.iconResource, context);
            }
        }
        if (info.iconBitmap == null) {
            info.iconBitmap = mIconCache.getDefaultIcon(info.user);
        }

        info.title = Utilities.trim(name);
        info.contentDescription = mUserManager.getBadgedLabelForUser(info.title, info.user);
        info.intent = intent;

        return info;
    }

    static boolean isValidProvider(AppWidgetProviderInfo provider) {
        return (provider != null) && (provider.provider != null)
                && (provider.provider.getPackageName() != null);
    }

    public void dumpState() {
        Log.d(TAG, "mCallbacks=" + mCallbacks);
        AppInfo.dumpApplicationInfoList(TAG, "mAllAppsList.data", mBgAllAppsList.data);
        AppInfo.dumpApplicationInfoList(TAG, "mAllAppsList.added", mBgAllAppsList.added);
        AppInfo.dumpApplicationInfoList(TAG, "mAllAppsList.removed", mBgAllAppsList.removed);
        AppInfo.dumpApplicationInfoList(TAG, "mAllAppsList.modified", mBgAllAppsList.modified);
        if (mLoaderTask != null) {
            mLoaderTask.dumpState();
        } else {
            Log.d(TAG, "mLoaderTask=null");
        }
    }

    public Callbacks getCallback() {
        return mCallbacks != null ? mCallbacks.get() : null;
    }

    /**
     * @return {@link FolderInfo} if its already loaded.
     */
    public FolderInfo findFolderById(Long folderId) {
        synchronized (sBgDataModel) {
            return sBgDataModel.folders.get(folderId);
        }
    }

    @Thunk class DeferredMainThreadExecutor implements Executor {

        @Override
        public void execute(Runnable command) {
            runOnMainThread(command);
        }
    }

    /**
     * @return the looper for the worker thread which can be used to start background tasks.
     */
    public static Looper getWorkerLooper() {
        return sWorkerThread.getLooper();
    }
}
