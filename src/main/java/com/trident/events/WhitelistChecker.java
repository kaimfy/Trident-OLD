package com.trident.events;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class WhitelistChecker {

    private final String apiUrl;
    private final Gson gson = new Gson();

    public WhitelistChecker(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    private String sendGet(String urlStr) throws Exception {
        URL url = new java.net.URI(urlStr).toURL();
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        StringBuilder content = new StringBuilder();
        String inputLine;

        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
        }

        in.close();
        con.disconnect();

        return content.toString();
    }

    public boolean isUserWhitelisted(String username) {
        try {
            String json = sendGet(apiUrl);

            // Parse JSON array into a String[]
            String[] whitelist = gson.fromJson(json, String[].class);

            for (String user : whitelist) {
                if (user.equalsIgnoreCase(username)) {
                    return true;
                }
            }

        } catch (JsonSyntaxException e) {
            System.err.println("Invalid JSON response from API: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }
}
