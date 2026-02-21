package paulscode.android.mupen64plusae.retroachievements;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import paulscode.android.mupen64plusae.R;

import java.util.List;

public class AchievementListDialogFragment extends DialogFragment {
    public static final String FRAGMENT_TAG = "AchievementListDialogFragment";
    private AchievementBadgeCache mBadgeCache;
    private RecyclerView mRecyclerView;
    private ImageButton mCloseButton;
    private List<AchievementItem> mItems;

    public static AchievementListDialogFragment newInstance() {
        return new AchievementListDialogFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.Theme_Mupen64plusaeTheme_Game);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.ra_dialog_achievement_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mCloseButton = view.findViewById(R.id.ra_list_close);
        TextView summaryView = view.findViewById(R.id.ra_list_summary);
        mRecyclerView = view.findViewById(R.id.ra_list_recycler);

        mCloseButton.setOnClickListener(v -> dismiss());
        mCloseButton.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return focusFirstAchievement();
            }
            return false;
        });

        RetroAchievementsManager manager = RetroAchievementsManager.getInstance(requireContext());
        final boolean hardcoreSessionActive = manager.isHardcoreSessionActive();

        // Set summary
        int[] summary = manager.getUserGameSummary();
        if (summary != null) {
            summaryView.setText(getString(R.string.ra_list_summary,
                    summary[1], summary[0], summary[3], summary[2]));
        } else {
            summaryView.setVisibility(View.GONE);
        }

        // Get achievement list
        String json = manager.getAchievementListJson();
        mItems = AchievementItem.parseJson(json);

        if (mItems.isEmpty()) {
            summaryView.setText(R.string.ra_list_no_achievements);
            summaryView.setVisibility(View.VISIBLE);
            mRecyclerView.setVisibility(View.GONE);
            mCloseButton.requestFocus();
            return;
        }

        if (mBadgeCache != null) {
            mBadgeCache.shutdown();
        }
        mBadgeCache = new AchievementBadgeCache(requireContext());
        AchievementListAdapter adapter = new AchievementListAdapter(
                mItems,
                mBadgeCache,
                hardcoreSessionActive);

        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && isFirstAchievementFocused()) {
                mCloseButton.requestFocus();
                return true;
            }
            return false;
        });

        view.post(this::focusFirstAchievement);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                dismiss();
                return true;
            }
            return false;
        });
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    public void onDestroyView() {
        if (mBadgeCache != null) {
            mBadgeCache.shutdown();
            mBadgeCache = null;
        }
        mRecyclerView = null;
        mCloseButton = null;
        mItems = null;
        super.onDestroyView();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (getActivity() == null) {
            return;
        }
        View overlay = getActivity().findViewById(R.id.gameOverlay);
        if (overlay != null) {
            overlay.requestFocus();
        }
    }

    private boolean focusFirstAchievement() {
        if (mRecyclerView == null || mItems == null || mItems.isEmpty()) {
            return false;
        }

        int firstAchievementPosition = -1;
        for (int i = 0; i < mItems.size(); i++) {
            if (!mItems.get(i).isHeader) {
                firstAchievementPosition = i;
                break;
            }
        }

        if (firstAchievementPosition < 0) {
            return false;
        }

        mRecyclerView.scrollToPosition(firstAchievementPosition);
        final int position = firstAchievementPosition;
        mRecyclerView.post(() -> {
            if (mRecyclerView == null) {
                return;
            }
            RecyclerView.ViewHolder holder = mRecyclerView.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                holder.itemView.requestFocus();
            } else {
                mRecyclerView.requestFocus();
            }
        });
        return true;
    }

    private boolean isFirstAchievementFocused() {
        if (mRecyclerView == null || mItems == null || mItems.isEmpty()) {
            return false;
        }

        View focusedView = mRecyclerView.getFocusedChild();
        if (focusedView == null) {
            return false;
        }

        int focusedPosition = mRecyclerView.getChildAdapterPosition(focusedView);
        if (focusedPosition == RecyclerView.NO_POSITION) {
            return false;
        }

        for (int i = 0; i < focusedPosition; i++) {
            if (!mItems.get(i).isHeader) {
                return false;
            }
        }

        return true;
    }
}
