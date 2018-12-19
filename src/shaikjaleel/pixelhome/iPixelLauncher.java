/*
 * Copyright (C) 2018 CypherOS
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
package com.shaikjaleel.pixelhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;

import com.shaikjaleel.pixelhome.quickspace.QuickSpaceView;

import com.android.launcher3.AppInfo;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherCallbacks;
import com.android.launcher3.Utilities;
import com.android.launcher3.R;
import com.google.android.libraries.gsa.launcherclient.ClientOptions;
import com.google.android.libraries.gsa.launcherclient.ClientService;
import com.google.android.libraries.gsa.launcherclient.LauncherClient;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class iPixelLauncher extends Launcher {

    public iPixelLauncher() {
        setLauncherCallbacks(new iPixelLauncherCallbacks(this));
    }

    public class iPixelLauncherCallbacks implements LauncherCallbacks, OnSharedPreferenceChangeListener {

        public static final String SEARCH_PACKAGE = "com.google.android.googlequicksearchbox";

        private final iPixelLauncher mLauncher;
        private QuickSpaceView mQuickSpace;

        private OverlayCallbackImpl mOverlayCallbacks;
        private LauncherClient mLauncherClient;
        private boolean mStarted;
        private boolean mResumed;
        private boolean mAlreadyOnHome;

        public iPixelLauncherCallbacks(iPixelLauncher launcher) {
            mLauncher = launcher;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            mQuickSpace = mLauncher.findViewById(R.id.reserved_container_workspace);

            SharedPreferences prefs = Utilities.getPrefs(mLauncher);
            mOverlayCallbacks = new OverlayCallbackImpl(mLauncher);
            mLauncherClient = new LauncherClient(mLauncher, mOverlayCallbacks, new ClientOptions(((prefs.getBoolean(SettingsFragment.KEY_MINUS_ONE, true) ? 1 : 0) | 2 | 4 | 8)));
            mOverlayCallbacks.setClient(mLauncherClient);
            prefs.registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onResume() {
            mResumed = true;
            if (mStarted) {
                mAlreadyOnHome = true;
            }
            mLauncherClient.onResume();
        }

        @Override
        public void onStart() {
            mStarted = true;
            mLauncherClient.onStart();
        }

        @Override
        public void onStop() {
            mStarted = false;
            if (!mResumed) {
                mAlreadyOnHome = false;
            }
            mLauncherClient.onStop();
        }

        @Override
        public void onPause() {
            if (mQuickSpace != null) {
                mQuickSpace.onPause();
            }
            mResumed = false;
            mLauncherClient.onPause();
        }

        @Override
        public void onDestroy() {
            if (!mLauncherClient.isDestroyed()) {
                mLauncherClient.getActivity().unregisterReceiver(mLauncherClient.mInstallListener);
            }
            mLauncherClient.setDestroyed(true);
            mLauncherClient.getBaseService().disconnect();
            if (mLauncherClient.getOverlayCallback() != null) {
                mLauncherClient.getOverlayCallback().client = null;
                mLauncherClient.getOverlayCallback().windowManager = null;
                mLauncherClient.getOverlayCallback().window = null;
                mLauncherClient.setOverlayCallback(null);
            }
            ClientService service = mLauncherClient.getClientService();
            LauncherClient client = service.getClient();
            if (client != null && client.equals(mLauncherClient)) {
                service.mWeakReference = null;
                if (!mLauncherClient.getActivity().isChangingConfigurations()) {
                    service.disconnect();
                    if (ClientService.sInstance == service) {
                        ClientService.sInstance = null;
                    }
                }
            }
            Utilities.getPrefs(mLauncher).unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSaveInstanceState(Bundle outState) { }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) { }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) { }

        @Override
        public void onAttachedToWindow() {
            mLauncherClient.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow() {
            if (!mLauncherClient.isDestroyed()) {
                mLauncherClient.getEventInfo().parse(0, "detachedFromWindow", 0.0f);
                mLauncherClient.setParams(null);
            }
        }

        @Override
        public void dump(String prefix, FileDescriptor fd, PrintWriter w, String[] args) { }

        @Override
        public void onHomeIntent(boolean internalStateHandled) {
            mLauncherClient.hideOverlay(mAlreadyOnHome);
        }

        @Override
        public boolean handleBackPressed() {
            return false;
        }

        @Override
        public void onTrimMemory(int level) { }

        @Override
        public void onLauncherProviderChange() { }

        @Override
        public void bindAllApplications(ArrayList<AppInfo> apps) { }

        @Override
        public boolean startSearch(String initialQuery, boolean selectInitialQuery, Bundle appSearchData) {
            return false;
        }

        @Override
        public boolean hasSettings() {
            return true;
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (SettingsFragment.KEY_MINUS_ONE.equals(key)) {
                ClientOptions clientOptions = new ClientOptions((prefs.getBoolean(SettingsFragment.KEY_MINUS_ONE, true) ? 1 : 0) | 2 | 4 | 8);
                if (clientOptions.options != mLauncherClient.mFlags) {
                    mLauncherClient.mFlags = clientOptions.options;
                    if (mLauncherClient.getParams() != null) {
                        mLauncherClient.updateConfiguration();
                    }
                    mLauncherClient.getEventInfo().parse("setClientOptions ", mLauncherClient.mFlags);
                }
            }
        }

        private LauncherClient getClient() {
            return mLauncherClient;
        }
    }
}
