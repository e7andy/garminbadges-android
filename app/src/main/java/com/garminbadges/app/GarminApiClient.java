package com.garminbadges.app;

import java.io.IOException;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GarminApiClient {

    private static final String BASE_URL = "https://connectapi.garmin.com";

    private final OkHttpClient httpClient;
    private final String accessToken;

    public GarminApiClient(OkHttpClient httpClient, String accessToken) {
        this.httpClient = httpClient;
        this.accessToken = accessToken;
    }

    public String get(String path, Map<String, String> params) throws IOException {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + path).newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                urlBuilder.addQueryParameter(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Garmin API error: " + response.code() + " on " + path);
            }
            return response.body().string();
        }
    }
}
