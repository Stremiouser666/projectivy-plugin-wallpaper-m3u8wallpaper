package tv.projectivy.plugin.wallpaperprovider.bingwallpaper.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import tv.projectivy.sdk.models.WallpaperItem;
import tv.projectivy.sdk.services.WallpaperProviderService;

public class WallpaperProviderService extends WallpaperProviderService {
    private static final String TAG = "BingWallpaperPlugin";
    private static final String BING_API_URL = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US";
    private static final String PREFS_NAME = "bing_wallpaper_prefs";
    private static final String PREF_CUSTOM_SOURCES = "custom_sources";
    private static final String PREF_INCLUDE_BING = "include_bing";

    private OkHttpClient httpClient;
    private SharedPreferences prefs;
    private Gson gson;

    @Override
    public void onCreate() {
        super.onCreate();
        httpClient = new OkHttpClient();
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    @Override
    public void onGetWallpapers(WallpaperProviderCallback callback) {
        Log.d(TAG, "onGetWallpapers called");
        
        List<WallpaperItem> allWallpapers = new ArrayList<>();
        
        // Check if we should include Bing images
        boolean includeBing = prefs.getBoolean(PREF_INCLUDE_BING, true);
        
        if (includeBing) {
            try {
                List<WallpaperItem> bingWallpapers = fetchBingWallpapers();
                allWallpapers.addAll(bingWallpapers);
                Log.d(TAG, "Successfully fetched " + bingWallpapers.size() + " Bing wallpapers");
            } catch (IOException e) {
                Log.e(TAG, "Error fetching Bing wallpapers", e);
            }
        }
        
        // Add custom sources
        List<WallpaperItem> customWallpapers = getCustomWallpapers();
        allWallpapers.addAll(customWallpapers);
        
        if (allWallpapers.isEmpty()) {
            callback.onError("No wallpapers available");
        } else {
            callback.onSuccess(allWallpapers);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
        }
    }

    private List<WallpaperItem> fetchBingWallpapers() throws IOException {
        List<WallpaperItem> wallpapers = new ArrayList<>();
        
        Request request = new Request.Builder()
                .url(BING_API_URL)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            String responseBody = response.body().string();
            JSONObject jsonResponse = new JSONObject(responseBody);
            JSONArray images = jsonResponse.getJSONArray("images");

            for (int i = 0; i < images.length(); i++) {
                JSONObject imageObj = images.getJSONObject(i);
                
                String url = imageObj.getString("url");
                String title = imageObj.getString("title");
                String copyright = imageObj.getString("copyright");
                
                // Construct full image URL
                String fullImageUrl = "https://www.bing.com" + url;
                
                WallpaperItem wallpaper = new WallpaperItem();
                wallpaper.setId("bing_" + i);
                wallpaper.setTitle(title);
                wallpaper.setDescription("Bing - " + copyright);
                wallpaper.setImageUrl(fullImageUrl);
                wallpaper.setThumbnailUrl(fullImageUrl);
                
                wallpapers.add(wallpaper);
                
                Log.d(TAG, "Added Bing wallpaper: " + title);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing Bing response", e);
            throw new IOException("Failed to parse response", e);
        }

        return wallpapers;
    }

    /**
     * Get custom wallpapers/streams added by user
     */
    private List<WallpaperItem> getCustomWallpapers() {
        List<WallpaperItem> customWallpapers = new ArrayList<>();
        
        try {
            String jsonString = prefs.getString(PREF_CUSTOM_SOURCES, "[]");
            JSONArray jsonArray = new JSONArray(jsonString);
            
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                
                WallpaperItem wallpaper = new WallpaperItem();
                wallpaper.setId(obj.getString("id"));
                wallpaper.setTitle(obj.getString("title"));
                wallpaper.setDescription(obj.optString("description", "Custom Source"));
                wallpaper.setImageUrl(obj.getString("url"));
                wallpaper.setThumbnailUrl(obj.optString("thumbnail", obj.getString("url")));
                
                customWallpapers.add(wallpaper);
                Log.d(TAG, "Added custom wallpaper: " + wallpaper.getTitle());
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing custom wallpapers", e);
        }
        
        return customWallpapers;
    }

    /**
     * Add a custom wallpaper source (image or m3u8 stream)
     */
    public void addCustomWallpaper(String id, String title, String url, 
                                   String description, String thumbnailUrl) {
        try {
            String jsonString = prefs.getString(PREF_CUSTOM_SOURCES, "[]");
            JSONArray jsonArray = new JSONArray(jsonString);
            
            JSONObject newSource = new JSONObject();
            newSource.put("id", id);
            newSource.put("title", title);
            newSource.put("url", url);
            newSource.put("description", description != null ? description : "");
            newSource.put("thumbnail", thumbnailUrl != null ? thumbnailUrl : url);
            
            jsonArray.put(newSource);
            
            prefs.edit()
                    .putString(PREF_CUSTOM_SOURCES, jsonArray.toString())
                    .apply();
            
            Log.d(TAG, "Added custom source: " + title);
        } catch (JSONException e) {
            Log.e(TAG, "Error adding custom wallpaper", e);
        }
    }

    /**
     * Remove a custom wallpaper source
     */
    public void removeCustomWallpaper(String id) {
        try {
            String jsonString = prefs.getString(PREF_CUSTOM_SOURCES, "[]");
            JSONArray jsonArray = new JSONArray(jsonString);
            JSONArray newArray = new JSONArray();
            
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                if (!obj.getString("id").equals(id)) {
                    newArray.put(obj);
                }
            }
            
            prefs.edit()
                    .putString(PREF_CUSTOM_SOURCES, newArray.toString())
                    .apply();
            
            Log.d(TAG, "Removed custom source: " + id);
        } catch (JSONException e) {
            Log.e(TAG, "Error removing custom wallpaper", e);
        }
    }

    /**
     * Set whether to include Bing wallpapers
     */
    public void setIncludeBing(boolean include) {
        prefs.edit().putBoolean(PREF_INCLUDE_BING, include).apply();
        Log.d(TAG, "Include Bing: " + include);
    }
}