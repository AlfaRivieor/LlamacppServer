package org.mark.llamacpp.ollama;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFMetaDataReader;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.tools.ChatTemplateFileTool;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;



/**
 * 	兼容ollama API的服务。
 */
public class OllamaService {
	
	private static final Logger logger = LoggerFactory.getLogger(OllamaService.class);
	
	private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();
	private static final DateTimeFormatter OLLAMA_TIME_FORMATTER = new DateTimeFormatterBuilder()
			.appendPattern("yyyy-MM-dd'T'HH:mm:ss")
			.appendFraction(ChronoField.NANO_OF_SECOND, 0, 6, true)
			.appendOffset("+HH:MM", "+00:00")
			.toFormatter();
	private static final Pattern PARAM_SIZE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)([bBmMkK])");
	
	/**
	 * 	处理模型列表的请求。
	 * @param ctx
	 * @param request
	 */
	public void handleModelList(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.GET) {
			sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only GET method is supported");
			return;
		}
		
		LlamaServerManager manager = LlamaServerManager.getInstance();
		Map<String, LlamaCppProcess> loaded = manager.getLoadedProcesses();
		List<GGUFModel> allModels = manager.listModel();
		
		List<Map<String, Object>> models = new ArrayList<>();
		for (Map.Entry<String, LlamaCppProcess> entry : loaded.entrySet()) {
			String modelId = entry.getKey();
			GGUFModel model = findModelInfo(allModels, modelId);
			
			Map<String, Object> item = new HashMap<>();
			item.put("name", modelId);
			item.put("model", modelId);
			
			Instant modifiedAt = Instant.now();
			Long size = 0L;
			String family = null;
			String quant = null;
			
			if (model != null) {
				size = model.getSize();
				GGUFMetaData primary = model.getPrimaryModel();
				if (primary != null) {
					try {
						family = primary.getStringValue("general.architecture");
					} catch (Exception ignore) {
					}
					try {
						quant = primary.getQuantizationType();
					} catch (Exception ignore) {
					}
					try {
						String filePath = primary.getFilePath();
						if (filePath != null) {
							long lm = new File(filePath).lastModified();
							if (lm > 0) {
								modifiedAt = Instant.ofEpochMilli(lm);
							}
						}
					} catch (Exception ignore) {
					}
				} else {
					try {
						String p = model.getPath();
						if (p != null) {
							long lm = new File(p).lastModified();
							if (lm > 0) {
								modifiedAt = Instant.ofEpochMilli(lm);
							}
						}
					} catch (Exception ignore) {
					}
				}
			}
			
			item.put("modified_at", formatOllamaTime(modifiedAt));
			item.put("size", size == null ? 0L : size.longValue());
			item.put("digest", sha256Hex(modelId + ":" + item.get("size") + ":" + item.get("modified_at")));
			
			Map<String, Object> details = new HashMap<>();
			details.put("parent_model", "");
			details.put("format", "gguf");
			if (family != null && !family.isBlank()) {
				details.put("family", family);
				List<String> families = new ArrayList<>();
				families.add(family);
				details.put("families", families);
			}
			details.put("parameter_size", guessParameterSize(modelId, size == null ? 0L : size.longValue()));
			if (quant != null && !quant.isBlank()) {
				details.put("quantization_level", quant);
			}
			item.put("details", details);
			
			models.add(item);
		}
		
		Map<String, Object> resp = new HashMap<>();
		resp.put("models", models);
		sendOllamaJson(ctx, HttpResponseStatus.OK, resp);
	}

	private static String formatOllamaTime(Instant instant) {
		Instant safe = instant == null ? Instant.now() : instant;
		return OLLAMA_TIME_FORMATTER.format(OffsetDateTime.ofInstant(safe, DEFAULT_ZONE));
	}

	private static String guessParameterSize(String modelId, long sizeBytes) {
		String source = modelId == null ? "" : modelId.trim();
		if (source.contains(":")) {
			String[] parts = source.split(":", 2);
			if (parts.length == 2 && parts[1] != null && !parts[1].isBlank()) {
				source = parts[1].trim();
			}
		}

		Matcher m = PARAM_SIZE_PATTERN.matcher(source);
		if (m.find()) {
			try {
				double value = Double.parseDouble(m.group(1));
				String unit = m.group(2);
				if (unit != null && !unit.isBlank()) {
					char u = Character.toUpperCase(unit.charAt(0));
					double params;
					if (u == 'B') {
						params = value * 1_000_000_000d;
					} else if (u == 'M') {
						params = value * 1_000_000d;
					} else if (u == 'K') {
						params = value * 1_000d;
					} else {
						params = 0d;
					}

					if (params >= 1_000_000_000d) {
						return String.format(java.util.Locale.ROOT, "%.2fB", params / 1_000_000_000d);
					}
					if (params >= 1_000_000d) {
						return String.format(java.util.Locale.ROOT, "%.2fM", params / 1_000_000d);
					}
					if (params >= 1_000d) {
						return String.format(java.util.Locale.ROOT, "%.2fK", params / 1_000d);
					}
				}
			} catch (Exception ignore) {
			}
		}

		double mBytes = sizeBytes <= 0 ? 0d : (sizeBytes / 1024d / 1024d);
		return String.format(java.util.Locale.ROOT, "%.2fM", mBytes);
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 */
	public void handleShow(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST && request.method() != HttpMethod.GET) {
			sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST/GET method is supported");
			return;
		}

		String modelName = null;
		boolean verbose = false;

		if (request.method() == HttpMethod.POST) {
			String content = request.content().toString(StandardCharsets.UTF_8);
			
			System.err.println("收到请求：" + content);
			
			if (content == null || content.trim().isEmpty()) {
				sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body is empty");
				return;
			}
			JsonObject obj = null;
			try {
				obj = JsonUtil.fromJson(content, JsonObject.class);
			} catch (Exception e) {
				sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
				return;
			}
			if (obj == null) {
				sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
				return;
			}
			modelName = JsonUtil.getJsonString(obj, "name", null);
			if (modelName == null || modelName.isBlank()) {
				modelName = JsonUtil.getJsonString(obj, "model", null);
			}
			verbose = ParamTool.parseJsonBoolean(obj, "verbose", false);
		} else {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			modelName = params.get("name");
			if (modelName == null || modelName.isBlank()) {
				modelName = params.get("model");
			}
			String v = params.get("verbose");
			if (v != null) {
				String t = v.trim().toLowerCase();
				verbose = "true".equals(t) || "1".equals(t) || "yes".equals(t) || "on".equals(t);
			}
		}

		LlamaServerManager manager = LlamaServerManager.getInstance();
		if (modelName == null || modelName.isBlank()) {
			if (manager.getLoadedProcesses().size() == 1) {
				modelName = manager.getFirstModelName();
			} else {
				sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: name");
				return;
			}
		}

		List<GGUFModel> allModels = manager.listModel();
		GGUFModel model = manager.findModelById(modelName);
		if (model == null && modelName.contains(":")) {
			String base = modelName.substring(0, modelName.indexOf(':')).trim();
			if (!base.isEmpty()) {
				model = manager.findModelById(base);
			}
		}
		if (model == null && allModels != null) {
			for (GGUFModel m : allModels) {
				if (m == null) {
					continue;
				}
				String alias = m.getAlias();
				if (alias != null && alias.equals(modelName)) {
					model = m;
					break;
				}
			}
		}

		if (model == null) {
			sendOllamaError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
			return;
		}

		String modelId = model.getModelId();
		GGUFMetaData primary = model.getPrimaryModel();

		Map<String, Object> modelInfo = new HashMap<>();
		Instant modifiedAt = Instant.now();
		if (primary != null) {
			File primaryFile = new File(primary.getFilePath());
			if (primaryFile.exists() && primaryFile.isFile()) {
				try {
					long lm = primaryFile.lastModified();
					if (lm > 0) {
						modifiedAt = Instant.ofEpochMilli(lm);
					}
				} catch (Exception ignore) {
				}
			}

			Map<String, Object> m = GGUFMetaDataReader.read(primaryFile);
			if (m != null) {
				if (!verbose) {
					m.remove("tokenizer.ggml.tokens.size");
					m.put("tokenizer.ggml.merges", null);
					m.put("tokenizer.ggml.token_type", null);
					m.put("tokenizer.ggml.tokens", null);
				} else {
					m.remove("tokenizer.ggml.tokens.size");
					if (!m.containsKey("tokenizer.ggml.merges")) {
						m.put("tokenizer.ggml.merges", new ArrayList<>());
					}
					if (!m.containsKey("tokenizer.ggml.token_type")) {
						m.put("tokenizer.ggml.token_type", new ArrayList<>());
					}
					if (!m.containsKey("tokenizer.ggml.tokens")) {
						m.put("tokenizer.ggml.tokens", new ArrayList<>());
					}
				}
				modelInfo.putAll(m);
			}
		}

		String template = null;
		try {
			template = ChatTemplateFileTool.readChatTemplateFromCacheFile(modelId);
		} catch (Exception ignore) {
		}
		if (template == null || template.isBlank()) {
			Object tpl = modelInfo.get("tokenizer.chat_template");
			template = tpl == null ? "" : String.valueOf(tpl);
		}

		String family = null;
		String quant = null;
		if (primary != null) {
			try {
				family = primary.getStringValue("general.architecture");
			} catch (Exception ignore) {
			}
			try {
				quant = primary.getQuantizationType();
			} catch (Exception ignore) {
			}
		}
		if (family == null || family.isBlank()) {
			Object fam = modelInfo.get("general.architecture");
			if (fam != null) {
				family = String.valueOf(fam);
			}
		}

		Map<String, Object> details = new HashMap<>();
		details.put("parent_model", "");
		details.put("format", "gguf");
		if (family != null && !family.isBlank()) {
			details.put("family", family);
			List<String> families = new ArrayList<>();
			families.add(family);
			details.put("families", families);
		}
		details.put("parameter_size", guessParameterSize(modelId, model.getSize()));
		if (quant != null && !quant.isBlank()) {
			details.put("quantization_level", quant);
		}

		List<Map<String, Object>> tensors = new ArrayList<>();
		if (primary != null) {
			try {
				tensors = readGgufTensors(new File(primary.getFilePath()));
			} catch (Exception ignore) {
			}
		}

		String license = "";
		Object lic = modelInfo.get("general.license");
		if (lic != null) {
			license = String.valueOf(lic);
		}

		Map<String, Object> out = new HashMap<>();
		out.put("license", license);
		out.put("modelfile", "");
		out.put("parameters", "");
		out.put("template", template == null ? "" : template);
		out.put("details", details);
		out.put("model_info", modelInfo);
		out.put("tensors", tensors);
		List<String> caps = new ArrayList<>();
		caps.add("completion");
		caps.add("tools");
		out.put("capabilities", caps);
		out.put("modified_at", formatOllamaTime(modifiedAt));

		sendOllamaJson(ctx, HttpResponseStatus.OK, out);
	}
	
	
	/**
	 * 	处理chat请求。
	 * @param ctx
	 * @param request
	 */
	public void handleChat(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
			return;
		}
		
		String content = request.content().toString(StandardCharsets.UTF_8);
		if (content == null || content.trim().isEmpty()) {
			sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body is empty");
			return;
		}
		
		JsonObject ollamaReq = null;
		try {
			ollamaReq = JsonUtil.fromJson(content, JsonObject.class);
		} catch (Exception e) {
			sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
			return;
		}
		if (ollamaReq == null) {
			sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
			return;
		}
		
		LlamaServerManager manager = LlamaServerManager.getInstance();
		String modelName = JsonUtil.getJsonString(ollamaReq, "model", null);
		if (modelName == null || modelName.isBlank()) {
			if (manager.getLoadedProcesses().size() == 1) {
				modelName = manager.getFirstModelName();
			} else {
				sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: model");
				return;
			}
		}
		
		if (!manager.getLoadedProcesses().containsKey(modelName)) {
			sendOllamaError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
			return;
		}
		
		Integer port = manager.getModelPort(modelName);
		if (port == null) {
			sendOllamaError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Model port not found: " + modelName);
			return;
		}
		
		boolean isStream = false;
		try {
			if (ollamaReq.has("stream") && ollamaReq.get("stream").isJsonPrimitive()) {
				isStream = ollamaReq.get("stream").getAsBoolean();
			}
		} catch (Exception ignore) {
		}
		
		boolean hasTools = false;
		try {
			JsonElement tools = ollamaReq.get("tools");
			hasTools = tools != null && !tools.isJsonNull() && tools.isJsonArray() && tools.getAsJsonArray().size() > 0;
		} catch (Exception ignore) {
		}
		if (hasTools) {
			isStream = false;
		}
		
		JsonElement messages = ollamaReq.get("messages");
		if (messages == null || !messages.isJsonArray()) {
			sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: messages");
			return;
		}
		
		JsonObject openAiReq = new JsonObject();
		openAiReq.addProperty("model", modelName);
		openAiReq.add("messages", normalizeOllamaMessagesForOpenAI(messages.getAsJsonArray()));
		openAiReq.addProperty("stream", isStream);
		applyOllamaOptionsToOpenAI(openAiReq, ollamaReq.get("options"));
		applyOllamaToolsToOpenAI(openAiReq, ollamaReq);
		
		String createdAt = formatOllamaTime(Instant.now());
		String requestBody = JsonUtil.toJson(openAiReq);
		
		HttpURLConnection connection = null;
		try {
			String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port.intValue());
			URL url = URI.create(targetUrl).toURL();
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(36000 * 1000);
			connection.setReadTimeout(36000 * 1000);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			connection.setDoOutput(true);
			byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Length", String.valueOf(input.length));
			try (OutputStream os = connection.getOutputStream()) {
				os.write(input, 0, input.length);
			}
			
			int responseCode = connection.getResponseCode();
			if (isStream) {
				handleOllamaChatStreamResponse(ctx, connection, responseCode, modelName, createdAt);
			} else {
				handleOllamaChatNonStreamResponse(ctx, connection, responseCode, modelName, createdAt);
			}
		} catch (Exception e) {
			logger.info("处理Ollama chat请求时发生错误", e);
			sendOllamaError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}
	
	private static void applyOllamaOptionsToOpenAI(JsonObject openAiReq, JsonElement optionsEl) {
		if (openAiReq == null || optionsEl == null || optionsEl.isJsonNull() || !optionsEl.isJsonObject()) {
			return;
		}
		JsonObject options = optionsEl.getAsJsonObject();
		
		copyNumber(options, "temperature", openAiReq, "temperature");
		copyNumber(options, "top_p", openAiReq, "top_p");
		copyNumber(options, "top_k", openAiReq, "top_k");
		copyNumber(options, "repeat_penalty", openAiReq, "repeat_penalty");
		copyNumber(options, "frequency_penalty", openAiReq, "frequency_penalty");
		copyNumber(options, "presence_penalty", openAiReq, "presence_penalty");
		copyNumber(options, "seed", openAiReq, "seed");
		
		Integer numPredict = null;
		try {
			if (options.has("num_predict") && options.get("num_predict").isJsonPrimitive()) {
				numPredict = options.get("num_predict").getAsInt();
			}
		} catch (Exception ignore) {
		}
		if (numPredict != null) {
			openAiReq.addProperty("max_tokens", numPredict.intValue());
		}
		
		JsonElement stop = options.get("stop");
		if (stop != null && !stop.isJsonNull()) {
			if (stop.isJsonArray()) {
				openAiReq.add("stop", stop.deepCopy());
			} else if (stop.isJsonPrimitive()) {
				openAiReq.add("stop", stop.deepCopy());
			}
		}
	}
	
	private static JsonArray normalizeOllamaMessagesForOpenAI(JsonArray messages) {
		if (messages == null) {
			return new JsonArray();
		}
		JsonArray out = new JsonArray();
		Map<Integer, String> toolCallIndexToId = new HashMap<>();
		for (int i = 0; i < messages.size(); i++) {
			JsonElement el = messages.get(i);
			if (el == null || el.isJsonNull() || !el.isJsonObject()) {
				continue;
			}
			JsonObject msg = el.getAsJsonObject().deepCopy();
			normalizeOneMessageForOpenAI(msg, toolCallIndexToId);
			out.add(msg);
		}
		return out;
	}
	
	private static void normalizeOneMessageForOpenAI(JsonObject msg, Map<Integer, String> toolCallIndexToId) {
		if (msg == null) {
			return;
		}
		
		JsonElement contentEl = msg.get("content");
		if (contentEl != null && !contentEl.isJsonNull() && !contentEl.isJsonPrimitive()) {
			msg.addProperty("content", JsonUtil.jsonValueToString(contentEl));
		}
		
		JsonElement toolCallsEl = msg.get("tool_calls");
		if (toolCallsEl != null && !toolCallsEl.isJsonNull() && toolCallsEl.isJsonArray()) {
			JsonArray toolCalls = toolCallsEl.getAsJsonArray();
			normalizeToolCallsForOpenAI(toolCalls, toolCallIndexToId);
		}
		
		JsonObject fc = (msg.has("function_call") && msg.get("function_call").isJsonObject()) ? msg.getAsJsonObject("function_call") : null;
		if (fc != null && (toolCallsEl == null || toolCallsEl.isJsonNull())) {
			JsonArray arr = toolCallsFromFunctionCall(fc, null);
			if (arr != null) {
				msg.add("tool_calls", arr);
			}
			msg.remove("function_call");
		}
		
		String role = JsonUtil.getJsonString(msg, "role", null);
		if (role != null && role.equals("tool")) {
			JsonElement toolContent = msg.get("content");
			if (toolContent != null && !toolContent.isJsonNull() && !toolContent.isJsonPrimitive()) {
				msg.addProperty("content", JsonUtil.jsonValueToString(toolContent));
			}
		}
	}
	
	private static void normalizeToolCallsForOpenAI(JsonArray toolCalls, Map<Integer, String> toolCallIndexToId) {
		if (toolCalls == null) {
			return;
		}
		for (int i = 0; i < toolCalls.size(); i++) {
			JsonElement el = toolCalls.get(i);
			if (el == null || el.isJsonNull() || !el.isJsonObject()) {
				continue;
			}
			JsonObject tc = el.getAsJsonObject();
			
			JsonObject fn = (tc.has("function") && tc.get("function").isJsonObject()) ? tc.getAsJsonObject("function") : null;
			if (fn != null) {
				String type = JsonUtil.getJsonString(tc, "type", null);
				if (type == null || type.isBlank()) {
					tc.addProperty("type", "function");
				}
				JsonElement argsEl = fn.get("arguments");
				if (argsEl != null && !argsEl.isJsonNull() && !argsEl.isJsonPrimitive()) {
					fn.addProperty("arguments", argsEl.toString());
				} else if (argsEl != null && !argsEl.isJsonNull() && argsEl.isJsonPrimitive() && !argsEl.getAsJsonPrimitive().isString()) {
					fn.addProperty("arguments", JsonUtil.jsonValueToString(argsEl));
				} else if (argsEl == null || argsEl.isJsonNull()) {
					fn.addProperty("arguments", "");
				}
			}
		}
		ensureToolCallIdsInArray(toolCalls, toolCallIndexToId);
	}

	private static void applyOllamaToolsToOpenAI(JsonObject openAiReq, JsonObject ollamaReq) {
		if (openAiReq == null || ollamaReq == null) {
			return;
		}
		JsonElement tools = ollamaReq.get("tools");
		if (tools != null && !tools.isJsonNull() && tools.isJsonArray()) {
			openAiReq.add("tools", tools.deepCopy());
		}
		JsonElement toolChoice = ollamaReq.get("tool_choice");
		if (toolChoice != null && !toolChoice.isJsonNull()) {
			openAiReq.add("tool_choice", toolChoice.deepCopy());
		}
	}
	
	private static void copyNumber(JsonObject src, String srcKey, JsonObject dst, String dstKey) {
		if (src == null || dst == null || srcKey == null || dstKey == null || !src.has(srcKey)) {
			return;
		}
		try {
			if (!src.get(srcKey).isJsonPrimitive() || !src.get(srcKey).getAsJsonPrimitive().isNumber()) {
				return;
			}
			dst.add(srcKey.equals(dstKey) ? dstKey : dstKey, src.get(srcKey).deepCopy());
		} catch (Exception ignore) {
		}
	}
	
	private static void handleOllamaChatNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName, String createdAt) throws IOException {
		String responseBody = readBody(connection, responseCode >= 200 && responseCode < 300);
		if (!(responseCode >= 200 && responseCode < 300)) {
			String msg = extractOpenAIErrorMessage(responseBody);
			sendOllamaError(ctx, HttpResponseStatus.valueOf(responseCode), msg == null ? responseBody : msg);
			return;
		}
		
		JsonObject parsed = null;
		try {
			parsed = JsonUtil.fromJson(responseBody, JsonObject.class);
		} catch (Exception ignore) {
		}
		String content = null;
		String doneReason = "stop";
		JsonElement toolCalls = null;
		if (parsed != null) {
			try {
				JsonArray choices = parsed.getAsJsonArray("choices");
				if (choices != null && choices.size() > 0 && choices.get(0).isJsonObject()) {
					JsonObject c0 = choices.get(0).getAsJsonObject();
					JsonObject msg = c0.has("message") && c0.get("message").isJsonObject() ? c0.getAsJsonObject("message") : null;
					if (msg != null && msg.has("content")) {
						content = JsonUtil.jsonValueToString(msg.get("content"));
					}
					if (msg != null) {
						toolCalls = extractToolCallsFromOpenAIMessage(msg, new HashMap<>(), true);
					}
					JsonElement fr = c0.get("finish_reason");
					if (fr != null && !fr.isJsonNull()) {
						doneReason = JsonUtil.jsonValueToString(fr);
					}
				}
			} catch (Exception ignore) {
			}
		}
		if (content == null) {
			content = "";
		}
		
		Map<String, Object> out = new HashMap<>();
		out.put("model", modelName);
		out.put("created_at", createdAt);
		
		Map<String, Object> message = new HashMap<>();
		message.put("role", "assistant");
		message.put("content", content);
		if (toolCalls != null && !toolCalls.isJsonNull()) {
			JsonElement ollamaToolCalls = toOllamaToolCalls(toolCalls);
			if (ollamaToolCalls != null && !ollamaToolCalls.isJsonNull()) {
				message.put("tool_calls", ollamaToolCalls);
			}
		}
		out.put("message", message);
		
		out.put("done", Boolean.TRUE);
		out.put("done_reason", doneReason);
		
		sendOllamaJson(ctx, HttpResponseStatus.OK, out);
	}
	
	private static void handleOllamaChatStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName, String createdAt) throws IOException {
		if (!(responseCode >= 200 && responseCode < 300)) {
			String responseBody = readBody(connection, false);
			String msg = extractOpenAIErrorMessage(responseBody);
			sendOllamaError(ctx, HttpResponseStatus.valueOf(responseCode), msg == null ? responseBody : msg);
			return;
		}
		
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-ndjson; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		ctx.write(response);
		ctx.flush();
		
		String doneReason = "stop";
		Map<Integer, String> toolCallIndexToId = new HashMap<>();
		String functionCallId = null;
		String functionCallName = null;
		
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(
				responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream(),
				StandardCharsets.UTF_8
			)
		)) {
			String line;
			while ((line = br.readLine()) != null) {
				if (!ctx.channel().isActive()) {
					if (connection != null) {
						connection.disconnect();
					}
					break;
				}
				if (!line.startsWith("data: ")) {
					continue;
				}
				String data = line.substring(6);
				if ("[DONE]".equals(data)) {
					writeOllamaStreamChunk(ctx, modelName, createdAt, "", true, doneReason);
					break;
				}
				JsonObject chunk = tryParseObject(data);
				if (chunk == null) {
					continue;
				}
				
				String deltaContent = null;
				String finish = null;
				JsonElement deltaToolCalls = null;
				
				try {
					JsonArray choices = chunk.getAsJsonArray("choices");
					if (choices != null && choices.size() > 0 && choices.get(0).isJsonObject()) {
						JsonObject c0 = choices.get(0).getAsJsonObject();
						JsonObject delta = c0.has("delta") && c0.get("delta").isJsonObject() ? c0.getAsJsonObject("delta") : null;
						if (delta != null && delta.has("content")) {
							deltaContent = JsonUtil.jsonValueToString(delta.get("content"));
						}
						if (delta != null) {
							deltaToolCalls = extractToolCallsFromOpenAIMessage(delta, toolCallIndexToId, false);
							if (deltaToolCalls == null) {
								JsonObject fc = (delta.has("function_call") && delta.get("function_call").isJsonObject()) ? delta.getAsJsonObject("function_call") : null;
								if (fc != null) {
									String fcName = JsonUtil.getJsonString(fc, "name", null);
									if (fcName != null && !fcName.isBlank()) {
										functionCallName = fcName;
									}
									if (functionCallId == null) {
										functionCallId = "call_" + UUID.randomUUID().toString().replace("-", "");
									}
									JsonObject enriched = fc.deepCopy();
									if ((JsonUtil.getJsonString(enriched, "name", null) == null || JsonUtil.getJsonString(enriched, "name", null).isBlank())
											&& functionCallName != null && !functionCallName.isBlank()) {
										enriched.addProperty("name", functionCallName);
									}
									deltaToolCalls = toolCallsFromFunctionCall(enriched, functionCallId);
								}
							}
						}
						JsonElement fr = c0.get("finish_reason");
						if (fr != null && !fr.isJsonNull()) {
							finish = JsonUtil.jsonValueToString(fr);
						}
					}
				} catch (Exception ignore) {
				}
				
				if (finish != null && !finish.isBlank()) {
					doneReason = finish;
				}
				boolean hasContent = deltaContent != null && !deltaContent.isEmpty();
				boolean hasToolCalls = deltaToolCalls != null && !deltaToolCalls.isJsonNull();
				if (hasContent || hasToolCalls) {
					writeOllamaStreamChunk(ctx, modelName, createdAt, hasContent ? deltaContent : "", deltaToolCalls, false, null);
				}
			}
		} catch (Exception e) {
			logger.info("处理Ollama chat流式响应时发生错误", e);
			throw e;
		}
		
		LastHttpContent last = LastHttpContent.EMPTY_LAST_CONTENT;
		ctx.writeAndFlush(last).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	private static void writeOllamaStreamChunk(ChannelHandlerContext ctx, String modelName, String createdAt, String content, boolean done, String doneReason) {
		writeOllamaStreamChunk(ctx, modelName, createdAt, content, null, done, doneReason);
	}
	
	private static void writeOllamaStreamChunk(ChannelHandlerContext ctx, String modelName, String createdAt, String content, JsonElement toolCalls, boolean done, String doneReason) {
		Map<String, Object> out = new HashMap<>();
		out.put("model", modelName);
		out.put("created_at", createdAt);
		
		Map<String, Object> message = new HashMap<>();
		message.put("role", "assistant");
		message.put("content", content == null ? "" : content);
		if (toolCalls != null && !toolCalls.isJsonNull()) {
			message.put("tool_calls", toolCalls);
		}
		out.put("message", message);
		
		out.put("done", Boolean.valueOf(done));
		if (done) {
			out.put("done_reason", doneReason == null || doneReason.isBlank() ? "stop" : doneReason);
		}
		
		String json = JsonUtil.toJson(out) + "\n";
		ByteBuf buf = ctx.alloc().buffer();
		buf.writeBytes(json.getBytes(StandardCharsets.UTF_8));
		HttpContent httpContent = new DefaultHttpContent(buf);
		ChannelFuture f = ctx.writeAndFlush(httpContent);
		f.addListener((ChannelFutureListener) future -> {
			if (!future.isSuccess()) {
				ctx.close();
			}
		});
	}
	
	private static JsonObject tryParseObject(String s) {
		try {
			if (s == null || s.trim().isEmpty()) {
				return null;
			}
			JsonElement el = JsonUtil.fromJson(s, JsonElement.class);
			return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}
	
	private static JsonElement extractToolCallsFromOpenAIMessage(JsonObject msg, Map<Integer, String> indexToId, boolean includeLegacyFunctionCall) {
		if (msg == null) {
			return null;
		}
		JsonElement tcs = msg.get("tool_calls");
		if (tcs != null && !tcs.isJsonNull() && tcs.isJsonArray()) {
			JsonArray copy = tcs.getAsJsonArray().deepCopy();
			ensureToolCallIdsInArray(copy, indexToId);
			return copy;
		}
		if (includeLegacyFunctionCall) {
			JsonObject fc = (msg.has("function_call") && msg.get("function_call").isJsonObject()) ? msg.getAsJsonObject("function_call") : null;
			if (fc != null) {
				return toolCallsFromFunctionCall(fc, null);
			}
		}
		return null;
	}
	
	private static JsonArray toolCallsFromFunctionCall(JsonObject functionCall, String id) {
		if (functionCall == null) {
			return null;
		}
		String name = JsonUtil.getJsonString(functionCall, "name", null);
		if (name == null || name.isBlank()) {
			return null;
		}
		if (id == null || id.isBlank()) {
			id = "call_" + UUID.randomUUID().toString().replace("-", "");
		}
		JsonElement argsEl = functionCall.get("arguments");
		String args = argsEl == null || argsEl.isJsonNull() ? "" : JsonUtil.jsonValueToString(argsEl);
		
		JsonObject fn = new JsonObject();
		fn.addProperty("name", name);
		fn.addProperty("arguments", args);
		
		JsonObject tc = new JsonObject();
		tc.addProperty("id", id);
		tc.addProperty("type", "function");
		tc.add("function", fn);
		
		JsonArray arr = new JsonArray();
		arr.add(tc);
		return arr;
	}
	
	private static JsonElement toOllamaToolCalls(JsonElement openAiToolCalls) {
		if (openAiToolCalls == null || openAiToolCalls.isJsonNull()) {
			return null;
		}
		if (openAiToolCalls.isJsonArray()) {
			JsonArray arr = openAiToolCalls.getAsJsonArray();
			if (arr.size() == 0) {
				return null;
			}
			JsonArray out = new JsonArray();
			for (int i = 0; i < arr.size(); i++) {
				JsonElement el = arr.get(i);
				if (el == null || el.isJsonNull() || !el.isJsonObject()) {
					continue;
				}
				JsonObject tc = el.getAsJsonObject();
				JsonObject fn = (tc.has("function") && tc.get("function").isJsonObject()) ? tc.getAsJsonObject("function") : null;
				if (fn == null) {
					continue;
				}
				String name = JsonUtil.getJsonString(fn, "name", null);
				if (name == null || name.isBlank()) {
					continue;
				}
				JsonElement argsEl = fn.get("arguments");
				JsonElement args = null;
				if (argsEl == null || argsEl.isJsonNull()) {
					args = new JsonObject();
				} else if (argsEl.isJsonPrimitive() && argsEl.getAsJsonPrimitive().isString()) {
					args = tryParseJson(argsEl.getAsString());
					if (args == null) {
						String s = argsEl.getAsString();
						args = (s == null || s.isBlank()) ? new JsonObject() : argsEl.deepCopy();
					}
				} else if (argsEl.isJsonObject() || argsEl.isJsonArray()) {
					args = argsEl.deepCopy();
				} else {
					args = argsEl.deepCopy();
				}
				
				JsonObject outFn = new JsonObject();
				outFn.addProperty("name", name);
				outFn.add("arguments", args == null ? new JsonObject() : args);
				
				JsonObject outTc = new JsonObject();
				outTc.add("function", outFn);
				out.add(outTc);
			}
			return out.size() == 0 ? null : out;
		}
		if (openAiToolCalls.isJsonObject()) {
			JsonObject obj = openAiToolCalls.getAsJsonObject();
			if (obj.has("function_call") && obj.get("function_call").isJsonObject()) {
				JsonArray arr = toolCallsFromFunctionCall(obj.getAsJsonObject("function_call"), null);
				return arr == null ? null : toOllamaToolCalls(arr);
			}
		}
		return null;
	}
	
	private static JsonElement tryParseJson(String s) {
		try {
			if (s == null || s.isBlank()) {
				return null;
			}
			return JsonUtil.fromJson(s, JsonElement.class);
		} catch (Exception e) {
			return null;
		}
	}
	
	private static boolean ensureToolCallIdsInArray(JsonArray arr, Map<Integer, String> indexToId) {
		if (arr == null) {
			return false;
		}
		boolean changed = false;
		for (int i = 0; i < arr.size(); i++) {
			JsonElement el = arr.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			JsonObject tc = el.getAsJsonObject();
			Integer idx = readToolCallIndex(tc, i);
			String existingId = JsonUtil.getJsonString(tc, "id", null);
			if (existingId == null || existingId.isBlank()) {
				String assigned = (indexToId == null || idx == null) ? null : indexToId.get(idx);
				if (assigned == null || assigned.isBlank()) {
					assigned = "call_" + UUID.randomUUID().toString().replace("-", "");
					if (indexToId != null && idx != null) {
						indexToId.put(idx, assigned);
					}
				}
				tc.addProperty("id", assigned);
				changed = true;
			} else if (indexToId != null && idx != null) {
				indexToId.putIfAbsent(idx, existingId);
			}
		}
		return changed;
	}
	
	private static Integer readToolCallIndex(JsonObject tc, int fallback) {
		if (tc == null) {
			return fallback;
		}
		JsonElement idxEl = tc.get("index");
		if (idxEl == null || idxEl.isJsonNull()) {
			return fallback;
		}
		try {
			if (idxEl.isJsonPrimitive() && idxEl.getAsJsonPrimitive().isNumber()) {
				return idxEl.getAsInt();
			}
			if (idxEl.isJsonPrimitive() && idxEl.getAsJsonPrimitive().isString()) {
				String s = idxEl.getAsString();
				if (s != null && !s.isBlank()) {
					return Integer.parseInt(s.trim());
				}
			}
		} catch (Exception ignore) {
		}
		return fallback;
	}
	
	private static String extractOpenAIErrorMessage(String responseBody) {
		if (responseBody == null || responseBody.isBlank()) {
			return null;
		}
		try {
			JsonObject parsed = JsonUtil.fromJson(responseBody, JsonObject.class);
			if (parsed == null) {
				return null;
			}
			JsonObject err = parsed.has("error") && parsed.get("error").isJsonObject() ? parsed.getAsJsonObject("error") : null;
			if (err == null) {
				return null;
			}
			String msg = JsonUtil.getJsonString(err, "message", null);
			return msg == null || msg.isBlank() ? null : msg.trim();
		} catch (Exception e) {
			return null;
		}
	}
	
	private static String readBody(HttpURLConnection connection, boolean successStream) throws IOException {
		if (connection == null) {
			return "";
		}
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(successStream ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8)
		)) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		}
	}
	
	private static void sendOllamaError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("error", message == null ? "" : message);
		sendOllamaJson(ctx, status == null ? HttpResponseStatus.INTERNAL_SERVER_ERROR : status, payload);
	}
	
	private static List<Map<String, Object>> readGgufTensors(File ggufFile) throws IOException {
		if (ggufFile == null || !ggufFile.exists() || !ggufFile.isFile()) {
			return new ArrayList<>();
		}
		try (RandomAccessFile raf = new RandomAccessFile(ggufFile, "r"); FileChannel ignore = raf.getChannel()) {
			byte[] magicBytes = new byte[4];
			raf.readFully(magicBytes);
			String magic = new String(magicBytes, StandardCharsets.US_ASCII);
			if (!"GGUF".equals(magic)) {
				return new ArrayList<>();
			}

			readI32Le(raf);
			long tensorCount = readU64Le(raf);
			long kvCount = readU64Le(raf);

			for (long i = 0; i < kvCount; i++) {
				readGgufString(raf);
				int type = readI32Le(raf);
				skipGgufValue(raf, type);
			}

			List<Map<String, Object>> tensors = new ArrayList<>((int) Math.min(Math.max(tensorCount, 0), 4096));
			for (long i = 0; i < tensorCount; i++) {
				String name = readGgufString(raf);
				int nDims = readI32Le(raf);
				List<Long> shape = new ArrayList<>(Math.max(nDims, 0));
				for (int d = 0; d < nDims; d++) {
					shape.add(Long.valueOf(readU64Le(raf)));
				}
				int tensorType = readI32Le(raf);
				readU64Le(raf);

				Map<String, Object> item = new HashMap<>();
				item.put("name", name);
				item.put("type", ggmlTypeName(tensorType));
				item.put("shape", shape);
				tensors.add(item);
			}

			return tensors;
		} catch (EOFException eof) {
			return new ArrayList<>();
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	private static String ggmlTypeName(int id) {
		return switch (id) {
		case 0 -> "F32";
		case 1 -> "F16";
		case 2 -> "Q4_0";
		case 3 -> "Q4_1";
		case 4 -> "Q4_2";
		case 5 -> "Q4_3";
		case 6 -> "Q5_0";
		case 7 -> "Q5_1";
		case 8 -> "Q8_0";
		case 9 -> "Q8_1";
		case 10 -> "Q2_K";
		case 11 -> "Q3_K";
		case 12 -> "Q4_K";
		case 13 -> "Q5_K";
		case 14 -> "Q6_K";
		case 15 -> "Q8_K";
		case 16 -> "IQ2_XXS";
		case 17 -> "IQ2_XS";
		case 18 -> "IQ3_XXS";
		case 19 -> "IQ1_S";
		case 20 -> "IQ4_NL";
		case 21 -> "IQ3_S";
		case 22 -> "IQ2_S";
		case 23 -> "IQ4_XS";
		case 24 -> "I8";
		case 25 -> "I16";
		case 26 -> "I32";
		case 27 -> "I64";
		case 28 -> "F64";
		case 29 -> "IQ1_M";
		case 30 -> "BF16";
		default -> "UNKNOWN(" + id + ")";
		};
	}

	private static int readI32Le(RandomAccessFile raf) throws IOException {
		return Integer.reverseBytes(raf.readInt());
	}

	private static long readU64Le(RandomAccessFile raf) throws IOException {
		return Long.reverseBytes(raf.readLong());
	}

	private static String readGgufString(RandomAccessFile raf) throws IOException {
		long len = readU64Le(raf);
		if (len <= 0) {
			return "";
		}
		if (len > Integer.MAX_VALUE) {
			skipFully(raf, len);
			return "";
		}
		byte[] bytes = new byte[(int) len];
		raf.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void skipGgufValue(RandomAccessFile raf, int type) throws IOException {
		switch (type) {
		case 0, 1, 7 -> skipFully(raf, 1);
		case 2, 3 -> skipFully(raf, 2);
		case 4, 5, 6 -> skipFully(raf, 4);
		case 8 -> {
			long len = readU64Le(raf);
			if (len > 0) {
				skipFully(raf, len);
			}
		}
		case 9 -> {
			int elemType = readI32Le(raf);
			long len = readU64Le(raf);
			for (long i = 0; i < len; i++) {
				skipGgufValue(raf, elemType);
			}
		}
		case 10, 11, 12 -> skipFully(raf, 8);
		default -> {
		}
		}
	}

	private static void skipFully(RandomAccessFile raf, long n) throws IOException {
		if (n <= 0) {
			return;
		}
		long pos = raf.getFilePointer();
		raf.seek(pos + n);
	}

	/**
	 * 	发送JSON响应
	 * @param ctx
	 * @param status
	 * @param data
	 */
	private static void sendOllamaJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);
		
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
		//
		response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(content));
		response.headers().set("X-Powered-By", "Express");
		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.content().writeBytes(content);
		
		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	private static GGUFModel findModelInfo(List<GGUFModel> allModels, String modelId) {
		if (allModels == null || modelId == null) {
			return null;
		}
		for (GGUFModel model : allModels) {
			if (modelId.equals(model.getModelId())) {
				return model;
			}
		}
		return null;
	}
	
	private static String sha256Hex(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit((b & 0xF), 16));
			}
			return sb.toString();
		} catch (Exception e) {
			return UUID.randomUUID().toString().replace("-", "");
		}
	}

}
