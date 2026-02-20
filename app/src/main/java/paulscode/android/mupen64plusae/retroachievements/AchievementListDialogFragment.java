package paulscode.android.mupen64plusae.retroachievements;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
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

    public static AchievementListDialogFragment newInstance() {
        return new AchievementListDialogFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
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

        ImageView closeButton = view.findViewById(R.id.ra_list_close);
        TextView summaryView = view.findViewById(R.id.ra_list_summary);
        RecyclerView recyclerView = view.findViewById(R.id.ra_list_recycler);

        closeButton.setOnClickListener(v -> dismiss());

        RetroAchievementsManager manager = RetroAchievementsManager.getInstance(requireContext());

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
        List<AchievementItem> items = AchievementItem.parseJson(json);

        if (items.isEmpty()) {
            summaryView.setText(R.string.ra_list_no_achievements);
            summaryView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            return;
        }

        AchievementBadgeCache badgeCache = new AchievementBadgeCache(requireContext());
        AchievementListAdapter adapter = new AchievementListAdapter(items, badgeCache);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
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
}
