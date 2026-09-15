package com.winlator.cmod.games;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.Callback;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-select dialog that lets the user pick which containers should
 * receive the new shortcuts.
 *
 * <p>Uses a custom {@link ListView} instead of
 * {@code AlertDialog.setMultiChoiceItems} because {@code setMessage} and
 * {@code setMultiChoiceItems} together hide the list on many devices.</p>
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
            new AlertDialog.Builder(context)
                    .setTitle(R.string.scan_games_pick_containers)
                    .setMessage(R.string.no_items_to_display)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        String[] names = new String[containers.size()];
        final boolean[] checked = new boolean[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            names[i] = containers.get(i).getName();
            checked[i] = true;
        }

        View content = LayoutInflater.from(context).inflate(R.layout.dialog_container_multiselect, null);
        TextView message = content.findViewById(R.id.TVMessage);
        message.setText(R.string.scan_games_pick_containers_msg);

        ListView listView = content.findViewById(R.id.ListView);
        listView.setAdapter(new ArrayAdapter<>(context,
                android.R.layout.simple_list_item_multiple_choice, names));
        for (int i = 0; i < checked.length; i++) {
            listView.setItemChecked(i, checked[i]);
        }
        listView.setOnItemClickListener((parent, view, position, id) ->
                checked[position] = listView.isItemChecked(position));

        new AlertDialog.Builder(context)
                .setTitle(R.string.scan_games_pick_containers)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<Container> selected = new ArrayList<>();
                    for (int i = 0; i < containers.size(); i++) {
                        if (checked[i]) selected.add(containers.get(i));
                    }
                    if (onPicked != null) onPicked.call(selected);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
