package org.mark.llamacpp.lmstudio.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.ReferenceCountUtil;


/**
 * 	不知所谓的websocket握手
 */
public class LMStudioWsPathSelectHandler extends ChannelInboundHandlerAdapter {
	
	/**
	 * 	不知所谓的websocket端点。
	 */
	private static final String WEBSOCKET_LLM_PATH = "/llm";
	
	/**
	 * 	不知所谓的websocket端点。
	 */
	private static final String WEBSOCKET_SYSTEM_PATH = "/system";
	
	
	public LMStudioWsPathSelectHandler() {
		
	}
	
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (!(msg instanceof FullHttpRequest request)) {
			ctx.fireChannelRead(msg);
			return;
		}
		try {
			if (!this.isWebSocketUpgrade(request)) {
				ctx.fireChannelRead(request.retain());
				return;
			}

			String uri = request.uri();
			String path = uri == null ? null : uri.split("\\?", 2)[0];
			if (!WEBSOCKET_SYSTEM_PATH.equals(path) && !WEBSOCKET_LLM_PATH.equals(path)) {
				FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
				resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
				ctx.writeAndFlush(resp).addListener(f -> ctx.close());
				return;
			}

			String selfName = ctx.name();
			ctx.pipeline().addAfter(selfName, "lmstudio-ws-protocol", new WebSocketServerProtocolHandler(path, null, true, Integer.MAX_VALUE));
			ctx.pipeline().addAfter("lmstudio-ws-protocol", "lmstudio-ws-handler", new LMStudioWebSocketHandler());
			ctx.fireChannelRead(request.retain());
			ctx.pipeline().remove(this);
		} finally {
			ReferenceCountUtil.release(request);
		}
	}
	
	/**
	 * 	判断是不是websocket
	 * @param request
	 * @return
	 */
	private boolean isWebSocketUpgrade(FullHttpRequest request) {
		if (request == null) return false;
		String upgrade = request.headers().get(HttpHeaderNames.UPGRADE);
		if (upgrade == null || !HttpHeaderValues.WEBSOCKET.toString().equalsIgnoreCase(upgrade)) return false;
		String connection = request.headers().get(HttpHeaderNames.CONNECTION);
		if (connection == null) return false;
		return connection.toLowerCase().contains(HttpHeaderValues.UPGRADE.toString());
	}
}
