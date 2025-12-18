package org.mark.llamacpp.server.channel;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.server.service.CompletionService;

import com.google.gson.Gson;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * 	这是自用的创作服务的路由控制器。
 */
public class CompletionRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
	
	/**
	 * 	
	 */
	private static final Gson gson = new Gson();
	
	/**
	 * 	
	 */
	private CompletionService completionService = new CompletionService();
	
	
	
	public CompletionRouterHandler() {
		
	}
	
	
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
		String uri = msg.uri();
		if (uri == null) {
			sendError(ctx, HttpResponseStatus.BAD_REQUEST, "缺少URI");
			return;
		}
		
		if (uri.startsWith("/session")) {
			this.handleSessionApi(ctx, msg, uri);
			return;
		}
		ctx.fireChannelRead(msg.retain());
	}
	
	/**
	 * 	处理API请求。
	 * @param ctx
	 * @param msg
	 * @param uri
	 */
	private void handleSessionApi(ChannelHandlerContext ctx, FullHttpRequest msg, String uri) {
		try {
			String path = uri;
			String query = null;
			int qIdx = uri.indexOf('?');
			if (qIdx >= 0) {
				path = uri.substring(0, qIdx);
				query = uri.substring(qIdx + 1);
			}

			HttpMethod method = msg.method();

			if ("/session/list".equals(path) && method == HttpMethod.GET) {
				this.handleSessionList(ctx);
				return;
			}

			if ("/session/create".equals(path) && method == HttpMethod.POST) {
				String body = msg.content().toString(CharsetUtil.UTF_8);
				this.handleSessionCreate(ctx, body);
				return;
			}

			if ("/session/get".equals(path) && method == HttpMethod.GET) {
				String id = getQueryParam(query, "id");
				this.handleSessionGet(ctx, id);
				return;
			}

			if ("/session/save".equals(path) && method == HttpMethod.POST) {
				String id = getQueryParam(query, "id");
				String body = msg.content().toString(CharsetUtil.UTF_8);
				this.handleSessionSave(ctx, id, body);
				return;
			}

			if ("/session/switch".equals(path) && method == HttpMethod.POST) {
				String id = getQueryParam(query, "id");
				this.handleSessionSwitch(ctx, id);
				return;
			}

			if ("/session/delete".equals(path) && method == HttpMethod.DELETE) {
				String id = getQueryParam(query, "id");
				this.handleSessionDelete(ctx, id);
				return;
			}

			sendError(ctx, HttpResponseStatus.NOT_FOUND, "404 Not Found");
		} catch (Exception e) {
			sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage());
		}
	}
	
	/**
	 * 	列出全部的character，以JSON格式返回
	 * @param ctx
	 */
	private void handleSessionList(ChannelHandlerContext ctx) {
		//	TODO

		
	}
	
	/**
	 * 	创建一个新的character
	 * @param ctx
	 * @param body
	 */
	private void handleSessionCreate(ChannelHandlerContext ctx, String body) {
		// TODO
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param id
	 */
	private void handleSessionGet(ChannelHandlerContext ctx, String id) {
		// TODO
	}
	
	/**
	 * 	保存角色信息。
	 * @param ctx
	 * @param id
	 * @param body
	 */
	private void handleSessionSave(ChannelHandlerContext ctx, String id, String body) {
		// TODO
	}

	private void handleSessionSwitch(ChannelHandlerContext ctx, String id) {
		// TODO
	}

	private void handleSessionDelete(ChannelHandlerContext ctx, String id) {
		// TODO
	}

	/**
	 * 	
	 * @param query
	 * @param key
	 * @return
	 */
	private static String getQueryParam(String query, String key) {
		if (query == null || query.isEmpty() || key == null || key.isEmpty())
			return null;
		String[] parts = query.split("&");
		for (String p : parts) {
			int idx = p.indexOf('=');
			if (idx < 0)
				continue;
			String k = p.substring(0, idx);
			if (!key.equals(k))
				continue;
			String v = p.substring(idx + 1);
			try {
				return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
			} catch (Exception e) {
				return v;
			}
		}
		return null;
	}

	private static void sendJson(ChannelHandlerContext ctx, Object payload, HttpResponseStatus status) {
		String json = gson.toJson(payload);
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		FullHttpResponse resp = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1,
			status,
			Unpooled.wrappedBuffer(bytes)
		);
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("status", "error");
		payload.put("message", message);
		sendJson(ctx, payload, status);
	}
}
