/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 *
 * RetroAchievements In-Game Achievement Overlay
 *
 * Custom overlay view that displays achievement notification popups,
 * leaderboard trackers, and challenge indicators over the game.
 */
package paulscode.android.mupen64plusae.retroachievements;

import paulscode.android.mupen64plusae.R;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.LinkedList;
import java.util.Queue;

public class AchievementOverlayView extends FrameLayout {
    private static final long DISPLAY_DURATION_MS = 4000;
    private static final long ANIM_DURATION_MS = 300;
    private static final int BG_COLOR = 0xDD1A1A2E;
    private static final int ACCENT_COLOR = 0xFFFFD700;
    private static final int TEXT_COLOR = Color.WHITE;
    private static final int SUBTEXT_COLOR = 0xFFCCCCCC;
    private static final int TRACKER_BG_COLOR = 0xCC1A1A2E;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Queue<Runnable> mNotificationQueue = new LinkedList<>();
    private boolean mIsShowing = false;

    // Main popup (top-center) for achievements, leaderboard events
    private LinearLayout mPopupContainer;
    private ImageView mBadgeImage;
    private TextView mTitleText;
    private TextView mDescText;
    private TextView mPointsText;
    private ProgressBar mProgressBar;
    private TextView mProgressText;

    // Leaderboard tracker container (bottom-right), persistent
    private LinearLayout mTrackerContainer;
    private final SparseArray<TextView> mTrackerViews = new SparseArray<>();

    // Challenge indicator container (bottom-left), persistent
    private LinearLayout mChallengeContainer;
    private final SparseArray<ImageView> mChallengeViews = new SparseArray<>();

    private AchievementBadgeCache mBadgeCache;
    private int mPopupBadgeRequestToken = 0;

    public AchievementOverlayView(Context context) {
        super(context);
        init(context);
    }

