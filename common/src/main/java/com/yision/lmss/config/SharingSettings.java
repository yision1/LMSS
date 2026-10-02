package com.yision.lmss.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class SharingSettings {
    public enum RenderMode { DYNAMIC, STATIC, OFF }

    public RenderMode renderMode = RenderMode.DYNAMIC;
    public boolean shareGui = true;
    public boolean showRemoteGui = true;
    public boolean shareChatInput = true;
    public boolean sendMouseInfo = true;
    public boolean showGuiAnimations = true;
    public int maxShareDistance = 16;
    public double guiScale = 1.0;
    public int guiUpdateIntervalMs = 500;
    public boolean guiBlur = true;
    public boolean circularMask = true;
    public int textureUpdateIntervalMs;
    public boolean showOwnGuiInThirdPerson = true;
    public int guiCacheSeconds = 60;

    public static SharingSettings load(Path configDir) {
        Path file = configDir.resolve("lmss.toml");
        if (Files.notExists(file)) {
            SharingSettings defaults = new SharingSettings();
            try {
                defaults.save(configDir);
            } catch (IOException ignored) {}
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            CommentedConfig config = new TomlParser().parse(reader);
            Object value = config.get("renderMode");
            SharingSettings settings = new SharingSettings();
            settings.shareGui = bool(config, "shareGui", true);
            settings.showRemoteGui = bool(config, "showRemoteGui", true);
            settings.shareChatInput = bool(config, "shareChatInput", true);
            settings.sendMouseInfo = bool(config, "sendMouseInfo", true);
            settings.showGuiAnimations = bool(config, "showGuiAnimations", true);
            settings.maxShareDistance = integer(config, "maxShareDistance", 16, 1, 128);
            settings.guiScale = number(config, "guiScale", 1, 0.25, 4);
            settings.guiUpdateIntervalMs = integer(config, "guiUpdateIntervalMs", 500, 100, 10_000);
            settings.guiBlur = bool(config, "guiBlur", true);
            settings.circularMask = bool(config, "circularMask", true);
            settings.textureUpdateIntervalMs = integer(config, "textureUpdateIntervalMs", 0, 0, 10_000);
            settings.showOwnGuiInThirdPerson = bool(config, "showOwnGuiInThirdPerson", true);
            settings.guiCacheSeconds = integer(config, "guiCacheSeconds", 60, 0, 3600);
            if (value == null) {
                return settings;
            }
            if (!(value instanceof String mode)) {
                throw new IllegalArgumentException("Invalid renderMode");
            }
            settings.renderMode = switch (mode) {
                case "dynamic" -> RenderMode.DYNAMIC;
                case "static" -> RenderMode.STATIC;
                case "off" -> RenderMode.OFF;
                default -> throw new IllegalArgumentException("Invalid renderMode: " + mode);
            };
            return settings;
        } catch (IOException | ParsingException | IllegalArgumentException exception) {
            SharingSettings disabled = new SharingSettings();
            disabled.renderMode = RenderMode.OFF;
            return disabled;
        }
    }

    private static boolean bool(CommentedConfig config, String key, boolean fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException("Invalid " + key);
    }

    private static double number(CommentedConfig config, String key, double fallback, double min, double max) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            double result = number.doubleValue();
            if (Double.isFinite(result) && result >= min && result <= max) {
                return result;
            }
        }
        throw new IllegalArgumentException("Invalid " + key + ": expected " + min + ".." + max);
    }

    private static int integer(CommentedConfig config, String key, int fallback, int min, int max) {
        Object value = config.get(key);
        if (value != null && !(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException("Invalid integer " + key);
        }
        return (int) number(config, key, fallback, min, max);
    }

    public void save(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path file = configDir.resolve("lmss.toml");
        Path temporary = Files.createTempFile(configDir, "lmss-", ".toml.tmp");
        try {
            Files.writeString(temporary, """
                    # LMSS 配置文件

                    # 服务端：最大渲染距离（格），1～128。
                    maxShareDistance = %s

                    # 服务端：GUI缩放，0.25～4.0
                    guiScale = %s

                    # 服务端：GUI更新间隔（毫秒），100～10000。
                    guiUpdateIntervalMs = %s

                    # 服务端：圆形预览，关闭后显示完整GUI。
                    circularMask = %s

                    # 客户端与服务端：各自保留已关闭GUI的缓存时长（秒），0～3600；0 禁用。
                    guiCacheSeconds = %s

                    # 客户端：GUI模式：dynamic ，static ，off
                    renderMode = "%s"

                    # 客户端：向其他玩家分享自己的GUI。
                    shareGui = %s

                    # 客户端：显示他人的GUI。
                    showRemoteGui = %s

                    # 客户端：分享聊天输入框内容
                    shareChatInput = %s

                    # 客户端：同步鼠标位置和按键。
                    sendMouseInfo = %s

                    # 客户端：显示头部、手臂和点击动作。
                    showGuiAnimations = %s

                    # 客户端：柔化GUI和鼠标指针
                    guiBlur = %s

                    # 客户端：GUI刷新间隔（毫秒），0 为不限制。
                    textureUpdateIntervalMs = %s

                    # 客户端：第三人称显示自己的GUI
                    showOwnGuiInThirdPerson = %s
                    """.formatted(maxShareDistance, guiScale, guiUpdateIntervalMs, circularMask, guiCacheSeconds,
                            renderMode.name().toLowerCase(Locale.ROOT), shareGui, showRemoteGui, shareChatInput,
                            sendMouseInfo, showGuiAnimations, guiBlur, textureUpdateIntervalMs, showOwnGuiInThirdPerson));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
