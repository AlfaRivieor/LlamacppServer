package org.mark.llamacpp.server.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.mark.llamacpp.server.tools.JsonUtil;


/**
 * 	智谱的联网搜索功能。
 */
public class ZhipuWebSearchService {

	private static final String ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/web_search";

	private volatile String token;

	public ZhipuWebSearchService() {
		this(loadTokenFromZhipuSearchConfig());
	}

	public ZhipuWebSearchService(String token) {
		this.token = token == null ? "" : token.trim();
	}
	
	
	/**
	 * 	调用智谱的API发起搜索。
	 * @param query
	 * @param count
	 * @return
	 * @throws IOException
	 */
	public JsonObject search(String query, int count) throws IOException {
		if (query == null || query.trim().isEmpty()) {
			throw new IllegalArgumentException("search_query is empty");
		}
		String loaded = loadTokenFromZhipuSearchConfig();
		if (loaded != null && !loaded.isBlank()) {
			String trimmed = loaded.trim();
			if (!trimmed.equals(this.token)) {
				this.token = trimmed;
			}
		}
		if (this.token.isBlank()) {
			throw new IllegalStateException("Zhipu token is empty. Set config/zhipu_search.json apiKey.");
		}

		int finalCount = count <= 0 ? 10 : Math.min(count, 50);
		String requestId = UUID.randomUUID().toString().replace("-", "");

		JsonObject body = new JsonObject();
		body.addProperty("search_query", query.trim());
		//body.addProperty("search_engine", "search_std");
		body.addProperty("search_engine", "search_pro");
		body.addProperty("search_intent", false);
		body.addProperty("count", finalCount);
		body.addProperty("search_recency_filter", "noLimit");
		body.addProperty("content_size", "medium");
		body.addProperty("request_id", requestId);

		byte[] out = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);

		HttpURLConnection conn = null;
		try {
			URL url = URI.create(ENDPOINT).toURL();
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setConnectTimeout(15000);
			conn.setReadTimeout(60000);
			conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			conn.setRequestProperty("Authorization", "Bearer " + this.token);

			try (OutputStream os = conn.getOutputStream()) {
				os.write(out);
			}

			int code = conn.getResponseCode();
			String raw = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
			JsonElement parsed = null;
			try {
				parsed = JsonUtil.fromJson(raw, JsonElement.class);
			} catch (Exception ignore) {
			}
			if (code < 200 || code >= 300) {
				String msg = raw == null ? "" : raw;
				throw new IOException("Zhipu web_search failed, status=" + code + ", body=" + msg);
			}
			if (parsed == null || !parsed.isJsonObject()) {
				throw new IOException("Zhipu web_search invalid JSON response");
			}
			return parsed.getAsJsonObject();
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private static String readAll(java.io.InputStream in) throws IOException {
		if (in == null) {
			return "";
		}
		try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		}
	}

	private static String loadTokenFromZhipuSearchConfig() {
		try {
			Path configPath = Paths.get("config", "zhipu_search.json");
			if (!Files.exists(configPath)) {
				return "";
			}
			String json = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
			JsonObject root = JsonUtil.fromJson(json, JsonObject.class);
			if (root == null) {
				return "";
			}
			String t = null;
			if (root.has("apiKey") && root.get("apiKey") != null && !root.get("apiKey").isJsonNull()) {
				t = root.get("apiKey").getAsString();
			} else if (root.has("zhipu_search_apikey") && root.get("zhipu_search_apikey") != null
					&& !root.get("zhipu_search_apikey").isJsonNull()) {
				t = root.get("zhipu_search_apikey").getAsString();
			}
			if (t != null && !t.isBlank()) {
				return t.trim();
			}
			return "";
		} catch (Exception e) {
			return "";
		}
	}
}
