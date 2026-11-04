package com.puter.unofficial.models;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.net.URI;
import java.util.Objects;

/**
 * Model representing an organic web image extracted during Auto-Mode scraping.
 * Holds image source coordinates, webpage origin attribution, and metadata.
 * Supports polymorphic JSON deserialization across both Kiwi scrapers and LLM responses.
 */
public class ScrapedImageItem implements Serializable {
    private static final long serialVersionUID = 1L;

    private String imageUrl;
    private String title;
    private String sourceUrl;
    private String sourceDomain;

    public ScrapedImageItem() {
        this.imageUrl = "";
        this.title = "";
        this.sourceUrl = "";
        this.sourceDomain = "";
    }

    public ScrapedImageItem(String imageUrl, String title, String sourceUrl, String sourceDomain) {
        this.imageUrl = imageUrl != null ? imageUrl.trim() : "";
        this.title = title != null ? title.trim() : "";
        this.sourceUrl = sourceUrl != null ? sourceUrl.trim() : "";
        this.sourceDomain = (sourceDomain != null && !sourceDomain.trim().isEmpty()) 
                ? sourceDomain.trim() 
                : extractDomain(this.sourceUrl);
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl != null ? imageUrl.trim() : "";
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title != null ? title.trim() : "";
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl != null ? sourceUrl.trim() : "";
        if (this.sourceDomain == null || this.sourceDomain.isEmpty()) {
            this.sourceDomain = extractDomain(this.sourceUrl);
        }
    }

    public String getSourceDomain() {
        if (sourceDomain == null || sourceDomain.isEmpty()) {
            return extractDomain(sourceUrl);
        }
        return sourceDomain;
    }

    public void setSourceDomain(String sourceDomain) {
        this.sourceDomain = sourceDomain != null ? sourceDomain.trim() : "";
    }

    public boolean isValid() {
        return imageUrl != null 
                && !imageUrl.trim().isEmpty() 
                && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"));
    }

    /**
     * Converts model to JSONObject for clean session serialization.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("imageUrl", imageUrl != null ? imageUrl : "");
            json.put("title", title != null ? title : "");
            json.put("sourceUrl", sourceUrl != null ? sourceUrl : "");
            json.put("sourceDomain", getSourceDomain());
        } catch (JSONException ignored) {
        }
        return json;
    }

    /**
     * Deserializes JSONObject into a ScrapedImageItem instance with polymorphic key support.
     * Accurately parses schema variants ("url" vs "imageUrl", "description" vs "title").
     */
    public static ScrapedImageItem fromJson(JSONObject json) {
        if (json == null) return null;

        // Image URL: Accept imageUrl, url, src, or image
        String img = json.optString("imageUrl", "");
        if (img.isEmpty()) img = json.optString("url", "");
        if (img.isEmpty()) img = json.optString("src", "");
        if (img.isEmpty()) img = json.optString("image", "");

        // Caption / Title: Accept title, description, caption, or alt
        String ttl = json.optString("title", "");
        if (ttl.isEmpty()) ttl = json.optString("description", "");
        if (ttl.isEmpty()) ttl = json.optString("caption", "");
        if (ttl.isEmpty()) ttl = json.optString("alt", "");

        // Source Webpage URL: Accept sourceUrl, source, pageUrl, or link
        String src = json.optString("sourceUrl", "");
        if (src.isEmpty()) src = json.optString("source", "");
        if (src.isEmpty()) src = json.optString("pageUrl", "");
        if (src.isEmpty()) src = json.optString("link", "");

        // Source Domain: Accept sourceDomain or domain
        String dom = json.optString("sourceDomain", "");
        if (dom.isEmpty()) dom = json.optString("domain", "");

        return new ScrapedImageItem(img, ttl, src, dom);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScrapedImageItem that = (ScrapedImageItem) o;
        return Objects.equals(imageUrl, that.imageUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(imageUrl);
    }

    /**
     * Fallback domain parser to extract clean hostnames from URLs.
     */
    private static String extractDomain(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return "Web Source";
        }
        try {
            URI uri = new URI(urlString.trim());
            String domain = uri.getHost();
            if (domain != null) {
                return domain.startsWith("www.") ? domain.substring(4) : domain;
            }
        } catch (Exception ignored) {
        }
        return "Web Source";
    }
}