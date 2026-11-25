package io.github.escosis.hwtfaceswap.transport;

import android.app.*;
import android.content.*;
import android.os.*;
import io.github.escosis.hwtfaceswap.AppRuntime;
import io.github.escosis.hwtfaceswap.MainActivity;
import io.github.escosis.hwtfaceswap.R;
import java.lang.ref.WeakReference;

/** 通过前台服务维护本应用的设备连接生命周期；不维持或控制运动健康的连接。 */
public final class ConnectionService extends Service {
    private static final String CHANNEL="watch-connection";private static final int ID=701;private static volatile WeakReference<ConnectionService> current=new WeakReference<>(null);
    private PowerManager.WakeLock wake;private final Handler main=new Handler(Looper.getMainLooper());private long servedEpoch=-1;private boolean plannedStop;
    public static void runtimeState(boolean linkActive,boolean transferActive,String phase){ConnectionService s=current.get();if(s!=null)s.main.post(()->{if(current.get()!=s)return;AppRuntime.Snapshot latest=AppRuntime.get(s).snapshot();s.update(latest.connecting||latest.ready,latest.active,latest.code);});}
    public void onCreate(){super.onCreate();current=new WeakReference<>(this);NotificationManager m=getSystemService(NotificationManager.class);m.createNotificationChannel(new NotificationChannel(CHANNEL,"Connessione al watch",NotificationManager.IMPORTANCE_LOW));PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"gt7faces:transfer");wake.setReferenceCounted(false);startForeground(ID,notification("Connessione al dispositivo"));}
    public int onStartCommand(Intent intent,int flags,int startId){if(intent!=null&&"disconnect".equals(intent.getAction())){AppRuntime.get(this).connection.disconnect();stopSelf();return START_NOT_STICKY;}AppRuntime.Snapshot s=AppRuntime.get(this).snapshot();update(true,s.active,s.code);main.postDelayed(()->{AppRuntime.Snapshot latest=AppRuntime.get(this).snapshot();if(!latest.ready&&!latest.connecting)stopSelf();},3000L);return START_NOT_STICKY;}
    private Notification notification(String detail){PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);PendingIntent disconnect=PendingIntent.getService(this,2,new Intent(this,ConnectionService.class).setAction("disconnect"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.connection_notification_title)).setContentText(detail).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Disconnetti",disconnect).build()).build();}
    private void update(boolean linkActive,boolean active,String phase){servedEpoch=AppRuntime.get(this).snapshot().epoch;if(active&&!wake.isHeld())wake.acquire(900000);if(!active&&wake.isHeld())wake.release();if(!linkActive){plannedStop=true;stopSelf();return;}plannedStop=false;NotificationManager m=getSystemService(NotificationManager.class);m.notify(ID,notification(active?"Elaborazione del quadrante in corso, mantieni la connessione":phase.equals("READY")?"Watch connesso":"Connessione/autenticazione del dispositivo in corso"));}
    public IBinder onBind(Intent intent){return null;}
    public void onDestroy(){if(current.get()==this)current=new WeakReference<>(null);if(wake!=null&&wake.isHeld())wake.release();AppRuntime.Snapshot s=AppRuntime.get(this).snapshot();if(!plannedStop&&s.epoch==servedEpoch&&(s.ready||s.connecting))AppRuntime.get(this).connection.disconnectIfEpoch(servedEpoch);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
