package com.winlator.cmod.games;

import android.content.Context;
import android.util.SparseBooleanArray;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.WineInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-select dialog that lets the user pick which containers should
 * receive the new shortcuts.
 *
 * <p>Built on top of {@link ContentDialog} - the same rounded, theme-aware
 * dialog chrome used everywhere else in the app - instead of a plain
 * {@code AlertDialog}, so it matches the rest of the UI.</p>
 */
public class ContainerMultiSelectDialog {
    private final Context context;
    private final List<Container> containers;
    private final Callback<List<Container>> onPicked;

    public ContainerMultiSelectDialog(@NonNull Context context,
                                      @NonNull List<Container> containers,
                                      Callback<List<Container>> onPicked) {
        this.context = context;
        this.containers = containers;
        this.onPicked = onPicked;
    }

    public void show() {
        if (containers.isEmpty()) {
            ContentDialog.alert(context, R.string.no_items_to_display, null);
            return;
        }

        // Containers are listed by their Wine/Proton runtime instead of their
        // (often generic) container name, since that's what actually matters
        // when picking where a scanned game should get a shortcut.
        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();

        String[] names = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, containers.get(i).getWineVersion());
            names[i] = fullWineLabel(wineInfo);
        }

        ContentDialog dialog = new ContentDialog(context);
        dialog.setTitle(R.string.scan_games_pick_containers);
        dialog.setMessage(R.string.scan_games_pick_containers_msg);

        ListView listView = dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_multiple_choice, names));
        listView.setVisibility(View.VISIBLE);
        for (int i = 0; i < names.length; i++) listView.setItemChecked(i, true);

        dialog.setOnConfirmCallback(() -> {
            List<Container> selected = new ArrayList<>();
            SparseBooleanArray checkedItemPositions = listView.getCheckedItemPositions();
            for (int i = 0; i < containers.size(); i++) {
                if (checkedItemPositions.get(i)) selected.add(containers.get(i));
            }
            if (onPicked != null) onPicked.call(selected);
        });

        dialog.show();
    }

    /**
     * Builds a full runtime label including the architecture (e.g. "Proton
     * 9 arm64ec"), since {@link WineInfo#toString()} omits it and several
     * containers can otherwise show up with the exact same short name
     * (e.g. "Proton 9") even though they run different architectures.
     * Mirrors the formatting used by the Library's environment label.
     */
    private static String fullWineLabel(WineInfo wineInfo) {
        String version = wineInfo.fullVersion();
        if (version.endsWith(".0")) version = version.substring(0, version.length() - 2);
        String typeLabel = "proton".equalsIgnoreCase(wineInfo.type) ? "Proton " : "Wine ";
        return typeLabel + version + " " + wineInfo.getArch();
    }
}
