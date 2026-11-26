package io.github.escosis.hwtfaceswap.protocol;


import io.github.escosis.hwtfaceswap.Tr;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** 已证认证后初始化报文布局，独立实现，不代表用户已同意协议。 */
public final class WristInitialization {
    private WristInitialization() {}
    public static final long OPTIONAL_WAIT_MS=1500;
    public static final String AGREEMENT_VERSION="20230508-20230508-0-0";
    public static final String AGREEMENT_MESSAGE=Tr.tr("腕连的此步会向手表声明接受三项协议：软件更新服务声明、设备信息管理、用户许可协议。\n\n协议版本字段为 ", "In questo passaggio l'app Wanlian dichiara al watch di accettare tre accordi: Informativa sul servizio di aggiornamento software, Gestione delle informazioni del dispositivo e Contratto di licenza per l'utente.\n\nIl campo versione degli accordi è", "At this step the Wanlian app declares to the watch that it accepts three agreements: Software Update Service Statement, Device Information Management and User License Agreement.\n\nThe agreement version field is", "En este paso la app Wanlian declara al reloj que acepta tres acuerdos: Declaración del servicio de actualización de software, Gestión de la información del dispositivo y Acuerdo de licencia de usuario.\n\nEl campo de versión de los acuerdos es", "À cette étape, l'app Wanlian déclare à la montre qu'elle accepte trois accords : Déclaration du service de mise à jour logicielle, Gestion des informations de l'appareil et Contrat de licence utilisateur.\n\nLe champ de version des accords est", "Neste passo a app Wanlian declara ao relógio que aceita três acordos: Declaração do serviço de atualização de software, Gestão das informações do dispositivo e Contrato de licença do utilizador.\n\nO campo de versão dos acordos é", "Nesta etapa o app Wanlian declara ao relógio que aceita três acordos: Declaração do serviço de atualização de software, Gerenciamento das informações do dispositivo e Contrato de licença do usuário.\n\nO campo de versão dos acordos é", "На тази стъпка приложението Wanlian декларира пред часовника, че приема три споразумения: Декларация за услугата за актуализиране на софтуера, Управление на информацията за устройството и Лицензионно споразумение с потребителя.\n\nПолето за версия на споразуменията е", "Bu adımda Wanlian uygulaması saate üç sözleşmeyi kabul ettiğini bildirir: Yazılım Güncelleme Hizmeti Bildirimi, Cihaz Bilgisi Yönetimi ve Kullanıcı Lisans Sözleşmesi.\n\nSözleşme sürümü alanı:", "In diesem Schritt erklärt die App Wanlian der Uhr, drei Vereinbarungen zu akzeptieren: Erklärung zum Software-Update-Dienst, Verwaltung von Geräteinformationen und Nutzerlizenzvereinbarung.\n\nDas Versionsfeld der Vereinbarungen lautet", "На этом шаге приложение Wanlian сообщает часам, что принимает три соглашения: Заявление о службе обновления ПО, Управление сведениями об устройстве и Лицензионное соглашение пользователя.\n\nПоле версии соглашений:")+AGREEMENT_VERSION+Tr.tr("。本应用没有取得协议全文，不会替你默认同意。只有你已在官方渠道阅读并同意相应条款时，才选择发送；否则请选择跳过。跳过不会发送 01/30，也不会清除配对或密钥。", ". Questa app non dispone del testo completo degli accordi e non accetterà nulla al posto tuo. Scegli Invia solo se hai già letto e accettato i relativi termini tramite i canali ufficiali; altrimenti scegli Salta. Saltando non verrà inviato 01/30 e non verranno cancellati abbinamenti o chiavi.", ". This app doesn't have the full text of the agreements and won't accept them on your behalf. Choose Send only if you have already read and accepted the terms through official channels; otherwise choose Skip. Skipping won't send 01/30 and won't clear pairings or keys.", ". Esta app no tiene el texto completo de los acuerdos y no los aceptará por ti. Elige Enviar solo si ya has leído y aceptado los términos por los canales oficiales; si no, elige Omitir. Omitir no enviará 01/30 ni borrará emparejamientos o claves.", ". Cette app ne dispose pas du texte intégral des accords et ne les acceptera pas à votre place. Choisissez Envoyer seulement si vous avez déjà lu et accepté les conditions via les canaux officiels ; sinon, choisissez Ignorer. Ignorer n'enverra pas 01/30 et n'effacera ni associations ni clés.", ". Esta app não tem o texto completo dos acordos e não os aceitará por si. Escolha Enviar só se já leu e aceitou os termos pelos canais oficiais; caso contrário, escolha Ignorar. Ignorar não envia 01/30 nem apaga emparelhamentos ou chaves.", ". Este app não tem o texto completo dos acordos e não os aceitará por você. Escolha Enviar só se você já leu e aceitou os termos pelos canais oficiais; caso contrário, escolha Pular. Pular não envia 01/30 nem apaga pareamentos ou chaves.", ". Това приложение няма пълния текст на споразуменията и няма да ги приеме вместо вас. Изберете „Изпращане“ само ако вече сте прочели и приели условията по официалните канали; иначе изберете „Пропускане“. Пропускането няма да изпрати 01/30 и няма да изтрие сдвоявания или ключове.", ". Bu uygulamada sözleşmelerin tam metni yok ve sizin yerinize kabul etmeyecek. Yalnızca koşulları resmî kanallardan okuyup kabul ettiyseniz Gönder'i seçin; aksi hâlde Atla'yı seçin. Atlamak 01/30 göndermez ve eşleştirmeleri veya anahtarları silmez.", ". Diese App hat nicht den vollständigen Text der Vereinbarungen und akzeptiert sie nicht für dich. Wähle Senden nur, wenn du die Bedingungen bereits über offizielle Kanäle gelesen und akzeptiert hast; sonst wähle Überspringen. Überspringen sendet 01/30 nicht und löscht weder Kopplungen noch Schlüssel.", ". У этого приложения нет полного текста соглашений, и оно не примет их за вас. Выбирайте «Отправить», только если вы уже прочитали и приняли условия через официальные каналы; иначе выберите «Пропустить». При пропуске 01/30 не отправляется, сопряжения и ключи не удаляются.");
    public static byte[] emptyTags(int... tags)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(int tag:tags)out.write(Core.tlv(tag,new byte[0]));return out.toByteArray();}
    /** w0.k0 的 16 个空字段已含软件版本 07；产品回复为可选信息，不作为 READY 门槛。 */
    public static byte[] productRequest()throws IOException{return emptyTags(1,2,7,9,10,17,18,22,26,29,30,31,32,33,34,35);}
    /** 只读取已证软件版本字段，缺失或无法辨认时不从设备名称或型号兜底。 */
    public static String productFirmware(List<TlvView.Part> fields)throws IOException{return DeviceInfo.firmware(fields);}
    /** 时间报文使用大端秒数，以及带符号的小时标记和分钟余数。 */
    public static byte[] timeRequest(long utcMillis,int offsetMillis)throws IOException{
        if(offsetMillis< -86400000||offsetMillis>86400000)throw new Core.Fault("TIME_ZONE_RANGE");
        int seconds=offsetMillis/1000,hours=seconds<0?-seconds/3600+128:seconds/3600,minutes=seconds/60%60;
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(Core.tlv(1,ByteBuffer.allocate(4).putInt((int)(utcMillis/1000)).array()));out.write(Core.tlv(2,new byte[]{(byte)hours,(byte)minutes}));return out.toByteArray();
    }
    public static byte[] settingsRequest()throws IOException{return emptyTags(1,2,3,4,5,6);}
    /** 潜在反向能力分支；原命令表缺少 3F，常规流程跳过。 */
    public static byte[] reverseRequest()throws IOException{return Core.tlv(1,new byte[]{(byte)0xfd,(byte)0xf7,0x73,0x7a});}
    /** 调用方必须先消费与当前连接绑定的明确同意，才能构造此声明。 */
    public static byte[] agreementsRequest(long millis)throws IOException{
        ByteArrayOutputStream rows=new ByteArrayOutputStream();
        for(String name:new String[]{"software_update_service_statement","device_information_management","user_license_agreement"}){
            ByteArrayOutputStream row=new ByteArrayOutputStream();row.write(Core.tlv(3,name.getBytes(StandardCharsets.UTF_8)));row.write(Core.tlv(4,new byte[]{1}));row.write(Core.tlv(5,AGREEMENT_VERSION.getBytes(StandardCharsets.UTF_8)));row.write(Core.tlv(6,Long.toString(millis).getBytes(StandardCharsets.UTF_8)));rows.write(Core.tlv(0x82,row.toByteArray()));
        }
        return Core.tlv(0x81,rows.toByteArray());
    }
    public static String boundedDeviceName(String name)throws IOException{
        String s=name==null||name.trim().isEmpty()?"HUAWEI Watch":name.trim();
        if(s.getBytes(StandardCharsets.UTF_8).length>128)throw new Core.Fault("DEVICE_NAME_SIZE");
        for(int i=0;i<s.length();i++)if(Character.isISOControl(s.charAt(i)))throw new Core.Fault("DEVICE_NAME_CONTROL");
        return s;
    }
    private static String optionalText(List<TlvView.Part> fields,int tag)throws IOException{
        TlvView.Part f=Wire.unique(fields,tag);if(f==null||f.value.length==0)return null;
        if(f.value.length>129)throw new Core.Fault("PRODUCT_NAME_SIZE");int end=f.value.length;if(f.value[end-1]==0)end--;
        String text=Core.utf8(Arrays.copyOf(f.value,end));return text.trim().isEmpty()?null:boundedDeviceName(text);
    }
    /** 名称优先取非空的 11 设备名，其次取 0A 型号，不记录序列号或账户。 */
    public static String productName(List<TlvView.Part> fields,String fallback)throws IOException{String name=optionalText(fields,0x11);if(name==null)name=optionalText(fields,0x0a);return name==null?boundedDeviceName(fallback):name;}
    public static byte[] setupRequest(String deviceName)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(Core.tlv(1,new byte[]{1}));out.write(Core.tlv(2,boundedDeviceName(deviceName).getBytes(StandardCharsets.UTF_8)));out.write(Core.tlv(3,new byte[]{0}));return out.toByteArray();
    }
    public static byte[] connectedRequest()throws IOException{return Core.tlv(1,new byte[]{1});}
}
