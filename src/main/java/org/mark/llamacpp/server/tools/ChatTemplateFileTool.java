package org.mark.llamacpp.server.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

import org.mark.llamacpp.server.LlamaServer;

public final class ChatTemplateFileTool {
	private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

	private ChatTemplateFileTool() {
	}

	private static Object lockForModel(String modelId) {
		String safeModelId = modelId.replaceAll("[^a-zA-Z0-9._-]", "_");
		return LOCKS.computeIfAbsent(safeModelId, k -> new Object());
	}

	public static Path resolveChatTemplateCachePath(String modelId) {
		if (modelId == null || modelId.trim().isEmpty()) {
			return null;
		}
		Path cacheDir = LlamaServer.getCachePath();
		String safeModelId = modelId.replaceAll("[^a-zA-Z0-9._-]", "_");
		return cacheDir.resolve(safeModelId + ".jinja").toAbsolutePath().normalize();
	}

	public static String writeChatTemplateToCacheFile(String modelId, String chatTemplate) throws Exception {
		if (modelId == null || modelId.trim().isEmpty()) {
			return null;
		}
		if (chatTemplate == null || chatTemplate.trim().isEmpty()) {
			return null;
		}

		Object lock = lockForModel(modelId);
		synchronized (lock) {
			Path templatePath = resolveChatTemplateCachePath(modelId);
			if (templatePath == null) {
				return null;
			}
			Files.createDirectories(templatePath.getParent());
			Path tmp = templatePath.resolveSibling(templatePath.getFileName() + ".tmp");
			Files.write(tmp, chatTemplate.getBytes(StandardCharsets.UTF_8));
			try {
				Files.move(tmp, templatePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, templatePath, StandardCopyOption.REPLACE_EXISTING);
			}
			return templatePath.toString();
		}
	}

	public static String getChatTemplateCacheFilePathIfExists(String modelId) {
		try {
			if (modelId == null || modelId.trim().isEmpty()) return null;
			Object lock = lockForModel(modelId);
			synchronized (lock) {
				Path templatePath = resolveChatTemplateCachePath(modelId);
				if (templatePath == null) return null;
				return Files.exists(templatePath) && Files.isRegularFile(templatePath) ? templatePath.toString() : null;
			}
		} catch (Exception ignore) {
			return null;
		}
	}

	public static boolean deleteChatTemplateCacheFile(String modelId) {
		try {
			if (modelId == null || modelId.trim().isEmpty()) return false;
			Object lock = lockForModel(modelId);
			synchronized (lock) {
				Path templatePath = resolveChatTemplateCachePath(modelId);
				if (templatePath == null) return false;
				if (!Files.exists(templatePath) || !Files.isRegularFile(templatePath)) return false;
				return Files.deleteIfExists(templatePath);
			}
		} catch (Exception ignore) {
			return false;
		}
	}

	public static String readChatTemplateFromCacheFile(String modelId) {
		if (modelId == null || modelId.trim().isEmpty()) {
			return null;
		}
		try {
			Object lock = lockForModel(modelId);
			synchronized (lock) {
				Path templatePath = resolveChatTemplateCachePath(modelId);
				if (templatePath == null) return null;
				if (!Files.exists(templatePath)) {
					return null;
				}
				String content = Files.readString(templatePath, StandardCharsets.UTF_8);
				return content != null && !content.trim().isEmpty() ? content : null;
			}
		} catch (Exception ignore) {
			return null;
		}
	}
}
