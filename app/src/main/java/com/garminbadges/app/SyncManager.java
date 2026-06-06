package com.garminbadges.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyncManager {

    public interface Callback {
        void onProgress(String message);
        void onComplete(int recordCount, JSONObject response);
        void onError(String message);
    }

    private final GarminApiClient garmin;
    private final GarminBadgesApiClient badgesApi;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    public SyncManager(GarminApiClient garmin, GarminBadgesApiClient badgesApi) {
        this.garmin = garmin;
        this.badgesApi = badgesApi;
    }

    public void sync(Callback callback) {
        try {
            // 0. Garmin username
            String garminUsername = "";
            try {
                JSONObject profile = new JSONObject(garmin.get("/userprofile-service/socialProfile", null));
                garminUsername = profile.optString("userName", profile.optString("displayName", ""));
            } catch (Exception ignored) {}

            // 1. Earned badges
            callback.onProgress("Fetching earned badges…");
            JSONArray earned = new JSONArray(garmin.get("/badge-service/badge/earned", null));
            callback.onProgress("Found " + earned.length() + " earned badge records");

            // 2. Non-completed challenges
            callback.onProgress("Fetching active challenges…");
            JSONArray challenges = new JSONArray();
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("desc", "true");
                params.put("start", "1");
                params.put("includeExclusive", "true");
                params.put("limit", "10000");
                challenges = parseArrayOrObject(
                    garmin.get("/badgechallenge-service/badgeChallenge/non-completed", params),
                    "badgeChallengeList"
                );
                callback.onProgress("Found " + challenges.length() + " active challenges");
            } catch (Exception e) {
                callback.onProgress("Could not fetch challenges — skipping");
            }

            // 2b. In-progress virtual challenges
            try {
                JSONArray virtual = parseArrayOrObject(
                    garmin.get("/badgechallenge-service/virtualChallenge/inProgress", null),
                    "virtualChallengeList"
                );
                if (virtual.length() > 0) {
                    callback.onProgress("Found " + virtual.length() + " in-progress virtual challenges");
                    challenges = concat(challenges, virtual);
                }
            } catch (Exception e) {
                callback.onProgress("Could not fetch virtual challenges — skipping");
            }

            // 2c. Available badges with progress targets
            JSONArray available = new JSONArray();
            try {
                JSONArray all = new JSONArray(garmin.get("/badge-service/badge/available", null));
                for (int i = 0; i < all.length(); i++) {
                    JSONObject b = all.getJSONObject(i);
                    if (!b.isNull("badgeTargetValue") && b.optInt("badgeCategoryId", -1) != 4) {
                        available.put(b);
                    }
                }
                if (available.length() > 0) {
                    callback.onProgress("Found " + available.length() + " available badges with targets");
                }
            } catch (Exception e) {
                callback.onProgress("Could not fetch available badges — skipping");
            }

            // 3. Site badge catalogue
            JSONArray siteBadges = new JSONArray();
            try {
                siteBadges = new JSONArray(badgesApi.getBadges());
            } catch (Exception ignored) {}

            Map<Integer, String> nameById = new HashMap<>();
            Set<Integer> repeatableSiteIds = new HashSet<>();
            for (int i = 0; i < siteBadges.length(); i++) {
                JSONObject b = siteBadges.getJSONObject(i);
                int id = b.optInt("id", 0);
                String name = b.optString("name", "");
                if (id != 0 && !name.isEmpty()) nameById.put(id, name);
                if (b.optBoolean("repeatable") && id != 0) repeatableSiteIds.add(id);
            }

            // 4. Determine which badges need v3 detail fetch
            Set<Integer> earnedIds = new HashSet<>();
            for (int i = 0; i < earned.length(); i++) {
                int id = earned.getJSONObject(i).optInt("badgeId", 0);
                if (id != 0) earnedIds.add(id);
            }
            Set<Integer> challengeIds = new HashSet<>();
            for (int i = 0; i < challenges.length(); i++) {
                int id = challenges.getJSONObject(i).optInt("badgeId", 0);
                if (id != 0) challengeIds.add(id);
            }
            Set<Integer> relevantIds = new HashSet<>(challengeIds);
            for (int id : earnedIds) {
                if (repeatableSiteIds.contains(id)) relevantIds.add(id);
            }

            // Merge challenges + available, deduplicate numbered series
            JSONArray allChallenges = deduplicateNumberedSeries(concat(challenges, available), nameById);

            // 4a. Fetch v3 details in parallel
            Map<Integer, JSONObject> details = new ConcurrentHashMap<>();
            if (!relevantIds.isEmpty()) {
                callback.onProgress("Fetching progress for " + relevantIds.size() + " badges…");
                List<Future<?>> futures = new ArrayList<>();
                for (int id : relevantIds) {
                    final int badgeId = id;
                    futures.add(pool.submit(() -> {
                        try {
                            String raw = garmin.get("/badge-service/badge/detail/v3/" + badgeId, null);
                            details.put(badgeId, new JSONObject(raw));
                        } catch (Exception ignored) {}
                    }));
                }
                for (Future<?> f : futures) {
                    try { f.get(); } catch (Exception ignored) {}
                }
            }

            // 4b. Repeatable earn history
            Map<Integer, Integer> earnCounts = new HashMap<>();
            for (int i = 0; i < earned.length(); i++) {
                int id = earned.getJSONObject(i).optInt("badgeId", 0);
                if (id != 0) earnCounts.merge(id, 1, Integer::sum);
            }

            Map<Integer, Integer> maxEarnedByBadge = new HashMap<>();
            for (int i = 0; i < earned.length(); i++) {
                JSONObject b = earned.getJSONObject(i);
                int id = b.optInt("badgeId", 0);
                if (id == 0) continue;
                int num = b.optInt("badgeEarnedNumber", b.optInt("earnedNumber", 1));
                if (num > maxEarnedByBadge.getOrDefault(id, 0)) maxEarnedByBadge.put(id, num);
            }

            List<Integer> repeatBadgeIds = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : earnCounts.entrySet()) {
                int id = entry.getKey();
                if (repeatableSiteIds.contains(id) || entry.getValue() > 1) {
                    repeatBadgeIds.add(id);
                }
            }

            // repeatableEarns: badgeId -> earnedNumber -> {earned_date, assoc_type_id, assoc_data_id}
            Map<Integer, Map<Integer, JSONObject>> repeatableEarns = new ConcurrentHashMap<>();
            if (!garminUsername.isEmpty() && !repeatBadgeIds.isEmpty()) {
                callback.onProgress("Fetching repeat earn history for " + repeatBadgeIds.size() + " badges…");
                List<Future<?>> futures = new ArrayList<>();
                final String username = garminUsername;
                for (int id : repeatBadgeIds) {
                    final int badgeId = id;
                    futures.add(pool.submit(() -> {
                        try {
                            Map<String, String> params = new LinkedHashMap<>();
                            params.put("start", "1");
                            params.put("limit", "1000");
                            String raw = garmin.get(
                                "/badge-service/badge/" + username + "/earned/detail/repeatable/v2/" + badgeId,
                                params
                            );
                            JSONArray earns = parseEarnsResponse(raw);
                            int maxNum = maxEarnedByBadge.getOrDefault(badgeId, earns.length());
                            Map<Integer, JSONObject> earnMap = new HashMap<>();
                            for (int i = 0; i < earns.length(); i++) {
                                JSONObject earn = earns.getJSONObject(i);
                                int num;
                                if (earn.has("badgeEarnedNumber") && !earn.isNull("badgeEarnedNumber")) {
                                    num = earn.getInt("badgeEarnedNumber");
                                } else if (earn.has("earnedNumber") && !earn.isNull("earnedNumber")) {
                                    num = earn.getInt("earnedNumber");
                                } else {
                                    num = maxNum - i;
                                }
                                String date = earn.optString("badgeEarnedDate",
                                              earn.optString("earnedDate",
                                              earn.optString("date", "")));
                                if (num > 0 && !date.isEmpty()) {
                                    JSONObject info = new JSONObject();
                                    info.put("earned_date", date);
                                    info.put("assoc_type_id", earn.isNull("badgeAssocTypeId") ? JSONObject.NULL : earn.opt("badgeAssocTypeId"));
                                    String assocDataId = earn.optString("badgeAssocDataId", "");
                                    info.put("assoc_data_id", assocDataId.isEmpty() ? JSONObject.NULL : assocDataId);
                                    earnMap.put(num, info);
                                }
                            }
                            if (!earnMap.isEmpty()) repeatableEarns.put(badgeId, earnMap);
                        } catch (Exception ignored) {}
                    }));
                }
                for (Future<?> f : futures) {
                    try { f.get(); } catch (Exception ignored) {}
                }

                int totalFound = repeatableEarns.values().stream().mapToInt(Map::size).sum();
                if (totalFound > 0) {
                    callback.onProgress("Found " + totalFound + " historical earn records across " + repeatableEarns.size() + " badges");
                }
            }

            // 5. Map to upload schema
            JSONArray records = new JSONArray();
            Set<String> seen = new HashSet<>();

            // Earned records
            for (int i = 0; i < earned.length(); i++) {
                JSONObject b = earned.getJSONObject(i);
                int badgeId = b.optInt("badgeId", 0);
                if (badgeId == 0) continue;
                int num = b.optInt("badgeEarnedNumber", b.optInt("earnedNumber", 1));
                String key = badgeId + ":" + num;
                if (seen.contains(key)) continue;
                seen.add(key);

                JSONObject repeatInfo = repeatableEarns.containsKey(badgeId)
                    ? repeatableEarns.get(badgeId).get(num) : null;
                String earnedDate = repeatInfo != null
                    ? repeatInfo.optString("earned_date", "")
                    : b.optString("badgeEarnedDate", "");

                JSONObject record = new JSONObject();
                record.put("badge_id", badgeId);
                record.put("earned_number", num);
                record.put("earned_date", earnedDate.isEmpty() ? JSONObject.NULL : earnedDate);
                record.put("progress_value", b.isNull("badgeProgressValue") ? JSONObject.NULL : b.opt("badgeProgressValue"));
                record.put("assoc_type_id", b.isNull("badgeAssocTypeId") ? JSONObject.NULL : b.opt("badgeAssocTypeId"));
                String assocDataId = b.optString("badgeAssocDataId", "");
                record.put("assoc_data_id", assocDataId.isEmpty() ? JSONObject.NULL : assocDataId);
                String createDate = b.optString("badgeCreateDate", b.optString("badgeEarnedDate", ""));
                record.put("create_date", createDate.isEmpty() ? JSONObject.NULL : createDate);
                records.put(record);
            }

            // In-progress records from v3 details
            for (Map.Entry<Integer, JSONObject> entry : details.entrySet()) {
                int badgeId = entry.getKey();
                JSONObject detail = entry.getValue();
                Object progressObj = detail.opt("badgeProgressValue");
                if (progressObj == null || progressObj == JSONObject.NULL) continue;

                // Skip if already earned and at target
                Object targetObj = detail.opt("badgeTargetValue");
                String earnedDate = detail.optString("badgeEarnedDate", "");
                if (!earnedDate.isEmpty() && targetObj != null && targetObj != JSONObject.NULL) {
                    if (((Number) progressObj).doubleValue() >= ((Number) targetObj).doubleValue()) continue;
                }

                int lastNum = detail.optInt("badgeEarnedNumber", 0);
                int num = lastNum + 1;
                String key = badgeId + ":" + num;
                if (seen.contains(key)) continue;
                seen.add(key);

                JSONObject record = new JSONObject();
                record.put("badge_id", badgeId);
                record.put("earned_number", num);
                record.put("earned_date", JSONObject.NULL);
                record.put("progress_value", progressObj);
                record.put("assoc_type_id", detail.isNull("badgeAssocTypeId") ? JSONObject.NULL : detail.opt("badgeAssocTypeId"));
                String assocDataId = detail.optString("badgeAssocDataId", "");
                record.put("assoc_data_id", assocDataId.isEmpty() ? JSONObject.NULL : assocDataId);
                String createDate = detail.optString("createDate", "");
                record.put("create_date", createDate.isEmpty() ? JSONObject.NULL : createDate);
                records.put(record);
            }

            // Challenge/available records (joined, not yet earned)
            Set<Integer> earnedBadgeIds = new HashSet<>();
            for (int i = 0; i < records.length(); i++) {
                earnedBadgeIds.add(records.getJSONObject(i).getInt("badge_id"));
            }
            for (int i = 0; i < allChallenges.length(); i++) {
                JSONObject c = allChallenges.getJSONObject(i);
                int badgeId = c.optInt("badgeId", 0);
                if (badgeId == 0 || earnedBadgeIds.contains(badgeId)) continue;
                String key = badgeId + ":1";
                if (seen.contains(key)) continue;
                seen.add(key);

                Object progressVal = c.opt("userProfileBadgeProgressValue");
                if (progressVal == null || progressVal == JSONObject.NULL) progressVal = c.opt("badgeProgressValue");
                if (progressVal == null || progressVal == JSONObject.NULL) progressVal = c.opt("progressValue");
                if (progressVal == null) progressVal = 0;

                JSONObject record = new JSONObject();
                record.put("badge_id", badgeId);
                record.put("earned_number", 1);
                record.put("earned_date", JSONObject.NULL);
                record.put("progress_value", progressVal);
                record.put("assoc_type_id", c.isNull("badgeAssocTypeId") ? JSONObject.NULL : c.opt("badgeAssocTypeId"));
                record.put("assoc_data_id", JSONObject.NULL);
                String createDate = c.optString("joinDateLocal",
                                    c.optString("createDate",
                                    c.optString("badgeCreateDate", "")));
                record.put("create_date", createDate.isEmpty() ? JSONObject.NULL : createDate);
                records.put(record);
            }

            // 5b. Backfill historical repeatable earns not in badge/earned
            for (Map.Entry<Integer, Map<Integer, JSONObject>> entry : repeatableEarns.entrySet()) {
                int badgeId = entry.getKey();
                for (Map.Entry<Integer, JSONObject> earnEntry : entry.getValue().entrySet()) {
                    int num = earnEntry.getKey();
                    JSONObject info = earnEntry.getValue();
                    String key = badgeId + ":" + num;
                    if (seen.contains(key)) continue;
                    seen.add(key);

                    JSONObject record = new JSONObject();
                    record.put("badge_id", badgeId);
                    record.put("earned_number", num);
                    record.put("earned_date", info.optString("earned_date", ""));
                    record.put("progress_value", JSONObject.NULL);
                    record.put("assoc_type_id", info.isNull("assoc_type_id") ? JSONObject.NULL : info.opt("assoc_type_id"));
                    record.put("assoc_data_id", info.isNull("assoc_data_id") ? JSONObject.NULL : info.opt("assoc_data_id"));
                    record.put("create_date", info.optString("earned_date", ""));
                    records.put(record);
                }
            }

            // 6. Upload
            callback.onProgress("Uploading " + records.length() + " records…");
            JSONObject result = badgesApi.sync(records, garminUsername);
            callback.onComplete(records.length(), result);

        } catch (IOException e) {
            String msg = e.getMessage();
            if ("INVALID_API_KEY".equals(msg)) {
                callback.onError("Invalid API key. Check your key from the dashboard.");
            } else if ("VALIDATION_ERROR".equals(msg)) {
                callback.onError("Validation error — check your API key and try again.");
            } else {
                callback.onError(msg != null ? msg : "Network error.");
            }
        } catch (Exception e) {
            callback.onError(e.getMessage() != null ? e.getMessage() : "An unexpected error occurred.");
        }
    }

    private JSONArray parseArrayOrObject(String raw, String listKey) throws JSONException {
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            JSONObject obj = new JSONObject(raw);
            JSONArray arr = obj.optJSONArray(listKey);
            return arr != null ? arr : new JSONArray();
        }
    }

    private JSONArray parseEarnsResponse(String raw) throws JSONException {
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            JSONObject obj = new JSONObject(raw);
            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                Object val = obj.get(it.next());
                if (val instanceof JSONArray && ((JSONArray) val).length() > 0) {
                    return (JSONArray) val;
                }
            }
            return new JSONArray();
        }
    }

    private JSONArray concat(JSONArray a, JSONArray b) throws JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < a.length(); i++) result.put(a.get(i));
        for (int i = 0; i < b.length(); i++) result.put(b.get(i));
        return result;
    }

    private JSONArray deduplicateNumberedSeries(JSONArray badges, Map<Integer, String> nameMap) throws JSONException {
        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < badges.length(); i++) {
            JSONObject b = badges.getJSONObject(i);
            int id = b.optInt("badgeId", 0);
            String name = nameMap.containsKey(id) ? nameMap.get(id)
                : b.optString("badgeName", b.optString("badgeTitle", b.optString("name", ""))).trim();
            String base = name.replaceAll("\\s+\\d+$", "").trim();
            groups.computeIfAbsent(base, k -> new ArrayList<>()).add(b);
        }

        JSONArray result = new JSONArray();
        for (List<JSONObject> members : groups.values()) {
            JSONObject best = members.get(0);
            int bestNum = trailingNumber(best, nameMap);
            for (int i = 1; i < members.size(); i++) {
                int n = trailingNumber(members.get(i), nameMap);
                if (n < bestNum) { best = members.get(i); bestNum = n; }
            }
            result.put(best);
        }
        return result;
    }

    private static final Pattern TRAILING_NUM = Pattern.compile("\\s+(\\d+)$");

    private int trailingNumber(JSONObject b, Map<Integer, String> nameMap) {
        int id = b.optInt("badgeId", 0);
        String name = nameMap.containsKey(id) ? nameMap.get(id)
            : b.optString("badgeName", b.optString("badgeTitle", b.optString("name", ""))).trim();
        Matcher m = TRAILING_NUM.matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }
}
