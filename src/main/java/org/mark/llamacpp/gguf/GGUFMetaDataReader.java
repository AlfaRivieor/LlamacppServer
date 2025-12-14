package org.mark.llamacpp.gguf;

public class GGUFMetaDataReader {

    public static java.util.Map<String, Object> read(java.io.File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return java.util.Collections.emptyMap();
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
             java.nio.channels.FileChannel channel = raf.getChannel()) {
            long size = channel.size();
            long mapSize = Math.min(size, 64L * 1024 * 1024);
            java.nio.ByteBuffer buffer = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, mapSize);
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            byte[] magic = new byte[4];
            buffer.get(magic);
            String m = new String(magic, java.nio.charset.StandardCharsets.US_ASCII);
            if (!"GGUF".equals(m)) {
                return java.util.Collections.emptyMap();
            }
            buffer.getInt();
            buffer.getLong();
            long kvCount = buffer.getLong();
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            for (long i = 0; i < kvCount; i++) {
                String key = readString(buffer);
                int type = buffer.getInt();
                if ("tokenizer.ggml.tokens".equals(key) && type == 9) {
                    int elemType = buffer.getInt();
                    long len = buffer.getLong();
                    for (long j = 0; j < len; j++) {
                        skipValue(buffer, elemType);
                    }
                    metadata.put(key + ".size", len);
                } else {
                    Object value = readValue(buffer, type);
                    metadata.put(key, value);
                }
            }
            metadata.put("file.name", file.getName());
            metadata.put("file.path", file.getAbsolutePath());
            return metadata;
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }

    private static String readString(java.nio.ByteBuffer buffer) {
        long len = buffer.getLong();
        byte[] bytes = new byte[(int) len];
        buffer.get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Object readValue(java.nio.ByteBuffer buffer, int type) {
        switch (type) {
            case 0:
                return buffer.get() & 0xFF;
            case 1:
                return buffer.get();
            case 2:
                return buffer.getShort() & 0xFFFF;
            case 3:
                return buffer.getShort();
            case 4:
                return buffer.getInt() & 0xFFFFFFFFL;
            case 5:
                return buffer.getInt();
            case 6:
                return buffer.getFloat();
            case 7:
                return buffer.get() != 0;
            case 8:
                return readString(buffer);
            case 9:
                return readArray(buffer);
            case 10:
                return buffer.getLong();
            case 11:
                return buffer.getLong();
            case 12:
                return buffer.getDouble();
            default:
                throw new IllegalArgumentException("Unknown GGUF value type: " + type);
        }
    }

    private static java.util.List<Object> readArray(java.nio.ByteBuffer buffer) {
        int t = buffer.getInt();
        long len = buffer.getLong();
        java.util.List<Object> list = new java.util.ArrayList<>((int) len);
        for (int i = 0; i < len; i++) {
            list.add(readValue(buffer, t));
        }
        return list;
    }

    private static void skipValue(java.nio.ByteBuffer buffer, int type) {
        switch (type) {
            case 0:
                buffer.get();
                return;
            case 1:
                buffer.get();
                return;
            case 2:
                buffer.getShort();
                return;
            case 3:
                buffer.getShort();
                return;
            case 4:
                buffer.getInt();
                return;
            case 5:
                buffer.getInt();
                return;
            case 6:
                buffer.getFloat();
                return;
            case 7:
                buffer.get();
                return;
            case 8: {
                long len = buffer.getLong();
                int n = (int) len;
                buffer.position(buffer.position() + n);
                return;
            }
            case 9: {
                int t = buffer.getInt();
                long len = buffer.getLong();
                for (long i = 0; i < len; i++) {
                    skipValue(buffer, t);
                }
                return;
            }
            case 10:
                buffer.getLong();
                return;
            case 11:
                buffer.getLong();
                return;
            case 12:
                buffer.getDouble();
                return;
            default:
                return;
        }
    }
}
