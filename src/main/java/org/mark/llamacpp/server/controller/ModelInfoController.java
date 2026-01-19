package org.mark.llamacpp.server.controller;

import java.io.File;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFMetaDataReader;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.ConfigManager;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.ChatTemplateFileTool;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;


/**
 * 	模型信息相关的控制器s
 */
public class ModelInfoController implements BaseController {
	
	private static final Logger logger = LoggerFactory.getLogger(ModelInfoController.class);
	
	
	
	public ModelInfoController() {
		
	}
	
	
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 设置模型的别名
		if (uri.startsWith("/api/models/alias/set")) {
			this.handleSetModelAliasRequest(ctx, request);
			return true;
		}
		// 获取偏好模型的API
		if (uri.startsWith("/api/models/favourite")) {
			this.handleModelFavouriteRequest(ctx, request);
			return true;
		}
		// 查询指定模型启动参数的API
		if (uri.startsWith("/api/models/config/get")) {
			this.handleModelConfigRequest(ctx, request);
			return true;
		}
		// 用于更新启动参数的API
		if (uri.startsWith("/api/models/config/set")) {
			this.handleModelConfigSetRequest(ctx, request);
			return true;
		}
		// 获取指定模型详情的API
		if (uri.startsWith("/api/models/details")) {
			this.handleModelDetailsRequest(ctx, request);
			return true;
		}
		//============================聊天模板相关============================
		// 
		if (uri.startsWith("/api/model/template/get")) {
			this.handleModelTemplateGetRequest(ctx, request);
			return true;
		}
		
		
		if (uri.startsWith("/api/model/template/set")) {
			this.handleModelTemplateSetRequest(ctx, request);
			return true;
		}

		if (uri.startsWith("/api/model/template/delete")) {
			this.handleModelTemplateDeleteRequest(ctx, request);
			return true;
		}

		if (uri.startsWith("/api/model/template/default")) {
			this.handleModelTemplateDefaultRequest(ctx, request);
			return true;
		}
		//============================运行时信息============================
		// 查询对应模型的/solts的API
		if (uri.startsWith("/api/models/slots/get")) {
			this.handleModelSlotsGet(ctx, request);
			return true;
		}
		// 对应URL-POST：/slots/{solt_id}?action=save
		if (uri.startsWith("/api/models/slots/save")) {
			this.handleModelSlotsSave(ctx, request);
			return true;
		}
		// 对应URL-POST：/slots/{slot_id}?action=load
		if (uri.startsWith("/api/models/slots/load")) {
			this.handleModelSlotsLoad(ctx, request);
			return true;
		}
		
