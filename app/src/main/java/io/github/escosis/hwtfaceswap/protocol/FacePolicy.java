package io.github.escosis.hwtfaceswap.protocol;

import java.util.List;

/**
 * 基于已观察到的 27/02 字段制定界面与选择策略，不据此判定安装结果或许可状态。
 * 原始条目 05 是位集合；原始条目 07 是枚举，在已读取的原生映射中范围为 0..5。
 * WatchFaceType.STICKER.value()==9 属于业务枚举，绝不等同于原始 type==9 或 tag0A==9。
 */
public final class FacePolicy {
    public static final long CURRENT=0x01L, PRESET=0x02L, SUPPORT_DELETE=0x04L, EDITABLE=0x08L;
    public static final long VIDEO=0x10L, PHOTO=0x20L, TRIAL=0x40L, KALEIDOSCOPE=0x80L;
    public static final long EXPAND_VIDEO=1L, EXPAND_PHOTO=2L, EXPAND_KALEIDOSCOPE=3L;
    public static final long EXPAND_WEAR=4L, EXPAND_STICKER=5L;
    private static final long KNOWN_RAW_BITS=0xffL;
    private static final long INELIGIBLE_BITS=PRESET|VIDEO|PHOTO|TRIAL|KALEIDOSCOPE;
    private FacePolicy() {}

    private static boolean typeObserved(TlvView.Face face) {
        return face!=null && face.type!=null && face.type.longValue()>=0 && face.type.longValue()<=0xffffffffL;
    }
    private static boolean has(TlvView.Face face,long bit) {
        return typeObserved(face) && (face.type.longValue()&bit)!=0;
    }
    private static boolean expand(TlvView.Face face,long value) {
        return face!=null && face.expandType!=null && face.expandType.longValue()==value;
    }
    /** 官方两套 27/02 接收模型对缺失 07 使用 int 默认 0；只派生业务值，不改写原始 null。 */
    private static long effectiveExpandType(TlvView.Face face) {
        return face.expandType==null?0L:face.expandType.longValue();
    }

    /** 即使条目受保护、属于特殊类型或不可选择，也按实际当前位标记。 */
    public static boolean current(TlvView.Face face) { return has(face,CURRENT); }

    /**
     * 仅选择已有的、非预置且可删除的普通表盘；可选 07 缺失时采用官方业务默认 0，
     * 原始缺失值仍保持 null。显式非零/未知 expand 不放行，贴纸由 expand==5 识别。
     * 未知原始高位、试用及其他特殊配置路径均禁用。
     * 这不代表预置、试用或未知条目在所有固件上都无法替换。
     */
    public static boolean eligible(TlvView.Face face) {
        if(!typeObserved(face) || !face.validResource())return false;
        long type=face.type.longValue();
        if((type&~KNOWN_RAW_BITS)!=0 || (type&SUPPORT_DELETE)==0 || (type&INELIGIBLE_BITS)!=0)return false;
        return effectiveExpandType(face)==0;
    }

    /**
     * 启用已安装资源与覆盖/删除资格分离：已观察到的 05 仅含已知 0xff 位，
     * 07 采用官方缺省 0 或已知 0..5 即可。预置、试用及特殊表盘可以走原 ID/原版本 op1，
     * 但这不允许新文件、特殊配置、版本改写或推定许可；未知/无效资源仍禁用。
     */
    public static boolean canActivate(TlvView.Face face) {
        if(!typeObserved(face) || !face.validResource())return false;
        if((face.type.longValue()&~KNOWN_RAW_BITS)!=0)return false;
        long expand=effectiveExpandType(face);
        return expand>=0 && expand<=EXPAND_STICKER;
    }

    /**
     * 仅提供类型标签，不编造表盘名称。特殊类型的优先级遵循原生
     * WatchFaceWearService.getWatchType(II)：视频、相册、万花筒、穿戴，然后是贴纸。
     */
    public static String kind(TlvView.Face face) {
        if(face==null)return "Tipo non confermato";
        String special=null;
        if(has(face,VIDEO)||expand(face,EXPAND_VIDEO))special="Quadrante video";
        if(has(face,PHOTO)||expand(face,EXPAND_PHOTO))special="Quadrante galleria";
        if(has(face,KALEIDOSCOPE)||expand(face,EXPAND_KALEIDOSCOPE))special="Quadrante caleidoscopio";
        if(expand(face,EXPAND_WEAR))special="Quadrante indossabile";
        if(expand(face,EXPAND_STICKER))special="Adesivi divertenti";
        if(special!=null)return special;
        if(!typeObserved(face) || (face.type.longValue()&~KNOWN_RAW_BITS)!=0)return "Tipo non confermato";
        if(effectiveExpandType(face)!=0)return "Tipo di estensione non confermato";
        if(has(face,TRIAL))return "Quadrante di prova";
        if(has(face,PRESET))return "Quadrante preinstallato";
        if(!has(face,SUPPORT_DELETE))return "Quadrante standard non eliminabile";
        return "Quadrante standard";
    }

    /**
     * 仅当实际观察到唯一当前条目，且该条目本身可操作时，才将其作为默认选择。
     * 当前条目为特殊或不支持的类型时，绝不默默改选其他条目。
     * 返回原始 Face 对象，以便调用方绑定 ID、版本及对应观察。
     */
    public static TlvView.Face defaultSelection(List<TlvView.Face> faces) {
        if(faces==null || faces.size()>TlvView.MAX_FIELDS)return null;
        TlvView.Face selected=null;
        for(TlvView.Face face:faces)if(current(face)){
            if(selected!=null)return null;
            selected=face;
        }
        return selected!=null && eligible(selected)?selected:null;
    }
}
