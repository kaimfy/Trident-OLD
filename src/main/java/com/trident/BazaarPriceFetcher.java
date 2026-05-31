package com.trident;

import com.google.gson.stream.JsonReader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class BazaarPriceFetcher {
    private static final String TARGET_PRODUCT = "ENCHANTED_SEA_LUMIES"; // Correct Hypixel item ID
    private static double seaLumiesPrice = 0;
    private static long lastUpdateTime = 0;
    private static final long CACHE_TIME_MS = 300000; // 5 minutes
    private static boolean isFetching = false;
    private static String lastStatus = "Not fetched yet";

    public static void fetchPriceAsync() {
        if (isFetching) {
            debugLog("Fetch already in progress - skipping");
            return;
        }

        isFetching = true;
        lastStatus = "Fetching Sea Lumies price...";
        debugLog("Starting targeted price fetch for " + TARGET_PRODUCT);

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://api.hypixel.net/v2/skyblock/bazaar");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);

                debugLog("Connecting to Hypixel API...");
                int responseCode = connection.getResponseCode();

                if (responseCode != 200) {
                    lastStatus = "API Error: " + responseCode;
                    throw new Exception("HTTP " + responseCode);
                }

                debugLog("Streaming response for " + TARGET_PRODUCT + "...");
                JsonReader reader = new JsonReader(new InputStreamReader(connection.getInputStream()));
                reader.setLenient(true);

                // Stream through JSON to find only our target product
                boolean foundProduct = false;
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.equals("products")) {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String productId = reader.nextName();
                            if (productId.equals(TARGET_PRODUCT)) {
                                foundProduct = true;
                                debugLog("Found " + TARGET_PRODUCT + " - extracting price");
                                reader.beginObject();
                                while (reader.hasNext()) {
                                    String propName = reader.nextName();
                                    if (propName.equals("quick_status")) {
                                        reader.beginObject();
                                        while (reader.hasNext()) {
                                            String statusName = reader.nextName();
                                            if (statusName.equals("sellPrice")) {
                                                seaLumiesPrice = reader.nextDouble();
                                                lastUpdateTime = System.currentTimeMillis();
                                                lastStatus = String.format("Sea Lumies: %.1f coins", seaLumiesPrice);
                                                debugLog("Success! Price: " + seaLumiesPrice);
                                                // Clean up reader
                                                while (reader.hasNext()) reader.skipValue();
                                                break;
                                            } else {
                                                reader.skipValue();
                                            }
                                        }
                                        reader.endObject();
                                    } else {
                                        reader.skipValue();
                                    }
                                }
                                reader.endObject();
                            } else {
                                reader.skipValue(); // Skip other products
                            }
                        }
                        reader.endObject();
                    } else {
                        reader.skipValue(); // Skip other top-level fields
                    }
                }
                reader.close();

                if (!foundProduct) {
                    lastStatus = TARGET_PRODUCT + " not found in bazaar";
                    throw new Exception("Product ID not found");
                }

            } catch (Exception e) {
                lastStatus = "Error: " + e.getMessage();
                debugLog("Fetch failed: " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (connection != null) connection.disconnect();
                isFetching = false;
                notifyInGame();
            }
        }).start();
    }

    private static void notifyInGame() {
        MinecraftClient.getInstance().execute(() -> {
            if (MinecraftClient.getInstance().player != null) {

            }
        });
    }

    public static double getCurrentPrice() {
        if (System.currentTimeMillis() - lastUpdateTime > CACHE_TIME_MS && !isFetching) {
            fetchPriceAsync();
        }
        return seaLumiesPrice;
    }

    private static void debugLog(String message) {
        System.out.println("[BazaarFetcher] " + message);
    }

    public static String getDebugInfo() {
        return String.format(
                "Sea Lumies: %.2f coins | Updated: %ds ago | Status: %s",
                seaLumiesPrice,
                (System.currentTimeMillis() - lastUpdateTime) / 1000,
                lastStatus
        );
    }
}