    public AchievementOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AchievementOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private GradientDrawable makeRoundedBg(int color, int cornerDp, int strokeDp, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(cornerDp));
        if (strokeDp > 0) bg.setStroke(dp(strokeDp), strokeColor);
        return bg;
    }

    private void init(Context context) {
        mBadgeCache = new AchievementBadgeCache(context);

        initPopup(context);
        initTrackerContainer(context);
        initChallengeContainer(context);
    }

    private void initPopup(Context context) {
        mPopupContainer = new LinearLayout(context);
        mPopupContainer.setOrientation(LinearLayout.HORIZONTAL);
        mPopupContainer.setGravity(Gravity.CENTER_VERTICAL);
        mPopupContainer.setPadding(dp(12), dp(10), dp(16), dp(10));
        mPopupContainer.setVisibility(View.GONE);
        mPopupContainer.setBackground(makeRoundedBg(BG_COLOR, 12, 1, 0x44FFFFFF));

        // Badge image
        mBadgeImage = new ImageView(context);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        badgeParams.setMarginEnd(dp(12));
        mBadgeImage.setLayoutParams(badgeParams);
        mBadgeImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mPopupContainer.addView(mBadgeImage);

        // Text container
        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mTitleText = new TextView(context);
        mTitleText.setTextColor(TEXT_COLOR);
        mTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        mTitleText.setMaxLines(1);
        mTitleText.setSingleLine(true);
        textContainer.addView(mTitleText);

        mDescText = new TextView(context);
        mDescText.setTextColor(SUBTEXT_COLOR);
        mDescText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mDescText.setMaxLines(2);
        textContainer.addView(mDescText);

        mProgressText = new TextView(context);
        mProgressText.setTextColor(SUBTEXT_COLOR);
        mProgressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mProgressText.setVisibility(View.GONE);
        textContainer.addView(mProgressText);

        mProgressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        progressParams.topMargin = dp(4);
        mProgressBar.setLayoutParams(progressParams);
        mProgressBar.setVisibility(View.GONE);
        textContainer.addView(mProgressBar);

        mPopupContainer.addView(textContainer);

        // Points text
        mPointsText = new TextView(context);
        mPointsText.setTextColor(ACCENT_COLOR);
        mPointsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mPointsText.setTypeface(Typeface.DEFAULT_BOLD);
        mPointsText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pointsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pointsParams.setMarginStart(dp(8));
        mPointsText.setLayoutParams(pointsParams);
        mPopupContainer.addView(mPointsText);

        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        containerParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        containerParams.topMargin = dp(16);
        containerParams.leftMargin = dp(24);
        containerParams.rightMargin = dp(24);
        addView(mPopupContainer, containerParams);
    }

    private void initTrackerContainer(Context context) {
        mTrackerContainer = new LinearLayout(context);
        mTrackerContainer.setOrientation(LinearLayout.VERTICAL);
        mTrackerContainer.setPadding(dp(8), dp(4), dp(8), dp(4));
        mTrackerContainer.setVisibility(View.GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.bottomMargin = dp(16);
        params.rightMargin = dp(16);
        addView(mTrackerContainer, params);
    }

    private void initChallengeContainer(Context context) {
        mChallengeContainer = new LinearLayout(context);
        mChallengeContainer.setOrientation(LinearLayout.HORIZONTAL);
        mChallengeContainer.setPadding(dp(4), dp(4), dp(4), dp(4));
        mChallengeContainer.setVisibility(View.GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.START;
        params.bottomMargin = dp(16);
        params.leftMargin = dp(16);
        addView(mChallengeContainer, params);
    }

    private void resetPopupBackground() {
        mPopupContainer.setBackground(makeRoundedBg(BG_COLOR, 12, 1, 0x44FFFFFF));
    }

    // ========== Achievement Popup Methods ==========

    public void showAchievementTriggered(int id, String title, String description, String badgeUrl, int points) {
        enqueueNotification(() -> {
            resetPopupBackground();
            mTitleText.setText(title);
            mDescText.setText(description);
            mDescText.setVisibility(View.VISIBLE);
            mPointsText.setText("+" + points);
            mPointsText.setVisibility(View.VISIBLE);
            mProgressBar.setVisibility(View.GONE);
            mProgressText.setVisibility(View.GONE);
            loadBadge(badgeUrl);
            showPopup();
        });
    }

    public void showAchievementProgress(int id, String title, String measuredProgress, float measuredPercent) {
        enqueueNotification(() -> {
            resetPopupBackground();
            mTitleText.setText(title);
            mDescText.setVisibility(View.GONE);
            mPointsText.setVisibility(View.GONE);
            mProgressText.setText(measuredProgress);
            mProgressText.setVisibility(View.VISIBLE);
            mProgressBar.setProgress(Math.round(measuredPercent));
            mProgressBar.setVisibility(View.VISIBLE);
            clearPopupBadge();
            showPopup();
        });
    }

    public void hideAchievementProgress() {
        dismissPopup();
    }

    public void showGameCompleted(String gameTitle) {
        enqueueNotification(() -> {
            mPopupContainer.setBackground(makeRoundedBg(0xDD2E1A00, 12, 2, ACCENT_COLOR));
            mTitleText.setText(getContext().getString(R.string.ra_overlay_game_mastered));
            mDescText.setText(gameTitle);
            mDescText.setVisibility(View.VISIBLE);
            mPointsText.setVisibility(View.GONE);
            mProgressBar.setVisibility(View.GONE);
            mProgressText.setVisibility(View.GONE);
            clearPopupBadge();
            showPopup();
        });
    }

    public void showGameSessionStarted(String gameTitle, String badgeUrl, int numAchievements, int numUnlocked) {
        enqueueNotification(() -> {
            resetPopupBackground();
            mTitleText.setText(getContext().getString(R.string.ra_overlay_now_playing));
            String desc = gameTitle;
            if (numAchievements > 0) {
                desc += "\n" + getContext().getString(R.string.ra_overlay_achievement_count, numUnlocked, numAchievements);
            }
            mDescText.setText(desc);
            mDescText.setVisibility(View.VISIBLE);
            mPointsText.setVisibility(View.GONE);
            mProgressBar.setVisibility(View.GONE);
            mProgressText.setVisibility(View.GONE);
            loadBadge(badgeUrl);
            showPopup();
        });
    }

    public void showSubsetCompleted(String subsetTitle) {
        enqueueNotification(() -> {
            mPopupContainer.setBackground(makeRoundedBg(0xDD2E1A00, 12, 2, ACCENT_COLOR));
            mTitleText.setText(getContext().getString(R.string.ra_overlay_subset_mastered));
            mDescText.setText(subsetTitle);
            mDescText.setVisibility(View.VISIBLE);
            mPointsText.setVisibility(View.GONE);
            mProgressBar.setVisibility(View.GONE);
            mProgressText.setVisibility(View.GONE);
            clearPopupBadge();
            showPopup();
        });
    }

    // ========== Leaderboard Popup Methods ==========

    public void showLeaderboardStarted(String title, String description) {
        enqueueNotification(() -> {
            resetPopupBackground();
            mTitleText.setText(getContext().getString(R.string.ra_overlay_leaderboard_prefix, title));
            mDescText.setText(description);
            mDescText.setVisibility(View.VISIBLE);
            mPointsText.setVisibility(View.GONE);
            mProgressBar.setVisibility(View.GONE);
            mProgressText.setVisibility(View.GONE);
            clearPopupBadge();
            showPopup();
        });
    }

    public void showLeaderboardFailed(String title) {
        enqueueNotification(() -> {
            resetPopupBackground();
            mTitleText.setText(getContext().getString(R.string.ra_overlay_leaderboard_failed));
            mDescText.setText(title);
            mDescText.setVisibility(View.VISIBLE);
            mPointsText.setVisibility(View.GONE);
            mProgressBar.setVisibility(View.GONE);
            mProgressText.setVisibility(View.GONE);
            clearPopupBadge();
            showPopup();
        });
    }

    public void showLeaderboardSubmitted(String title, String score, String bestScore, int newRank, int numEntries) {
        enqueueNotification(() -> {
            resetPopupBackground();
            mTitleText.setText(getContext().getString(R.string.ra_overlay_leaderboard_prefix, title));
            String detail;
            if (newRank > 0) {
                detail = getContext().getString(R.string.ra_overlay_leaderboard_rank, score, newRank, numEntries);
            } else {
                detail = score;
            }
            mDescText.setText(detail);
            mDescText.setVisibility(View.VISIBLE);
            mPointsText.setVisibility(View.GONE);
            mProgressBar.setVisibility(View.GONE);
            mProgressText.setVisibility(View.GONE);
            clearPopupBadge();
            showPopup();
        });
    }

    // ========== Leaderboard Tracker Methods (persistent, bottom-right) ==========

    public void showLeaderboardTracker(int trackerId, String display) {
        TextView tv = mTrackerViews.get(trackerId);
        if (tv == null) {
            tv = new TextView(getContext());
            tv.setTextColor(TEXT_COLOR);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tv.setPadding(dp(8), dp(4), dp(8), dp(4));
            tv.setBackground(makeRoundedBg(TRACKER_BG_COLOR, 6, 0, 0));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(4);
            tv.setLayoutParams(params);
            mTrackerViews.put(trackerId, tv);
            mTrackerContainer.addView(tv);
        }
        tv.setText(display);
        mTrackerContainer.setVisibility(View.VISIBLE);
    }

    public void updateLeaderboardTracker(int trackerId, String display) {
        TextView tv = mTrackerViews.get(trackerId);
        if (tv != null) {
            tv.setText(display);
        }
    }

    public void hideLeaderboardTracker(int trackerId) {
        TextView tv = mTrackerViews.get(trackerId);
        if (tv != null) {
            mTrackerContainer.removeView(tv);
            mTrackerViews.remove(trackerId);
        }
        if (mTrackerViews.size() == 0) {
            mTrackerContainer.setVisibility(View.GONE);
        }
    }

    // ========== Challenge Indicator Methods (persistent, bottom-left) ==========

    public void showChallengeIndicator(int id, String title, String badgeUrl) {
        ImageView iv = mChallengeViews.get(id);
        if (iv == null) {
            iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
            params.setMarginEnd(dp(4));
            iv.setLayoutParams(params);
            iv.setBackground(makeRoundedBg(TRACKER_BG_COLOR, 4, 1, ACCENT_COLOR));
            iv.setPadding(dp(2), dp(2), dp(2), dp(2));
            mChallengeViews.put(id, iv);
            mChallengeContainer.addView(iv);
        }
        mChallengeContainer.setVisibility(View.VISIBLE);

        // Load badge
        final ImageView targetIv = iv;
        if (badgeUrl != null && !badgeUrl.isEmpty()) {
            mBadgeCache.getBadge(badgeUrl, bitmap -> {
                mHandler.post(() -> {
                    if (bitmap != null) {
                        targetIv.setImageBitmap(bitmap);
                    }
                });
            });
        }
    }

    public void hideChallengeIndicator(int id) {
        ImageView iv = mChallengeViews.get(id);
        if (iv != null) {
            mChallengeContainer.removeView(iv);
            mChallengeViews.remove(id);
        }
        if (mChallengeViews.size() == 0) {
            mChallengeContainer.setVisibility(View.GONE);
        }
    }

    // ========== Internal helpers ==========

    private void clearPopupBadge() {
        mPopupBadgeRequestToken++;
        mBadgeImage.setImageBitmap(null);
    }

    private void loadBadge(String badgeUrl) {
        final int requestToken = ++mPopupBadgeRequestToken;
        mBadgeImage.setImageBitmap(null);
        if (badgeUrl == null || badgeUrl.isEmpty()) {
            return;
        }
        mBadgeCache.getBadge(badgeUrl, bitmap -> {
            mHandler.post(() -> {
                if (requestToken != mPopupBadgeRequestToken) {
                    return;
                }
                if (bitmap != null && mPopupContainer.getVisibility() == View.VISIBLE) {
                    mBadgeImage.setImageBitmap(bitmap);
                }
            });
        });
    }

    private void enqueueNotification(Runnable showAction) {
        if (mIsShowing) {
            mNotificationQueue.offer(showAction);
        } else {
            showAction.run();
        }
    }

    private void showPopup() {
        mIsShowing = true;
        mPopupContainer.setVisibility(View.VISIBLE);
        mPopupContainer.setTranslationY(-dp(100));
        mPopupContainer.setAlpha(0f);

        mPopupContainer.animate()
                .translationY(0)
                .alpha(1f)
                .setDuration(ANIM_DURATION_MS)
                .setListener(null)
                .start();

        mHandler.postDelayed(this::dismissPopup, DISPLAY_DURATION_MS);
    }

    private void dismissPopup() {
        if (!mIsShowing) return;
        mHandler.removeCallbacksAndMessages(null);

        mPopupContainer.animate()
                .translationY(-dp(100))
                .alpha(0f)
                .setDuration(ANIM_DURATION_MS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPopupContainer.setVisibility(View.GONE);
                        clearPopupBadge();
                        resetPopupBackground();
                        mIsShowing = false;
                        processQueue();
                    }
                })
                .start();
    }

    private void processQueue() {
        Runnable next = mNotificationQueue.poll();
        if (next != null) {
            mHandler.postDelayed(next, 200);
        }
    }

    public void destroy() {
        mHandler.removeCallbacksAndMessages(null);
        mNotificationQueue.clear();
        mTrackerViews.clear();
        mChallengeViews.clear();
        if (mBadgeCache != null) {
            mBadgeCache.shutdown();
        }
    }
}
