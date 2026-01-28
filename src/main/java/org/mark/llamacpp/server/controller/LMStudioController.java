package org.mark.llamacpp.server.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

public class LMStudioController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(LMStudioController.class);

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		if (uri.startsWith("/api/v0/models")) {
			this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

			try {
				LlamaServerManager manager = LlamaServerManager.getInstance();
				Map<String, LlamaCppProcess> loadedProcesses = manager.getLoadedProcesses();
				List<GGUFModel> allModels = manager.listModel();
				List<Map<String, Object>> data = new ArrayList<>();

				for (Map.Entry<String, LlamaCppProcess> entry : loadedProcesses.entrySet()) {
					String modelId = entry.getKey();
					GGUFModel modelInfo = findModelInfo(allModels, modelId);
					Map<String, Object> modelData = new HashMap<>();
					modelData.put("id", modelId);
					modelData.put("object", "model");

					String modelType = "llm";
					String architecture = null;
					Integer contextLength = null;
					String quantization = null;

					if (modelInfo != null) {
						GGUFMetaData primaryModel = modelInfo.getPrimaryModel();
						if (primaryModel != null) {
							architecture = primaryModel.getStringValue("general.architecture");
							contextLength = primaryModel.getIntValue(architecture + ".context_length");
							quantization = guessQuantization(primaryModel.getFileName());
						}
						if (quantization == null) {
							quantization = guessQuantization(modelInfo.getName());
						}
						if (quantization == null) {
							quantization = guessQuantization(modelInfo.getModelId());
						}
						modelType = resolveModelType(architecture, modelInfo.getMmproj() != null);
					}

					modelData.put("type", modelType);
					if (architecture != null) {
						modelData.put("arch", architecture);
					}
					modelData.put("publisher", "GGUF");
					modelData.put("compatibility_type", "gguf");
					if (quantization != null) {
						modelData.put("quantization", quantization);
					}
					modelData.put("state", "loaded");
					if (contextLength != null) {
						modelData.put("max_context_length", contextLength);
					}

					data.add(modelData);
				}

				Map<String, Object> response = new HashMap<>();
				response.put("data", data);
				response.put("object", "list");
				LlamaServer.sendJsonResponse(ctx, response);
			} catch (Exception e) {
				logger.info("获取模型列表时发生错误", e);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型列表时发生错误: " + e.getMessage()));
			}
			return true;
		}

		return false;
	}

	private GGUFModel findModelInfo(List<GGUFModel> allModels, String modelId) {
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

	private String resolveModelType(String architecture, boolean multimodal) {
		if (multimodal) {
			return "vlm";
		}
		if (architecture == null || architecture.isEmpty()) {
			return "llm";
		}
		String arch = architecture.toLowerCase(Locale.ROOT);
		if (arch.contains("embed") || arch.contains("embedding") || arch.contains("bert")) {
			return "embeddings";
		}
		return "llm";
	}

	private String guessQuantization(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		String upper = text.toUpperCase(Locale.ROOT);
		String[] parts = upper.split("[^A-Z0-9_]+");
		for (String part : parts) {
			if (part.length() >= 2 && part.startsWith("Q") && Character.isDigit(part.charAt(1))) {
				return part;
			}
			if (part.length() >= 3 && part.startsWith("IQ") && Character.isDigit(part.charAt(2))) {
				return part;
			}
		}
		return null;
	}

}
