package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WeatherHelper {

    public static final class HourForecast {
        public final String time;
        public final String icon;
        public final String temp;

        public HourForecast(String time, String icon, String temp) {
            this.time = time;
            this.icon = icon;
            this.temp = temp;
        }
    }

    public static final class WeatherData {
        public final String city;
        public final String currentTemp;
        public final String condition;
        public final List<HourForecast> hours;

        public WeatherData(String city, String currentTemp, String condition, List<HourForecast> hours) {
            this.city = city;
            this.currentTemp = currentTemp;
            this.condition = condition;
            this.hours = hours;
        }
    }

    public interface Callback {
        void onWeatherLoaded(WeatherData data);
    }

    public interface ErrorCallback {
        void onError(String message);
    }

    private static final String PREFS_NAME = "launcher_weather";
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Callback callback;
    private long lastFetchTime = 0;
    private WeatherData cachedData;

    public WeatherHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
        if (cachedData != null) {
            callback.onWeatherLoaded(cachedData);
        }
    }

    public void refresh() {
        if (System.currentTimeMillis() - lastFetchTime < 60_000L && cachedData != null) {
            if (callback != null) callback.onWeatherLoaded(cachedData);
            return;
        }
        executor.execute(this::fetchInBackground);
    }

    public void setLocation(String cityName, Runnable onDone, ErrorCallback onError) {
        executor.execute(() -> {
            try {
                String query = URLEncoder.encode(cityName.trim(), "UTF-8");
                String url = "https://geocoding-api.open-meteo.com/v1/search?name=" + query + "&count=1&language=en&format=json";
                String json = httpGet(url);
                JSONObject root = new JSONObject(json);
                JSONArray results = root.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject first = results.getJSONObject(0);
                    String name = first.optString("name", cityName);
                    String country = first.optString("country_code", "");
                    double lat = first.getDouble("latitude");
                    double lon = first.getDouble("longitude");

                    String displayCity = country.isEmpty() ? name : (name + ", " + country.toUpperCase(Locale.US));
                    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs.edit()
                            .putString("city", displayCity)
                            .putLong("lat", Double.doubleToRawLongBits(lat))
                            .putLong("lon", Double.doubleToRawLongBits(lon))
                            .apply();

                    lastFetchTime = 0;
                    fetchInBackground();
                    if (onDone != null) mainHandler.post(onDone);
                    return;
                }
                if (onError != null) mainHandler.post(() -> onError.onError("City not found: " + cityName));
            } catch (Exception e) {
                if (onError != null) mainHandler.post(() -> onError.onError("Search failed: " + e.getMessage()));
            }
        });
    }

    public void setAutoLocation(Runnable onDone) {
        executor.execute(() -> {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove("city").remove("lat").remove("lon").apply();
            lastFetchTime = 0;
            fetchInBackground();
            if (onDone != null) mainHandler.post(onDone);
        });
    }

    private void fetchInBackground() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        double lat = Double.longBitsToDouble(prefs.getLong("lat", 0));
        double lon = Double.longBitsToDouble(prefs.getLong("lon", 0));
        String city = prefs.getString("city", null);

        // Auto-locate if no city/coordinates are stored
        if (city == null || (lat == 0 && lon == 0)) {
            try {
                String ipJson = httpGet("http://ip-api.com/json");
                JSONObject obj = new JSONObject(ipJson);
                if ("success".equalsIgnoreCase(obj.optString("status"))) {
                    city = obj.optString("city", "Local");
                    lat = obj.optDouble("lat", 0);
                    lon = obj.optDouble("lon", 0);
                    prefs.edit()
                            .putString("city", city)
                            .putLong("lat", Double.doubleToRawLongBits(lat))
                            .putLong("lon", Double.doubleToRawLongBits(lon))
                            .apply();
                }
            } catch (Exception ignored) { }
        }

        if (city == null) city = "Local Weather";
        if (lat == 0 && lon == 0) {
            lat = 18.52;
            lon = 73.85;
        }

        try {
            String url = String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day&forecast_hours=14&timezone=auto",
                    lat, lon);
            String weatherJson = httpGet(url);
            JSONObject root = new JSONObject(weatherJson);

            JSONObject current = root.getJSONObject("current");
            double curTemp = current.getDouble("temperature_2m");
            int curCode = current.getInt("weather_code");
            boolean curIsDay = current.getInt("is_day") == 1;

            String currentTempStr = Math.round(curTemp) + "°";
            String conditionStr = getConditionDescription(curCode, curIsDay);

            JSONObject hourly = root.getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.getJSONArray("is_day");

            List<HourForecast> hourList = new ArrayList<>();
            int total = Math.min(times.length(), 7);
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
            SimpleDateFormat hourFormatter = new SimpleDateFormat("ha", Locale.ROOT);

            for (int i = 0; i < total; i++) {
                String timeLabel = "Now";
                if (i > 0) {
                    try {
                        Date d = parser.parse(times.getString(i));
                        if (d != null) {
                            timeLabel = hourFormatter.format(d).toLowerCase();
                        }
                    } catch (Exception e) {
                        timeLabel = "+" + i + "h";
                    }
                }
                double t = temps.getDouble(i);
                int c = codes.getInt(i);
                boolean d = days.getInt(i) == 1;
                String icon = getWeatherIcon(c, d);
                String tStr = Math.round(t) + "°";
                hourList.add(new HourForecast(timeLabel, icon, tStr));
            }

            final WeatherData data = new WeatherData(city, currentTempStr, conditionStr, hourList);
            cachedData = data;
            lastFetchTime = System.currentTimeMillis();

            mainHandler.post(() -> {
                if (callback != null) callback.onWeatherLoaded(data);
            });
        } catch (Exception ignored) { }
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }

    private static String getConditionDescription(int code, boolean isDay) {
        switch (code) {
            case 0: return isDay ? "Clear Sky" : "Clear Night";
            case 1: return isDay ? "Mainly Clear" : "Clear";
            case 2: return "Partly Cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Foggy";
            case 51: case 53: case 55: return "Drizzle";
            case 61: case 63: case 65: return "Rain";
            case 71: case 73: case 75: return "Snow";
            case 80: case 81: case 82: return "Showers";
            case 95: case 96: case 99: return "Thunderstorm";
            default: return "Fair";
        }
    }

    private static String getWeatherIcon(int code, boolean isDay) {
        switch (code) {
            case 0: return isDay ? "☀️" : "🌙";
            case 1: return isDay ? "🌤️" : "🌙";
            case 2: return isDay ? "⛅" : "☁️";
            case 3: return "☁️";
            case 45: case 48: return "🌫️";
            case 51: case 53: case 55: return "🌦️";
            case 61: case 63: case 65: return "🌧️";
            case 71: case 73: case 75: return "❄️";
            case 80: case 81: case 82: return "🌧️";
            case 95: case 96: case 99: return "⛈️";
            default: return isDay ? "☀️" : "🌙";
        }
    }
}
