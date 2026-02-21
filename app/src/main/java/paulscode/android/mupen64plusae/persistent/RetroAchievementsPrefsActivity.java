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
    private Preference mEnabledPreference;
    private Preference mHardcorePreference;
    private boolean mIsLoginValidationInProgress = false;

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

        // Create layout with username and credential fields
        final EditText usernameInput = new EditText(this);
        usernameInput.setHint(R.string.retroachievements_username_hint);
        usernameInput.setInputType(InputType.TYPE_CLASS_TEXT);

        final EditText credentialInput = new EditText(this);
        credentialInput.setHint(R.string.retroachievements_password_hint);
        credentialInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        final android.widget.RadioGroup loginModeGroup = new android.widget.RadioGroup(this);
        loginModeGroup.setOrientation(android.widget.LinearLayout.HORIZONTAL);

        final android.widget.RadioButton passwordModeButton = new android.widget.RadioButton(this);
        passwordModeButton.setId(android.view.View.generateViewId());
        passwordModeButton.setText(R.string.retroachievements_login_mode_password);
        passwordModeButton.setChecked(true);

        final android.widget.RadioButton tokenModeButton = new android.widget.RadioButton(this);
        tokenModeButton.setId(android.view.View.generateViewId());
        tokenModeButton.setText(R.string.retroachievements_login_mode_token);

        loginModeGroup.addView(passwordModeButton);
        loginModeGroup.addView(tokenModeButton);
        loginModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == tokenModeButton.getId()) {
                credentialInput.setHint(R.string.retroachievements_token_hint);
            } else {
                credentialInput.setHint(R.string.retroachievements_password_hint);
            }
        });

        // Simple linear layout for inputs
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        
        // Convert dp to pixels for proper density-independent spacing
        int paddingDp = 16; // Standard material design padding
        float density = getResources().getDisplayMetrics().density;
        int paddingPx = (int) (paddingDp * density);
        layout.setPadding(paddingPx, paddingPx, paddingPx, paddingPx / 2);
        
        layout.addView(usernameInput);
        layout.addView(loginModeGroup);
        layout.addView(credentialInput);

        builder.setView(layout);

        builder.setPositiveButton(R.string.retroachievements_login, (dialog, which) -> {
            String username = usernameInput.getText().toString().trim();
            String credential = credentialInput.getText().toString().trim();
            boolean useToken = loginModeGroup.getCheckedRadioButtonId() == tokenModeButton.getId();

            if (username.isEmpty() || credential.isEmpty()) {
                Toast.makeText(this, R.string.retroachievements_login_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            performLogin(username, credential, useToken);
        });

        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void performLogin(String username, String credential, boolean useToken) {
        RetroAchievementsManager manager = RetroAchievementsManager.getInstance(this);
        if (!manager.initialize()) {
            Toast.makeText(this, getString(R.string.ra_login_failed,
                            getString(R.string.retroachievements_login_validation_failed_reason)),
                    Toast.LENGTH_LONG).show();
            return;
        }

        mIsLoginValidationInProgress = true;
        updateLoginState();

        Toast.makeText(this, R.string.retroachievements_login_validating, Toast.LENGTH_SHORT).show();

        manager.validateCredentials(username, credential, useToken, (success, errorMessage, resolvedToken) -> {
            mIsLoginValidationInProgress = false;

            if (success) {
                mAppData.setRetroAchievementsUsername(username);

                if (!TextUtils.isEmpty(resolvedToken)) {
                    mAppData.setRetroAchievementsToken(resolvedToken);
                    manager.setTokenCredentials(username, resolvedToken);
                } else if (useToken) {
                    mAppData.setRetroAchievementsToken(credential);
                    manager.setTokenCredentials(username, credential);
                } else {
                    mAppData.setRetroAchievementsPassword(credential);
                    manager.setCredentials(username, credential);
                }

                mAppData.setRetroAchievementsEnabled(true);
                mAppData.setRetroAchievementsCredentialsVerified(true);

                Toast.makeText(this, getString(R.string.retroachievements_login_success, username),
                        Toast.LENGTH_LONG).show();
            } else {
                String message = !TextUtils.isEmpty(errorMessage)
                        ? errorMessage
                        : getString(R.string.retroachievements_login_validation_failed_reason);
                Toast.makeText(this, getString(R.string.ra_login_failed, message), Toast.LENGTH_LONG).show();
            }

            updateLoginState();
        });

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
        boolean hasCredentials = mAppData.getRetroAchievementsPassword() != null
                || mAppData.getRetroAchievementsToken() != null;
        String username = mAppData.getRetroAchievementsUsername();
        boolean isLoggedIn = !TextUtils.isEmpty(username)
                && hasCredentials
                && mAppData.isRetroAchievementsCredentialsVerified();
        boolean isEnabled = mAppData.isRetroAchievementsEnabled();

        if (mLoginPreference != null) {
            mLoginPreference.setEnabled(!mIsLoginValidationInProgress && !isLoggedIn && isEnabled);
            if (mIsLoginValidationInProgress) {
                mLoginPreference.setSummary(R.string.retroachievements_login_validating);
            } else if (isLoggedIn) {
                mLoginPreference.setSummary(getString(R.string.retroachievements_logged_in_as,
                        username));
            } else if (!TextUtils.isEmpty(username) && hasCredentials) {
                mLoginPreference.setSummary(getString(R.string.retroachievements_login_needs_validation,
                        username));
            } else {
                mLoginPreference.setSummary(R.string.retroachievements_login_summary);
            }
        }

        if (mLogoutPreference != null) {
            mLogoutPreference.setEnabled(!mIsLoginValidationInProgress && hasCredentials);
        }

        if (mHardcorePreference != null) {
            mHardcorePreference.setEnabled(!mIsLoginValidationInProgress && isEnabled);
        }
    }
}
