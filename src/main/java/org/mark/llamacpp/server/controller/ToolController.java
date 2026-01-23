package org.mark.llamacpp.server.controller;

import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.ToolExecutionService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;



/**
 * 	
 */
public class ToolController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(ToolController.class);

	private static final ToolExecutionService toolExecutionService = new ToolExecutionService();
	
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (!uri.startsWith("/api/tools/execute")) {
			return false;
		}

		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return true;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体为空");
				return true;
			}

			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体解析失败");
				return true;
			}

			String toolName = JsonUtil.getJsonString(obj, "tool_name", null);
			if (toolName == null || toolName.trim().isEmpty()) {
				toolName = JsonUtil.getJsonString(obj, "name", null);
			}
			if (toolName == null || toolName.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少必需的tool_name参数");
				return true;
			}
			toolName = toolName.trim();

			String preparedQuery = JsonUtil.getJsonString(obj, "preparedQuery", "");
			if (preparedQuery == null) {
				preparedQuery = "";
			}

			String toolArguments = null;
			if (obj.has("arguments") && obj.get("arguments") != null && !obj.get("arguments").isJsonNull()) {
				JsonElement argsEl = obj.get("arguments");
				if (argsEl.isJsonPrimitive()) {
					toolArguments = argsEl.getAsString();
				} else {
					toolArguments = JsonUtil.toJson(argsEl);
				}
			} else {
				toolArguments = JsonUtil.getJsonString(obj, "tool_arguments", null);
			}

			String out = toolExecutionService.executeToText(toolName, toolArguments, preparedQuery);
			Map<String, Object> data = new HashMap<>();
			data.put("content", out == null ? "" : out);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			return true;
		} catch (Exception e) {
			logger.info("执行工具失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("执行工具失败: " + e.getMessage()));
			return true;
		}
	}
}
