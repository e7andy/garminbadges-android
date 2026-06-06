package com.garminbadges.app;

import java.io.IOException;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GarminApiClient {

    private static final String BASE_URL = "https://connect.garmin.com/gc-api";

    private final OkHttpClient httpClient;
    private final String cookies;
    private final String csrfToken;

    public GarminApiClient(OkHttpClient httpClient, String cookies, String csrfToken) {
        this.httpClient = httpClient;
        this.cookies = cookies;
        this.csrfToken = csrfToken;
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
            .header("Accept", "application/json")
            .header("di-backend", "connectapi.garmin.com")
            .header("nk", "NT")
            .header("connect-csrf-token", csrfToken)
            .header("Cookie", cookies)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Garmin API error: " + response.code() + " on " + path);
            }
            return response.body().string();
        }
    }
}
