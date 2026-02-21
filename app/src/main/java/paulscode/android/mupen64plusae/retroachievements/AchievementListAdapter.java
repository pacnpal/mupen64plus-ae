package paulscode.android.mupen64plusae.retroachievements;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import paulscode.android.mupen64plusae.R;

import java.util.List;

public class AchievementListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;

    private final List<AchievementItem> mItems;
    private final AchievementBadgeCache mBadgeCache;
    private final boolean mHardcoreSessionActive;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public AchievementListAdapter(List<AchievementItem> items, AchievementBadgeCache badgeCache,
                                  boolean hardcoreSessionActive) {
        mItems = items;
        mBadgeCache = badgeCache;
        mHardcoreSessionActive = hardcoreSessionActive;
    }

    @Override
    public int getItemViewType(int position) {
        return mItems.get(position).isHeader ? VIEW_TYPE_HEADER : VIEW_TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_HEADER) {
            View view = inflater.inflate(R.layout.ra_list_section_header, parent, false);
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
            return new HeaderViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.ra_list_item_achievement, parent, false);
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            return new AchievementViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        AchievementItem item = mItems.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(item);
        } else if (holder instanceof AchievementViewHolder) {
            ((AchievementViewHolder) holder).bind(item, position, mHardcoreSessionActive);
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView mText;

        HeaderViewHolder(View itemView) {
            super(itemView);
            mText = itemView.findViewById(R.id.ra_section_header_text);
        }

        void bind(AchievementItem item) {
            mText.setText(item.headerLabel);
        }
    }

    class AchievementViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mBadge;
        private final TextView mTitle;
        private final TextView mUnlockMode;
        private final TextView mType;
        private final TextView mDescription;
        private final TextView mRarity;
        private final View mProgressContainer;
        private final ProgressBar mProgressBar;
        private final TextView mProgressText;
        private final TextView mPoints;
        private int mBoundPosition = -1;

        AchievementViewHolder(View itemView) {
            super(itemView);
            mBadge = itemView.findViewById(R.id.ra_item_badge);
            mTitle = itemView.findViewById(R.id.ra_item_title);
            mUnlockMode = itemView.findViewById(R.id.ra_item_unlock_mode);
            mType = itemView.findViewById(R.id.ra_item_type);
            mDescription = itemView.findViewById(R.id.ra_item_description);
            mRarity = itemView.findViewById(R.id.ra_item_rarity);
            mProgressContainer = itemView.findViewById(R.id.ra_item_progress_container);
            mProgressBar = itemView.findViewById(R.id.ra_item_progress_bar);
            mProgressText = itemView.findViewById(R.id.ra_item_progress_text);
            mPoints = itemView.findViewById(R.id.ra_item_points);

            itemView.setOnFocusChangeListener((view, hasFocus) -> view.setActivated(hasFocus));
        }

        void bind(AchievementItem item, int position, boolean hardcoreSessionActive) {
            mBoundPosition = position;

            mTitle.setText(item.title);
            mDescription.setText(item.description);
            mPoints.setText(String.valueOf(item.points));

            // Alpha for locked achievements
            float alpha = item.isUnlocked() ? 1.0f : 0.6f;
            itemView.setAlpha(alpha);

            if (item.isUnlockedInHardcore() && item.isUnlockedInSoftcore()) {
                mUnlockMode.setText(R.string.ra_list_unlock_mode_both);
                mUnlockMode.setVisibility(View.VISIBLE);
            } else if (item.isUnlockedInHardcore()) {
                mUnlockMode.setText(R.string.ra_list_unlock_mode_hardcore);
                mUnlockMode.setVisibility(View.VISIBLE);
            } else if (item.isUnlockedInSoftcore()) {
                mUnlockMode.setText(R.string.ra_list_unlock_mode_softcore);
                mUnlockMode.setVisibility(View.VISIBLE);
            } else {
                mUnlockMode.setVisibility(View.GONE);
            }

            // Type indicator
            if (item.type == AchievementItem.TYPE_MISSABLE) {
                mType.setText(R.string.ra_list_type_missable);
                mType.setVisibility(View.VISIBLE);
            } else if (item.type == AchievementItem.TYPE_PROGRESSION) {
                mType.setText(R.string.ra_list_type_progression);
                mType.setVisibility(View.VISIBLE);
            } else if (item.type == AchievementItem.TYPE_WIN) {
                mType.setText(R.string.ra_list_type_win);
                mType.setVisibility(View.VISIBLE);
            } else {
                mType.setVisibility(View.GONE);
            }

            // Rarity
            float rarityValue = item.getDisplayRarity(hardcoreSessionActive);
            if (rarityValue > 0) {
                mRarity.setText(String.format(itemView.getContext().getString(R.string.ra_list_rarity), rarityValue));
                mRarity.setVisibility(View.VISIBLE);
            } else {
                mRarity.setVisibility(View.GONE);
            }

            // Progress
            if (item.measuredProgress != null && !item.measuredProgress.isEmpty()) {
                mProgressContainer.setVisibility(View.VISIBLE);
                mProgressBar.setProgress(Math.round(item.measuredPercent));
                mProgressText.setText(item.measuredProgress);
            } else {
                mProgressContainer.setVisibility(View.GONE);
            }

            // Badge
            mBadge.setImageResource(android.R.drawable.ic_menu_gallery);
            String badgeUrl = item.getEffectiveBadgeUrl();
            if (badgeUrl != null && !badgeUrl.isEmpty()) {
                mBadgeCache.getBadge(badgeUrl, bitmap -> {
                    mMainHandler.post(() -> {
                        if (mBoundPosition == position && bitmap != null) {
                            mBadge.setImageBitmap(bitmap);
                        }
                    });
                });
            }
        }
    }
}
