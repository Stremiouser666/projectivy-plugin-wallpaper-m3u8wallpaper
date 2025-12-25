package tv.projectivy.plugin.wallpaperprovider.bingwallpaper.ui;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.leanback.app.BrowseFragment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import tv.projectivy.plugin.wallpaperprovider.bingwallpaper.R;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";
    private static final String PROJECTIVY_PACKAGE = "com.spocky.projengmenu";

    private CheckBox bingCheckbox;
    private EditText titleInput;
    private EditText urlInput;
    private EditText descriptionInput;
    private EditText thumbnailInput;
    private RadioGroup typeRadioGroup;
    private Button addButton;
    private Button projectivyCheckButton;
    private TextView sourceListText;
    private Button refreshButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Initialize views
        bingCheckbox = findViewById(R.id.bing_checkbox);
        titleInput = findViewById(R.id.title_input);
        urlInput = findViewById(R.id.url_input);
        descriptionInput = findViewById(R.id.description_input);
        thumbnailInput = findViewById(R.id.thumbnail_input);
        typeRadioGroup = findViewById(R.id.type_radio_group);
        addButton = findViewById(R.id.add_button);
        projectivyCheckButton = findViewById(R.id.projectivy_check_button);
        sourceListText = findViewById(R.id.source_list_text);
        refreshButton = findViewById(R.id.refresh_button);

        // Set initial Bing checkbox state
        boolean includeBing = getSharedPreferences("bing_wallpaper_prefs", MODE_PRIVATE)
                .getBoolean("include_bing", true);
        bingCheckbox.setChecked(includeBing);

        // Bing checkbox listener
        bingCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences("bing_wallpaper_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("include_bing", isChecked)
                    .apply();
            Toast.makeText(this, "Bing wallpapers " + (isChecked ? "enabled" : "disabled"), 
                    Toast.LENGTH_SHORT).show();
        });

        // Add source button
        addButton.setOnClickListener(v -> addCustomSource());

        // Check Projectivy button
        projectivyCheckButton.setOnClickListener(v -> checkProjectivy());

        // Refresh list button
        refreshButton.setOnClickListener(v -> updateSourceList());

        // Update source list on start
        updateSourceList();
    }

    private void addCustomSource() {
        String title = titleInput.getText().toString().trim();
        String url = urlInput.getText().toString().trim();
        String description = descriptionInput.getText().toString().trim();
        String thumbnail = thumbnailInput.getText().toString().trim();

        // Validate inputs
        if (title.isEmpty()) {
            Toast.makeText(this, "Please enter a title", Toast.LENGTH_SHORT).show();
            return;
        }

        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show();
            return;
        }

        // Validate URL format
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get selected type
        String type = typeRadioGroup.getCheckedRadioButtonId() == R.id.type_video 
                ? "video" 
                : "image";

        // Validate m3u8 format if video type
        if (type.equals("video") && !url.endsWith(".m3u8") && !url.endsWith(".m3u")) {
            Toast.makeText(this, "Warning: Video URLs should typically be .m3u8 or .m3u streams", 
                    Toast.LENGTH_LONG).show();
        }

        // Generate unique ID
        String id = "custom_" + System.currentTimeMillis();

        try {
            JSONArray jsonArray = new JSONArray(
                    getSharedPreferences("bing_wallpaper_prefs", MODE_PRIVATE)
                            .getString("custom_sources", "[]"));
            
            JSONObject newSource = new JSONObject();
            newSource.put("id", id);
            newSource.put("title", title);
            newSource.put("url", url);
            newSource.put("type", type);
            newSource.put("description", description.isEmpty() ? "Custom Source" : description);
            newSource.put("thumbnail", thumbnail.isEmpty() ? url : thumbnail);
            
            jsonArray.put(newSource);
            
            getSharedPreferences("bing_wallpaper_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("custom_sources", jsonArray.toString())
                    .apply();
            
            Log.d(TAG, "Added custom source: " + title);
            Toast.makeText(this, "Source added successfully!", Toast.LENGTH_SHORT).show();
            
            // Clear inputs
            titleInput.setText("");
            urlInput.setText("");
            descriptionInput.setText("");
            thumbnailInput.setText("");
            typeRadioGroup.check(R.id.type_image);
            
            // Refresh list
            updateSourceList();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error adding source", e);
            Toast.makeText(this, "Error adding source: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateSourceList() {
        try {
            JSONArray jsonArray = new JSONArray(
                    getSharedPreferences("bing_wallpaper_prefs", MODE_PRIVATE)
                            .getString("custom_sources", "[]"));
            
            StringBuilder list = new StringBuilder("Custom Sources:\n\n");
            
            if (jsonArray.length() == 0) {
                list.append("No custom sources added yet.");
            } else {
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    list.append((i + 1)).append(". ").append(obj.getString("title")).append("\n")
                            .append("   Type: ").append(obj.getString("type")).append("\n")
                            .append("   URL: ").append(obj.getString("url")).append("\n\n");
                }
            }
            
            sourceListText.setText(list.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error updating source list", e);
        }
    }

    private void checkProjectivy() {
        if (isProjectivyInstalled()) {
            Toast.makeText(this, "✓ Projectivy Launcher is installed!", 
                    Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Projectivy is installed");
        } else {
            Toast.makeText(this, getString(R.string.projectivy_not_installed), 
                    Toast.LENGTH_LONG).show();
            Log.w(TAG, "Projectivy is NOT installed");
        }
    }

    private boolean isProjectivyInstalled() {
        try {
            getPackageManager().getPackageInfo(PROJECTIVY_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Projectivy not found", e);
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isProjectivyInstalled()) {
            Log.w(TAG, "Warning: Projectivy not installed");
        }
    }
}