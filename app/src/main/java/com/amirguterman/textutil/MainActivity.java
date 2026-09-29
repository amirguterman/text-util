package com.amirguterman.textutil;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final String STORAGE_KEY = "notes.v1";
    private final List<Note> notes = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Spinner selector;
    private EditText editor, query;
    private LinearLayout results;
    private TextView status;
    private String selectedId;
    private boolean switching;
    private final Runnable saveJob = this::persist;
    private final Runnable searchJob = this::runSearch;

    private static final class Note {
        String id, body;
        Note(String id, String body) { this.id = id; this.body = body; }
        String title() {
            String first = body.split("\\R", 2)[0].trim();
            return first.isEmpty() ? "Untitled note" : first.length() > 32 ? first.substring(0, 32) + "…" : first;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        load();
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        setContentView(root);

        TextView heading = new TextView(this);
        heading.setText("Text Util"); heading.setTextSize(24); heading.setTypeface(null, Typeface.BOLD);
        root.addView(heading);

        LinearLayout toolbar = row(); root.addView(toolbar);
        selector = new Spinner(this);
        toolbar.addView(selector, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button add = button("New", toolbar); add.setOnClickListener(v -> createNote());
        Button remove = button("Delete", toolbar); remove.setOnClickListener(v -> deleteNote());

        editor = new EditText(this);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextSize(17);
        editor.setHint("Write a note…");
        editor.setSingleLine(false);
        editor.setMinLines(7);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView label = new TextView(this); label.setText("Search this note");
        label.setTypeface(null, Typeface.BOLD); root.addView(label);
        LinearLayout searchRow = row(); root.addView(searchRow);
        query = new EditText(this); query.setSingleLine(true);
        query.setTextSize(15); query.setHint("Text, {int.range(1, 4)}, or regex:…");
        searchRow.addView(query, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button search = button("Find", searchRow); search.setOnClickListener(v -> runSearch());
        status = new TextView(this); status.setPadding(0, dp(5), 0, dp(5)); root.addView(status);
        ScrollView scroll = new ScrollView(this);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(168)));

        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (switching || position >= notes.size()) return;
                saveCurrent(); selectedId = notes.get(position).id;
                showSelected();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        editor.addTextChangedListener(watcher(() -> {
            if (switching) return;
            saveCurrent(); scheduleSave(); scheduleSearch();
        }));
        query.addTextChangedListener(watcher(this::scheduleSearch));
        selectedId = notes.get(0).id;
        refreshNotes(); showSelected();
    }

    private TextWatcher watcher(Runnable action) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); }
            @Override public void afterTextChanged(Editable e) { }
        };
    }

    private void createNote() {
        saveCurrent();
        Note note = new Note(UUID.randomUUID().toString(), "");
        notes.add(0, note); selectedId = note.id;
        refreshNotes(); showSelected(); persist(); editor.requestFocus();
    }

    private void deleteNote() {
        new AlertDialog.Builder(this).setTitle("Delete note?")
            .setMessage("This removes the current note from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> {
                Note note = current(); if (note == null) return;
                notes.remove(note);
                if (notes.isEmpty()) notes.add(new Note(UUID.randomUUID().toString(), ""));
                selectedId = notes.get(0).id;
                refreshNotes(); showSelected(); persist();
            }).show();
    }

    private Note current() {
        for (Note note : notes) if (note.id.equals(selectedId)) return note;
        return null;
    }

    private void saveCurrent() {
        Note note = current();
        if (note != null && editor != null && !switching) {
            String body = editor.getText().toString();
            if (!note.body.equals(body)) { note.body = body; refreshNotes(); }
        }
    }

    private void refreshNotes() {
        if (selector == null) return;
        switching = true;
        List<String> titles = new ArrayList<>();
        int index = 0;
        for (int i = 0; i < notes.size(); i++) {
            Note note = notes.get(i); titles.add(note.title());
            if (note.id.equals(selectedId)) index = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, titles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selector.setAdapter(adapter); selector.setSelection(index);
        switching = false;
    }

    private void showSelected() {
        Note note = current(); if (note == null) return;
        switching = true; editor.setText(note.body); editor.setSelection(0); switching = false;
        runSearch();
    }

    private void scheduleSave() { handler.removeCallbacks(saveJob); handler.postDelayed(saveJob, 400); }
    private void scheduleSearch() { handler.removeCallbacks(searchJob); handler.postDelayed(searchJob, 250); }

    private void runSearch() {
        if (results == null) return;
        results.removeAllViews();
        String expression = query.getText().toString();
        if (expression.isEmpty()) { status.setText("Type a query to search. Prefix raw regex with regex:"); return; }
        try {
            List<SearchEngine.Hit> hits = SearchEngine.search(editor.getText().toString(), expression);
            status.setText(hits.size() + (hits.size() == 1 ? " result" : " results"));
            int count = Math.min(hits.size(), 100);
            for (int i = 0; i < count; i++) {
                SearchEngine.Hit hit = hits.get(i);
                TextView item = new TextView(this);
                item.setText("Line " + hit.line + "  ·  " + hit.preview);
                item.setTextSize(15); item.setPadding(dp(8), dp(9), dp(8), dp(9));
                item.setBackgroundResource(android.R.drawable.list_selector_background);
                item.setOnClickListener(v -> {
                    editor.requestFocus(); editor.setSelection(hit.start, hit.end);
                });
                results.addView(item);
            }
            if (hits.size() > count) status.append(" (showing first 100)");
        } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
    }

    private void load() {
        try {
            JSONArray stored = new JSONArray(getPreferences(Context.MODE_PRIVATE).getString(STORAGE_KEY, "[]"));
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.getJSONObject(i);
                notes.add(new Note(item.getString("id"), item.getString("body")));
            }
        } catch (JSONException e) {
            notes.clear(); Toast.makeText(this, "Could not load saved notes", Toast.LENGTH_LONG).show();
        }
        if (notes.isEmpty()) notes.add(new Note(UUID.randomUUID().toString(), ""));
    }

    private void persist() {
        saveCurrent();
        JSONArray array = new JSONArray();
        try {
            for (Note note : notes) array.put(new JSONObject().put("id", note.id).put("body", note.body));
            if (!getPreferences(Context.MODE_PRIVATE).edit().putString(STORAGE_KEY, array.toString()).commit())
                Toast.makeText(this, "Could not save notes", Toast.LENGTH_LONG).show();
        } catch (JSONException e) { Toast.makeText(this, "Could not save notes", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onPause() { handler.removeCallbacks(saveJob); saveCurrent(); persist(); super.onPause(); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private Button button(String label, LinearLayout row) { Button button = new Button(this); button.setText(label); row.addView(button); return button; }
}
