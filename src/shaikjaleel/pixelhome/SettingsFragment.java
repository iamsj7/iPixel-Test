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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.FragmentManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;

import com.shaikjaleel.pixelhome.iPixelLauncher.iPixelLauncherCallbacks;

import com.android.launcher3.SettingsActivity;
import com.android.launcher3.SettingsActivity.LauncherSettingsFragment;

import java.util.Objects;

public class SettingsFragment extends SettingsActivity {

    public static final String KEY_MINUS_ONE = "pref_enable_minus_one";

    @Override
    protected PreferenceFragment getNewFragment() {
        return new SettingsPreferenceFragment();
    }

    /**
     * This fragment shows the PixelHome launcher preferences.
     */
    public static class SettingsPreferenceFragment extends LauncherSettingsFragment {

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            SwitchPreference minusOne = (SwitchPreference) findPreference(KEY_MINUS_ONE);
            if (!Bits.hasPackageInstalled(getActivity(),
                        iPixelLauncherCallbacks.SEARCH_PACKAGE)) {
                getPreferenceScreen().removePreference(minusOne);
            }
        }
    }
}
