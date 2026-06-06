package com.garminbadges.app;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class GarminBadgesApiClient {

    private static final String BASE_URL = "https://api.garminbadges.com/api";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String apiKey;

    public GarminBadgesApiClient(OkHttpClient httpClient, String apiKey) {
        this.httpClient = httpClient;
        this.apiKey = apiKey;
    }

    public String getBadges() throws IOException {
        Request request = new Request.Builder()
            .url(BASE_URL + "/badges")
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/json")
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Badges API error: " + response.code());
            }
            return response.body().string();
        }
    }

    public JSONObject sync(JSONArray records, String garminUsername) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("user_badges", records);
        if (garminUsername != null && !garminUsername.isEmpty()) {
            body.put("garmin_username", garminUsername);
        }

        RequestBody requestBody = RequestBody.create(body.toString(), JSON);
        Request request = new Request.Builder()
            .url(BASE_URL + "/sync")
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/json")
            .post(requestBody)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 401) throw new IOException("INVALID_API_KEY");
            if (response.code() == 422) throw new IOException("VALIDATION_ERROR");
            if (!response.isSuccessful()) throw new IOException("Upload failed: " + response.code());
            return new JSONObject(response.body().string());
        }
    }
}
