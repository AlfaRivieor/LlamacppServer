package org.mark.llamacpp.server.channel;

import java.io.File;
import java.net.URL;
import java.net.URLDecoder;
import java.util.LinkedList;
import java.util.List;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.controller.BaseController;
import org.mark.llamacpp.server.controller.HuggingFaceController;
import org.mark.llamacpp.server.controller.LlamacppController;
import org.mark.llamacpp.server.controller.ModelActionController;
import org.mark.llamacpp.server.controller.ModelInfoController;
import org.mark.llamacpp.server.controller.ModelPathController;
import org.mark.llamacpp.server.controller.ParamController;
import org.mark.llamacpp.server.controller.SystemController;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 基本路由处理器。 实现本项目用到的API端点。
 */
public class BasicRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(BasicRouterHandler.class);

	
	private static final List<BaseController> pipeline = new LinkedList<>();
	
	
	static {
		pipeline.add(new HuggingFaceController());
		pipeline.add(new LlamacppController());
		pipeline.add(new ModelActionController());
		pipeline.add(new ModelInfoController());
		pipeline.add(new ModelPathController());
		pipeline.add(new ParamController());
		pipeline.add(new SystemController());
	}
	
	

	public BasicRouterHandler() {

	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		if (!request.decoderResult().isSuccess()) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求解析失败");
			return;
		}

		// 1.
		String uri = request.uri();
		//logger.info("收到请求: {} {}", request.method().name(), uri);
		// 傻逼浏览器不知道为什么一直在他妈的访问/.well-known/appspecific/com.chrome.devtools.json
		if ("/.well-known/appspecific/com.chrome.devtools.json".equals(uri)) {
			ctx.close();
			return;
		}

		// 处理模型API请求
		if (uri.startsWith("/api/") || uri.startsWith("/v1") || uri.startsWith("/session")) {
			for(BaseController c : pipeline) {
				boolean result = c.handleRequest(uri, ctx, request);
				if(result)
					break;
			}
			return;
		}

		try {
			// 断言一下请求方式
			this.assertRequestMethod(request.method() != HttpMethod.GET, "仅支持GET请求");
			// 解码URI
			String path = URLDecoder.decode(uri, "UTF-8");
			boolean isRootRequest = path.equals("/");

			if (isRootRequest) {
				// 只有当用户访问根路径时，才返回首页
				path = "/index.html";
			}
			// 
			if(path.indexOf('?') > 0) {
				path = path.substring(0, path.indexOf('?'));
			}
			
			URL url = LlamaServer.class.getResource("/web" + path);

			if (url == null) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "文件不存在: " + path);
				return;
			}
			// 对于非API请求，只允许访问静态文件，不允许目录浏览
			// 首先尝试从resources目录获取文件
			File file = new File(url.getFile().replace("%20", " "));
			if (!file.exists()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "文件不存在: " + path);
				return;
			}
			if (file.isDirectory()) {
				// 不允许直接访问目录，必须通过API
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.FORBIDDEN, "不允许直接访问目录，请使用API获取文件列表");
			} else {
				LlamaServer.sendFile(ctx, file);
			}
		} catch (RequestMethodException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.error("处理静态文件请求时发生错误", e);
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
		}
	}
	
	
	/**
	 * 	简单的断言。
	 * @param check
	 * @param message
	 * @throws RequestMethodException
	 */
	private void assertRequestMethod(boolean check, String message) throws RequestMethodException {
		if (check)
			throw new RequestMethodException(message);
	}
}
