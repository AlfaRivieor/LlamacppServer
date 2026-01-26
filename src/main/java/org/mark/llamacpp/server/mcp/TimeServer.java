package org.mark.llamacpp.server.mcp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;

import org.mark.llamacpp.server.tools.JsonUtil;

import com.google.gson.JsonObject;

public class TimeServer {

	private static final String DEFAULT_SOURCE_TIMEZONE = "Asia/Shanghai";

	private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public static String executeToText(String toolName, String toolArguments) {
		if (toolName == null || toolName.isBlank()) {
			throw new IllegalArgumentException("toolName不能为空");
		}
		String name = toolName.trim();
		JsonObject argsObj = parseArgsObject(toolArguments);
		if ("get_current_time".equals(name)) {
			return getCurrentTime(argsObj);
		}
		if ("convert_time".equals(name)) {
			return convertTime(argsObj);
		}
		throw new IllegalArgumentException("不支持的时间工具: " + name);
	}

	private static String getCurrentTime(JsonObject argsObj) {
		String timezone = trimToNull(JsonUtil.getJsonString(argsObj, "timezone", null));
		ZoneId zone = (timezone == null) ? ZoneId.systemDefault() : parseZoneId(timezone, "timezone");
		ZonedDateTime now = ZonedDateTime.now(zone);
		String offset = now.getOffset().getId();
		return DATE_TIME_FORMATTER.format(now) + " " + offset + " " + zone.getId();
	}

	private static String convertTime(JsonObject argsObj) {
		String sourceTimezone = trimToNull(JsonUtil.getJsonString(argsObj, "source_timezone", null));
		if (sourceTimezone == null) {
			sourceTimezone = DEFAULT_SOURCE_TIMEZONE;
		}
		String targetTimezone = trimToNull(JsonUtil.getJsonString(argsObj, "target_timezone", null));
		String time = trimToNull(JsonUtil.getJsonString(argsObj, "time", null));

		if (targetTimezone == null) {
			throw new IllegalArgumentException("缺少必填参数: target_timezone");
		}
		if (time == null) {
			throw new IllegalArgumentException("缺少必填参数: time (HH:MM)");
		}

		ZoneId src = parseZoneId(sourceTimezone, "source_timezone");
		ZoneId tgt = parseZoneId(targetTimezone, "target_timezone");
		LocalTime lt = parseLocalTime(time);

		LocalDate date = LocalDate.now(src);
		LocalDateTime ldt = LocalDateTime.of(date, lt);

		ZonedDateTime srcZdt = resolveLocalDateTime(src, ldt);
		ZonedDateTime tgtZdt = srcZdt.withZoneSameInstant(tgt);

		String srcText = DATE_TIME_FORMATTER.format(srcZdt) + " " + srcZdt.getOffset().getId() + " " + src.getId();
		String tgtText = DATE_TIME_FORMATTER.format(tgtZdt) + " " + tgtZdt.getOffset().getId() + " " + tgt.getId();
		return srcText + " -> " + tgtText;
	}

	private static ZonedDateTime resolveLocalDateTime(ZoneId zone, LocalDateTime ldt) {
		ZoneRules rules = zone.getRules();
		List<ZoneOffset> offsets = rules.getValidOffsets(ldt);
		if (offsets != null && !offsets.isEmpty()) {
			ZoneOffset preferred = offsets.get(0);
			return ZonedDateTime.ofLocal(ldt, zone, preferred);
		}
		ZoneOffsetTransition transition = rules.getTransition(ldt);
		if (transition != null) {
			throw new IllegalArgumentException("该时间在时区 " + zone.getId() + " 中无效（夏令时切换导致缺口）: " + ldt);
		}
		throw new IllegalArgumentException("无法解析时间与时区: " + ldt + " @ " + zone.getId());
	}

	private static LocalTime parseLocalTime(String time) {
		try {
			return LocalTime.parse(time.trim(), TIME_FORMATTER);
		} catch (Exception e) {
			throw new IllegalArgumentException("time参数格式错误，需为24小时制 HH:MM: " + time);
		}
	}

	private static ZoneId parseZoneId(String timezone, String fieldName) {
		try {
			return ZoneId.of(timezone.trim());
		} catch (Exception e) {
			throw new IllegalArgumentException("无效的IANA时区(" + fieldName + "): " + timezone);
		}
	}

	private static JsonObject parseArgsObject(String toolArguments) {
		if (toolArguments == null || toolArguments.isBlank()) {
			return new JsonObject();
		}
		try {
			JsonObject obj = JsonUtil.fromJson(toolArguments, JsonObject.class);
			return obj == null ? new JsonObject() : obj;
		} catch (Exception e) {
			return new JsonObject();
		}
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}
}
