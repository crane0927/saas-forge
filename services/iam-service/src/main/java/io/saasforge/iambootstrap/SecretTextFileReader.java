package io.saasforge.iambootstrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 读取外部挂载的单行 UTF-8 Secret，不在失败信息中暴露路径或内容。 */
public final class SecretTextFileReader {

    /** maximumBytes 限制 Secret 内容本身；一个终止 LF 或 CRLF 不计入长度。 */
    public String read(Path path, int maximumBytes) {
        byte[] bytes = null;
        try {
            bytes = Files.readAllBytes(path);
            if (bytes.length == 0 || bytes.length > (long) maximumBytes + 2) {
                throw new IllegalArgumentException("IAM 受限任务 Secret 长度不合法");
            }
            String value = decode(bytes);
            int terminalLineEndingBytes = 0;
            if (value.endsWith("\r\n")) {
                value = value.substring(0, value.length() - 2);
                terminalLineEndingBytes = 2;
            } else if (value.endsWith("\n")) {
                value = value.substring(0, value.length() - 1);
                terminalLineEndingBytes = 1;
            }
            if (bytes.length - terminalLineEndingBytes > maximumBytes) {
                throw new IllegalArgumentException("IAM 受限任务 Secret 长度不合法");
            }
            if (value.isEmpty() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("IAM 受限任务 Secret 必须是单行文本");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 IAM 受限任务 Secret", exception);
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("IAM 受限任务 Secret 必须使用 UTF-8", exception);
        }
    }
}