		return false;
	}
	
	
	/**
	 * 修改别名。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleSetModelAliasRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = JsonUtil.fromJson(content, JsonObject.class);
			if (json == null || !json.has("modelId") || !json.has("alias")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的参数: modelId 或 alias"));
				return;
			}
			String modelId = json.get("modelId").getAsString();
			String alias = json.get("alias").getAsString();
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("modelId不能为空"));
				return;
			}
			if (alias == null)
				alias = "";
			alias = alias.trim();
			// 更新配置文件
			ConfigManager configManager = ConfigManager.getInstance();
			boolean ok = configManager.saveModelAlias(modelId, alias);
			// 更新内存模型
			LlamaServerManager manager = LlamaServerManager.getInstance();
			GGUFModel model = manager.findModelById(modelId);
			if (model != null) {
				model.setAlias(alias);
			}
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("alias", alias);
			data.put("saved", ok);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("设置模型别名时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置模型别名失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 偏好模型的请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelFavouriteRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = JsonUtil.fromJson(content, JsonObject.class);
			if (json == null || !json.has("modelId")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的参数: modelId"));
				return;
			}
			String modelId = json.get("modelId").getAsString();
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("modelId不能为空"));
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			if (model == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到指定模型: " + modelId));
				return;
			}

			boolean next = !model.isFavourite();
			model.setFavourite(next);
			ConfigManager configManager = ConfigManager.getInstance();
			boolean saved = configManager.saveModelFavourite(modelId, next);

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("favourite", next);
			data.put("saved", saved);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("设置模型喜好时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置模型喜好失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 处理获取模型启动配置请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelConfigRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");		
		try {
			String query = request.uri();
			String modelId = null;
			Map<String, String> params = ParamTool.getQueryParam(query);
			modelId = params.get("modelId");

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			ConfigManager configManager = ConfigManager.getInstance();
			// 取出指定模型的启动参数
			Map<String, Map<String, Object>> allConfigs = configManager.loadAllLaunchConfigs();
			Map<String, Object> launchConfig = allConfigs.get(modelId);
			if (launchConfig == null) {
				launchConfig = new HashMap<>();
			}
			// 
			Map<String, Object> data = new HashMap<>();
			data.put(modelId, launchConfig);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取模型启动配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型启动配置失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 设置模型的启动参数
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelConfigSetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			ConfigManager configManager = ConfigManager.getInstance();
			JsonElement root = JsonUtil.fromJson(content, JsonElement.class);
			if (root == null || !root.isJsonObject()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体必须为JSON对象"));
				return;
			}
			JsonObject obj = root.getAsJsonObject();
			Type mapType = new TypeToken<Map<String, Object>>() {}.getType();

			Map<String, Object> savedData = new HashMap<>();

			if (obj.has("modelId")) {
				String modelId = obj.get("modelId").getAsString();
				if (modelId == null || modelId.trim().isEmpty()) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
					return;
				}
				JsonElement cfgEl = obj.has("config") ? obj.get("config") : obj;
				Map<String, Object> cfgMap = JsonUtil.fromJson(cfgEl, mapType);
				if (cfgMap == null) cfgMap = new HashMap<>();
				cfgMap.remove("modelId");
				cfgMap.remove("config");
				if (cfgMap.containsKey("chatTemplate")) {
					Object v = cfgMap.get("chatTemplate");
					String s = v == null ? "" : String.valueOf(v);
					if (s.trim().isEmpty()) {
						ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
					} else {
						ChatTemplateFileTool.writeChatTemplateToCacheFile(modelId, s);
					}
					cfgMap.remove("chatTemplate");
				}
				boolean saved = configManager.saveLaunchConfig(modelId, cfgMap);
				if (!saved) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存模型启动配置失败"));
					return;
				}
				savedData.put(modelId, cfgMap);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(savedData));
				return;
			}

			for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
				String modelId = e.getKey();
				if (modelId == null || modelId.trim().isEmpty()) continue;
				JsonElement cfgEl = e.getValue();
				if (cfgEl == null || cfgEl.isJsonNull()) continue;
				if (!cfgEl.isJsonObject()) continue;
				Map<String, Object> cfgMap = JsonUtil.fromJson(cfgEl, mapType);
				if (cfgMap == null) cfgMap = new HashMap<>();
				if (cfgMap.containsKey("chatTemplate")) {
					Object v = cfgMap.get("chatTemplate");
					String s = v == null ? "" : String.valueOf(v);
					if (s.trim().isEmpty()) {
						ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
					} else {
						ChatTemplateFileTool.writeChatTemplateToCacheFile(modelId, s);
					}
					cfgMap.remove("chatTemplate");
				}
				boolean saved = configManager.saveLaunchConfig(modelId, cfgMap);
				if (!saved) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存模型启动配置失败"));
					return;
				}
				savedData.put(modelId, cfgMap);
			}

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(savedData));
		} catch (Exception e) {
			logger.error("设置模型启动配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置模型启动配置失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 处理器模型详情的请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelDetailsRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		
		try {
			String query = request.uri();
			String modelId = null;
			Map<String, String> params = ParamTool.getQueryParam(query);
			modelId = params.get("modelId");
			
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			if (model == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到指定模型: " + modelId));
				return;
			}
			Map<String, Object> metadata = new HashMap<>();
			GGUFMetaData primary = model.getPrimaryModel();
			if (primary != null) {
				Map<String, Object> m = GGUFMetaDataReader.read(new File(primary.getFilePath()));
				if (m != null) {
					m.remove("tokenizer.ggml.merges");
					//m.remove("tokenizer.chat_template");
					m.remove("tokenizer.ggml.token_type");
					metadata.putAll(m);
				}
			}
			GGUFMetaData mmproj = model.getMmproj();
			if (mmproj != null) {
				Map<String, Object> m2 = GGUFMetaDataReader.read(new File(mmproj.getFilePath()));
				if (m2 != null) {
					for (Map.Entry<String, Object> e : m2.entrySet()) {
						metadata.put("mmproj." + e.getKey(), e.getValue());
					}
				}
			}
			boolean isLoaded = manager.getLoadedProcesses().containsKey(modelId);
			String startCmd = isLoaded ? manager.getModelStartCmd(modelId) : null;
			Integer port = manager.getModelPort(modelId);
			Map<String, Object> modelMap = new HashMap<>();
			String alias = model.getAlias();
			modelMap.put("name", alias != null && !alias.isEmpty() ? alias : modelId);
			modelMap.put("path", model.getPath());
			modelMap.put("size", model.getSize());
			modelMap.put("metadata", metadata);
			modelMap.put("isLoaded", isLoaded);
			if (startCmd != null && !startCmd.isEmpty()) {
				modelMap.put("startCmd", startCmd);
			}
			if (port != null) {
				modelMap.put("port", port);
			}
			Map<String, Object> response = new HashMap<>();
			response.put("model", modelMap);
			response.put("success", true);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("获取模型详情时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型详情失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	请求指定模型的模板
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelTemplateGetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			String chatTemplate = ChatTemplateFileTool.readChatTemplateFromCacheFile(modelId);
			String filePath = ChatTemplateFileTool.getChatTemplateCacheFilePathIfExists(modelId);
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("exists", filePath != null && !filePath.isEmpty());
			if (filePath != null && !filePath.isEmpty()) {
				data.put("filePath", filePath);
			}
			data.put("chatTemplate", chatTemplate);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取模型聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型聊天模板失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	设置指定模型的自定义模板
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelTemplateSetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			String chatTemplate = JsonUtil.getJsonString(obj, "chatTemplate", null);
			if (chatTemplate == null) chatTemplate = JsonUtil.getJsonString(obj, "template", null);
			if (chatTemplate == null) chatTemplate = JsonUtil.getJsonString(obj, "content", null);
			if (chatTemplate == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的chatTemplate参数"));
				return;
			}

			boolean deleted = false;
			String filePath = null;
			if (chatTemplate.trim().isEmpty()) {
				deleted = ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
			} else {
				filePath = ChatTemplateFileTool.writeChatTemplateToCacheFile(modelId, chatTemplate);
			}

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("deleted", deleted);
			if (filePath != null && !filePath.isEmpty()) {
				data.put("filePath", filePath);
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("设置模型聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置模型聊天模板失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	删除指定模型的自定义模板
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelTemplateDeleteRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}

			boolean existed = ChatTemplateFileTool.getChatTemplateCacheFilePathIfExists(modelId) != null;
			boolean deleted = ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("existed", existed);
			data.put("deleted", deleted);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("删除模型聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("删除模型聊天模板失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelTemplateDefaultRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			if (model == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到指定模型: " + modelId));
				return;
			}

			boolean exists = false;
			String chatTemplate = "";
			GGUFMetaData primary = model.getPrimaryModel();
			if (primary != null) {
				Map<String, Object> m = GGUFMetaDataReader.read(new File(primary.getFilePath()));
				if (m != null) {
					Object tpl = m.get("tokenizer.chat_template");
					if (tpl != null) {
						exists = true;
						chatTemplate = String.valueOf(tpl);
					}
				}
			}

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("exists", exists);
			data.put("chatTemplate", chatTemplate);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取模型默认聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型默认聊天模板失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 获取指定模型的slots信息
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelSlotsGet(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			String query = request.uri();
			String modelId = null;
			Map<String, String> params = ParamTool.getQueryParam(query);
			modelId = params.get("modelId");
			
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			// 调别的实现然后响应
			ApiResponse response = LlamaServerManager.getInstance().handleModelSlotsGet(modelId);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("获取模型slots信息时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型slots信息失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 保存指定模型指定slot的缓存
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelSlotsSave(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = JsonUtil.fromJson(content, JsonObject.class);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}
			String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
			Integer slotId = null;
			if (json.has("slotId")) {
				slotId = json.get("slotId").getAsInt();
			}
			String fileName = modelId + "_" + slotId + ".bin";
			ApiResponse response = LlamaServerManager.getInstance().handleModelSlotsSave(modelId, slotId.intValue(),
					fileName);
			// 响应消息。
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("保存模型slots缓存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存模型slots缓存失败: " + e.getMessage()));
		}
	}

	/**
	 * 加载指定模型指定slot的缓存
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelSlotsLoad(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = JsonUtil.fromJson(content, JsonObject.class);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}
			// 解析请求
			String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
			Integer slotId = null;
			if (json.has("slotId")) {
				slotId = json.get("slotId").getAsInt();
			}
			String fileName = modelId + "_" + slotId.intValue() + ".bin";
			ApiResponse response = LlamaServerManager.getInstance().handleModelSlotsLoad(modelId, slotId.intValue(),
					fileName);
			// 响应消息。
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("加载模型slots缓存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("加载模型slots缓存失败: " + e.getMessage()));
		}
	}
}
