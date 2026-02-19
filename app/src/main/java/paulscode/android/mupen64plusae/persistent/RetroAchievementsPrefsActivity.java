/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 * 
 * RetroAchievements Preferences Activity
 * 
 * Copyright (C) 2024
 * 
 * This file is part of Mupen64PlusAE.
 * 
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package paulscode.android.mupen64plusae.persistent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import paulscode.android.mupen64plusae.R;
import paulscode.android.mupen64plusae.compat.AppCompatPreferenceActivity;
import paulscode.android.mupen64plusae.retroachievements.RetroAchievementsManager;
import paulscode.android.mupen64plusae.util.LocaleContextWrapper;

public class RetroAchievementsPrefsActivity extends AppCompatPreferenceActivity
        implements Preference.OnPreferenceClickListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String PREF_RA_LOGIN = "retroachievementsLogin";
    private static final String PREF_RA_LOGOUT = "retroachievementsLogout";
    private static final String PREF_RA_ENABLED = "retroachievementsEnabled";
    private static final String PREF_RA_HARDCORE = "retroachievementsHardcore";

    private AppData mAppData;
    private Preference mLoginPreference;
    private Preference mLogoutPreference;
    private SwitchPreferenceCompat mEnabledPreference;
    private SwitchPreferenceCompat mHardcorePreference;

    @Override
    protected void attachBaseContext(Context newBase) {
        if (TextUtils.isEmpty(LocaleContextWrapper.getLocalCode())) {
            super.attachBaseContext(newBase);
        } else {
            super.attachBaseContext(LocaleContextWrapper.wrap(newBase, LocaleContextWrapper.getLocalCode()));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAppData = new AppData(this);
    }

    @Override
    protected String getSharedPrefsName() {
        return null;
    }

    @Override
    protected int getSharedPrefsId() {
        return R.xml.preferences_retroachievements;
    }

    @Override
    protected void OnPreferenceScreenChange(String key) {
        mLoginPreference = findPreference(PREF_RA_LOGIN);
        mLogoutPreference = findPreference(PREF_RA_LOGOUT);
        mEnabledPreference = findPreference(PREF_RA_ENABLED);
        mHardcorePreference = findPreference(PREF_RA_HARDCORE);

        if (mLoginPreference != null) {
            mLoginPreference.setOnPreferenceClickListener(this);
        }
        if (mLogoutPreference != null) {
            mLogoutPreference.setOnPreferenceClickListener(this);
        }

        updateLoginState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);
        updateLoginState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();

        if (PREF_RA_LOGIN.equals(key)) {
            showLoginDialog();
            return true;
        } else if (PREF_RA_LOGOUT.equals(key)) {
            performLogout();
            return true;
        }

        return false;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (PREF_RA_ENABLED.equals(key)) {
            updateLoginState();
        }
    }

    private void showLoginDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.retroachievements_login_title);

        // Create layout with username and token fields
        final EditText usernameInput = new EditText(this);
        usernameInput.setHint(R.string.retroachievements_username_hint);
        usernameInput.setInputType(InputType.TYPE_CLASS_TEXT);

        final EditText tokenInput = new EditText(this);
        tokenInput.setHint(R.string.retroachievements_token_hint);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // Simple linear layout for inputs
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        
        // Convert dp to pixels for proper density-independent spacing
        int paddingDp = 16; // Standard material design padding
        float density = getResources().getDisplayMetrics().density;
        int paddingPx = (int) (paddingDp * density);
        layout.setPadding(paddingPx, paddingPx, paddingPx, paddingPx / 2);
        
        layout.addView(usernameInput);
        layout.addView(tokenInput);

        builder.setView(layout);

        builder.setPositiveButton(R.string.retroachievements_login, (dialog, which) -> {
            String username = usernameInput.getText().toString().trim();
            String token = tokenInput.getText().toString().trim();

            if (username.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, R.string.retroachievements_login_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            performLogin(username, token);
        });

        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void performLogin(String username, String token) {
        // Save credentials
        mAppData.setRetroAchievementsUsername(username);
        mAppData.setRetroAchievementsToken(token);
        mAppData.setRetroAchievementsEnabled(true);

        // Update manager
        RetroAchievementsManager manager = RetroAchievementsManager.getInstance(this);
        manager.setCredentials(username, token);

        Toast.makeText(this, getString(R.string.retroachievements_login_success, username),
                Toast.LENGTH_LONG).show();

        updateLoginState();
    }

    private void performLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.retroachievements_logout_title)
                .setMessage(R.string.retroachievements_logout_confirm)
                .setPositiveButton(R.string.retroachievements_logout, (dialog, which) -> {
                    mAppData.clearRetroAchievementsCredentials();
                    RetroAchievementsManager.getInstance(this).setCredentials(null, null);

                    Toast.makeText(this, R.string.retroachievements_logout_success, Toast.LENGTH_SHORT).show();

                    updateLoginState();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateLoginState() {
        boolean isLoggedIn = mAppData.getRetroAchievementsUsername() != null &&
                mAppData.getRetroAchievementsToken() != null;
        boolean isEnabled = mAppData.isRetroAchievementsEnabled();

        if (mLoginPreference != null) {
            mLoginPreference.setEnabled(!isLoggedIn && isEnabled);
            if (isLoggedIn) {
                mLoginPreference.setSummary(getString(R.string.retroachievements_logged_in_as,
                        mAppData.getRetroAchievementsUsername()));
            } else {
                mLoginPreference.setSummary(R.string.retroachievements_login_summary);
            }
        }

        if (mLogoutPreference != null) {
            mLogoutPreference.setEnabled(isLoggedIn);
        }

        if (mHardcorePreference != null) {
            mHardcorePreference.setEnabled(isEnabled);
        }
    }
}
