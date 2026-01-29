package org.mark.llamacpp.ollama;

import org.mark.llamacpp.ollama.channel.OllamaRouterHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;

public class Ollama {
	/**
	 * 	
	 */
	private static final Logger logger = LoggerFactory.getLogger(Ollama.class);
	
	
	
	
	
	
	public Ollama() {
		
	}
	
	private Thread worker;
	
	/**
	 * 	默认端口
	 */
	private int port = 11435;
	
	
	
	
	
	public void start() {
		this.worker = new Thread(() -> {
	        EventLoopGroup bossGroup = new NioEventLoopGroup();
	        EventLoopGroup workerGroup = new NioEventLoopGroup();
	        
	        try {
	            ServerBootstrap bootstrap = new ServerBootstrap();
	            bootstrap.group(bossGroup, workerGroup)
	                    .channel(NioServerSocketChannel.class)
	                    .childHandler(new ChannelInitializer<SocketChannel>() {
	                        @Override
	                        protected void initChannel(SocketChannel ch) throws Exception {
	                            ch.pipeline()
	                                    .addLast(new HttpServerCodec())
	                                    .addLast(new HttpObjectAggregator(Integer.MAX_VALUE)) // 最大！
	                                    .addLast(new ChunkedWriteHandler())
	                                    
	                                    .addLast(new OllamaRouterHandler());
	                        }
	                        @Override
	                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
	                        		logger.info("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
	                            ctx.close();
	                        }
	                    });
	            
	            ChannelFuture future = bootstrap.bind(this.port).sync();
	            logger.info("LlammServer启动成功，端口: {}", this.port);
	            logger.info("访问地址: http://localhost:{}", this.port);
	            
	            future.channel().closeFuture().sync();
	        } catch (InterruptedException e) {
	            logger.info("服务器被中断", e);
	            Thread.currentThread().interrupt();
	        } catch (Exception e) {
	            logger.info("服务器启动失败", e);
	        } finally {
	            bossGroup.shutdownGracefully();
	            workerGroup.shutdownGracefully();
	            
	            logger.info("服务器已关闭");
	        }
		});
		this.worker.start();
	}
}
