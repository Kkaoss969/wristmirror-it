package io.github.escosis.hwtfaceswap.protocol;


import io.github.escosis.hwtfaceswap.Tr;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** 只读取 01/07 产品信息的已证根层字段；型号保留设备原文，不映射商业名称或猜测能力。 */
public final class DeviceInfo {
    public static final int SERVICE = 0x01;
    public static final int PRODUCT_COMMAND = 0x07;
    public static final int FIRMWARE_TAG = 0x07;
    public static final int MODEL_TAG = 0x0a;
    /** 本地防御上限，不代表所有 GT/Fit 固件的协议长度合同。 */
    public static final int MAX_FIRMWARE_BYTES = 80;
    public static final int MAX_MODEL_BYTES = 128;

    // 接受点分数字主体及常见构建后缀；无法辨认的格式保留未知，不补造版本。
    private static final Pattern VERSION = Pattern.compile(
            "[vV]?[0-9]+(?:\\.[0-9]+)+(?:[A-Za-z][A-Za-z0-9._-]*|[ _-][A-Za-z0-9._-]+| ?\\([A-Za-z0-9._ -]+\\))*");

    private DeviceInfo() {}

    /** 调用方传入已经验证封装并解密的 01/07；不从嵌套字段、设备名或其他路由兜底。 */
    public static String model(int service, int command, byte[] plain) throws IOException {
        if (service != SERVICE || command != PRODUCT_COMMAND) {
            throw new Core.Fault("DEVICE_INFO_HEADER_MISMATCH");
        }
        return model(TlvView.decode(plain));
    }

    /** 原 w0.L 将根 0A 作为 model、11 作为 deviceName；只去一个末尾 NUL，不修复坏编码。 */
    public static String model(List<TlvView.Part> fields) throws IOException {
        Long error = Wire.number(fields, 0x7f);
        if (error != null && error != 0 && error != 100000) {
            throw new Core.Fault("PRODUCT_INFO_REJECTED");
        }
        TlvView.Part field = Wire.unique(fields, MODEL_TAG);
        if (field == null || field.value.length == 0) return null;
        if (field.value.length > MAX_MODEL_BYTES + 1) throw new Core.Fault("PRODUCT_MODEL_SIZE");
        int end = field.value.length;
        if (field.value[end - 1] == 0) end--;
        if (end > MAX_MODEL_BYTES) throw new Core.Fault("PRODUCT_MODEL_SIZE");
        return modelText(Core.utf8(Arrays.copyOf(field.value, end)));
    }

    /** 对已读型号和本地缓存使用同一有界校验；未知占位不是型号，不添加或映射任何名称。 */
    public static String modelText(String text) throws IOException {
        if (text == null) return null;
        if (text.length() > MAX_MODEL_BYTES) throw new Core.Fault("PRODUCT_MODEL_SIZE");
        for (int i = 0; i < text.length();) {
            int ch = text.codePointAt(i);
            i += Character.charCount(ch);
            if (Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT ||
                    ch >= 0xd800 && ch <= 0xdfff || ch == 0xfffd) {
                throw new Core.Fault("PRODUCT_MODEL_CONTROL");
            }
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_MODEL_BYTES) {
            throw new Core.Fault("PRODUCT_MODEL_SIZE");
        }
        String model = text.trim();
        return model.isEmpty() || model.equalsIgnoreCase("unknown") || model.equals(Tr.tr("未知", "Sconosciuto", "Unknown", "Desconocido", "Inconnu", "Desconhecido", "Desconhecido", "Неизвестно", "Bilinmiyor", "Unbekannt", "Неизвестно")) ||
                model.equals(Tr.tr("未知型号", "Modello sconosciuto", "Unknown model", "Modelo desconocido", "Modèle inconnu", "Modelo desconhecido", "Modelo desconhecido", "Неизвестен модел", "Bilinmeyen model", "Unbekanntes Modell", "Неизвестная модель")) || model.equalsIgnoreCase("null") || model.equalsIgnoreCase("n/a")
                ? null : model;
    }

    /** 调用方传入解密后的 TLV 正文；路由不匹配或正文损坏时不能当作产品信息。 */
    public static String firmware(int service, int command, byte[] plain) throws IOException {
        if (service != SERVICE || command != PRODUCT_COMMAND) {
            throw new Core.Fault("DEVICE_INFO_HEADER_MISMATCH");
        }
        return firmware(TlvView.decode(plain));
    }

    /**
     * 根层 07 为 softwareVersion；03 为硬件版本、09 为序列号、0A 为型号、11 为设备名。
     * 缺失、空内容或不具版本形状时返回 null；重复、坏编码和错误回执显式拒绝。
     * 可接受一个末尾 NUL，但不截断内部 NUL、不替换坏 UTF-8、不选择重复字段的首项。
     */
    public static String firmware(List<TlvView.Part> fields) throws IOException {
        Long error = Wire.number(fields, 0x7f);
        if (error != null && error != 0 && error != 100000) {
            throw new Core.Fault("PRODUCT_INFO_REJECTED");
        }
        TlvView.Part field = Wire.unique(fields, FIRMWARE_TAG);
        if (field == null || field.value.length == 0) return null;
        if (field.value.length > MAX_FIRMWARE_BYTES + 1) {
            throw new Core.Fault("PRODUCT_FIRMWARE_SIZE");
        }
        int end = field.value.length;
        if (field.value[end - 1] == 0) end--;
        if (end > MAX_FIRMWARE_BYTES) throw new Core.Fault("PRODUCT_FIRMWARE_SIZE");
        if (end == 0) return null;
        String text = Core.utf8(Arrays.copyOf(field.value, end));
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                throw new Core.Fault("PRODUCT_FIRMWARE_CONTROL");
            }
        }
        String version = text.trim();
        return VERSION.matcher(version).matches() ? version : null;
    }
}
