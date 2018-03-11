package com.aware.plugin.upmc.dash.services;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.aware.Aware;
import com.aware.plugin.upmc.dash.activities.NotificationResponseActivity;
import com.aware.plugin.upmc.dash.activities.SetupLoadingActvity;
import com.aware.plugin.upmc.dash.utils.Constants;
import com.aware.plugin.upmc.dash.R;
import com.aware.plugin.upmc.dash.settings.Settings;
import com.aware.plugin.upmc.dash.fileutils.SyncFilesParams;
import com.aware.plugin.upmc.dash.fileutils.SyncFilesResponse;
import com.aware.plugin.upmc.dash.fileutils.SyncFilesTask;
import com.aware.plugin.upmc.dash.activities.UPMC;
import com.aware.plugin.upmc.dash.receivers.SnoozeReceiver;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static com.aware.plugin.upmc.dash.utils.Constants.CONNECTED_WEAR;
import static com.aware.plugin.upmc.dash.utils.Constants.DB_NAME;
import static com.aware.plugin.upmc.dash.utils.Constants.FAILED_WEAR;
import static com.aware.plugin.upmc.dash.utils.Constants.PASS;
import static com.aware.plugin.upmc.dash.utils.Constants.TABLE_CONN;
import static com.aware.plugin.upmc.dash.utils.Constants.TABLE_MINI;
import static com.aware.plugin.upmc.dash.utils.Constants.TABLE_NOTIF;
import static com.aware.plugin.upmc.dash.utils.Constants.USER;

/**
 * Created by RaghuTeja on 6/23/17.
 */

public class MessageService extends WearableListenerService implements
        GoogleApiClient.ConnectionCallbacks, DataApi.DataListener, SyncFilesResponse {

    public boolean wearConnected = false;
    private GoogleApiClient mGoogleApiClient;
    private NotificationCompat.Builder monitorNotifCompatBuilder;
    private Notification.Builder setupNotifBuilder;
    private NotificationCompat.Builder setupNotifCompatBuilder;
    private AlarmManager mAlarmManager;
    private int count = 0;
    private int notifCount = 0;
    private int miniCount = 0;
    private String NODE_ID;
    private boolean isNodeSaved = false;
    private BroadcastReceiver mBluetootLocalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(Constants.BLUETOOTH_COMM_KEY)) {
                int state = intent.getIntExtra(Constants.BLUETOOTH_COMM_KEY, BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        Log.d(Constants.TAG, "MessageService:BluetoothReceiver:StateOff");
                        notifySetup(Constants.FAILED_WEAR_BLUETOOTH);
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        Log.d(Constants.TAG, "MessageService:BluetoothReceiver:StateTurningOff");
                        notifySetup(Constants.FAILED_WEAR_BLUETOOTH);
                        break;
                    case BluetoothAdapter.STATE_ON:
                        Log.d(Constants.TAG, "MessageService:BluetoothReceiver:StateOn");
//                        setUpNodeIdentities();
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        Log.d(Constants.TAG, "MessageService:BluetoothReceiver:StateTurningOn");
                        break;
                }
            }
        }
    };

    private BroadcastReceiver mSnoozeAlarmLocalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(Constants.SNOOZE_ALARM_EXTRA_KEY)) {
                notifyUserWithInactivity();
            }
        }
    };

    private BroadcastReceiver mNotifLocalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(Constants.NOTIF_KEY)) {
                Log.d(Constants.TAG, "MessageService: NotifLocalReceiver:Received :  " + intent.getStringExtra(Constants.NOTIF_KEY));
                String action = intent.getStringExtra(Constants.NOTIF_KEY);
                switch (action) {
                    case Constants.SNOOZE_ACTION:
                        snoozeInactivityNotif();
                        break;
                    case Constants.OK_ACTION:
                        dismissInactivtyNotif();
                        break;
                    case Constants.NO_ACTION:
                        dismissInactivtyNotif();
                        break;
                }
            }
        }
    };

    public void dismissAppraisal() {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(3);
    }

    public void dismissInactivtyNotif() {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(3);
    }

    public void snoozeInactivityNotif() {
        Intent snoozeInt = new Intent(this, SnoozeReceiver.class);
        snoozeInt.putExtra(Constants.ALARM_COMM, Constants.SNOOZE_ALARM_EXTRA);
        mAlarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        PendingIntent snoozePendInt = PendingIntent.getBroadcast(this, 56, snoozeInt, 0);
        mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 15 * 60 * 1000, snoozePendInt);
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(3);
    }

//    public boolean isNodeSaved() {
//        return isNodeSaved;
//    }
//
//    public void setNodeSaved(boolean nodeSaved) {
//        isNodeSaved = nodeSaved;
//    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(Constants.TAG, "MessageService:onDestroy");
        stopForeground(true);
        Wearable.DataApi.removeListener(mGoogleApiClient, this);
        Wearable.MessageApi.removeListener(mGoogleApiClient, this);
        Wearable.CapabilityApi.removeListener(mGoogleApiClient, this);
        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBluetootLocalReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mNotifLocalReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mSnoozeAlarmLocalReceiver);
        stopSelf();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(Constants.TAG, "MessageService: onCreate");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .build();
        mGoogleApiClient.connect();
    }

//    public String getNODE_ID() {
//        return NODE_ID;
//    }
//
//    public void setNODE_ID(String NODE_ID) {
//        this.NODE_ID = NODE_ID;
//        setNodeSaved(true);
//    }

//    private void setUpNodeIdentities() {
//        Wearable.CapabilityApi.getCapability(mGoogleApiClient, Constants.CAPABILITY_WEAR_APP, CapabilityApi.FILTER_REACHABLE).setResultCallback(new ResultCallback<CapabilityApi.GetCapabilityResult>() {
//            @Override
//            public void onResult(@NonNull CapabilityApi.GetCapabilityResult getCapabilityResult) {
//                CapabilityInfo info = getCapabilityResult.getCapability();
//                Set<Node> nodes = info.getNodes();
//                String NODE_ID;
//                if (nodes.size() == 1) {
//                    for (Node node : nodes) {
//                        NODE_ID = node.getId();
//                        Log.d(Constants.TAG, "MessageService:setUpNodeIdentities: " + NODE_ID);
//                        setNODE_ID(NODE_ID);
//                        isWearServiceRunning(getNODE_ID());
//                    }
//                }
//            }
//        });
//    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);
        byte[] input = messageEvent.getData();
        String message = new String(input);
        Log.d(Constants.TAG, "MessageService: onMessageReceived: " + message + " " + count);
        //Log.d(Constants.TAG, "MessageService: onMessageReceived: buildPath" + messageEvent.getPath());
        count++;
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.scheme("wear").path("/upmc-dash").build();
        if (messageEvent.getPath().equals(uriBuilder.toString())) {
//            if (!isNodeSaved()) {
//                setNODE_ID(messageEvent.getSourceNodeId());
//            }
            if (!isWearConnected()) {
                setWearConnected(true);
                notifySetup(CONNECTED_WEAR);
            }
//            switch (message) {
//                case Constants.ACK:
//                    break;
//                case Constants.STATUS_LOGGING:
//                    sendMessageToWear(Constants.ACK);
//                    notifySetup(Constants.CONNECTED_WEAR);
//                    break;
//                case Constants.STATUS_INIT:
//                    sendMessageToWear(Constants.ACK);
//                    Log.d(Constants.TAG, "MessageService:onMessageReceived:TimeInit");
//                    notifySetup(Constants.CONNECTED_WEAR);
////                    if(isWearInitializable()){
////                        initializeWear();
////                    }
//                    break;
//                case Constants.NOTIF_INACTIVITY:
//                    sendMessageToWear(Constants.ACK);
//                    Log.d(Constants.TAG, "MessageService:onMessageReceived:InactiveUser");
//                    notifyUserWithInactivity();
//                    break;
//                case Constants.NOTIFY_GREAT_JOB:
//                    sendMessageToWear(Constants.ACK);
//                    Log.d(Constants.TAG, "MessageService:onMessageReceived:GreatJobUser");
//                    notifyUserWithAppraisal();
//                    break;
//            }
        }
    }

    public boolean isWearInitializable() {
        final Context context = getApplicationContext();
        boolean morning_hour = Integer.parseInt(Aware.getSetting(context, Settings.PLUGIN_UPMC_CANCER_MORNING_HOUR)) != -1;
        boolean morning_minute = Integer.parseInt(Aware.getSetting(context, Settings.PLUGIN_UPMC_CANCER_MORNING_MINUTE)) != -1;
        boolean night_hour = Integer.parseInt(Aware.getSetting(context, Settings.PLUGIN_UPMC_CANCER_NIGHT_HOUR)) != -1;
        boolean night_minute = Integer.parseInt(Aware.getSetting(context, Settings.PLUGIN_UPMC_CANCER_NIGHT_MINUTE)) != -1;
        boolean severity = Integer.parseInt(Aware.getSetting(context, Settings.PLUGIN_UPMC_CANCER_SYMPTOM_SEVERITY)) != -1;
        return (morning_hour && morning_minute & night_hour && night_minute && severity);
    }

    public void notifyUserWithAppraisal() {
        final Intent dashIntent = new Intent(this, MessageService.class).setAction(Constants.ACTION_APPRAISAL);
        PowerManager pm = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "Wake Up");
        wl.acquire(6000);
        final Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vibrator.vibrate(3000);
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(Constants.NOTIFICATION_CHANNEL_ID, "UPMC Dash", NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.setDescription("UPMC Dash notification channel");
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.enableVibration(true);
            mNotificationManager.createNotificationChannel(notificationChannel);
            PendingIntent dashPendingIntent = PendingIntent.getForegroundService(this, 0, dashIntent, 0);
            setupNotifBuilder.setAutoCancel(false)
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(R.drawable.common_google_signin_btn_icon_dark_normal)
                    .setContentTitle("UPMC Dash Monitor")
                    .setContentText(Constants.NOTIF_APPRAISAL)
                    .setGroup("Prompt")
                    .setOngoing(true)
                    .setContentIntent(dashPendingIntent);
        } else {
            PendingIntent dashPendingIntent = PendingIntent.getService(this, 0, dashIntent, 0);
            monitorNotifCompatBuilder = new NotificationCompat.Builder(getApplicationContext(), Constants.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.common_google_signin_btn_icon_dark_normal)
                    .setContentTitle("UPMC Dash Monitor")
                    .setContentText(Constants.NOTIF_APPRAISAL)
                    .setOngoing(true)
                    .setContentIntent(dashPendingIntent)
                    .setGroup("Prompt")
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                    .setPriority(NotificationCompat.PRIORITY_MAX);
            mNotificationManager.notify(3, monitorNotifCompatBuilder.build());
        }
    }

    public void notifyUserWithInactivity() {
        final Intent dashIntent = new Intent(this, MessageService.class).setAction(Constants.ACTION_INACTIVITY);
        PowerManager pm = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "Wake Up");
        wl.acquire(6000);
        final Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vibrator.vibrate(3000);
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(Constants.NOTIFICATION_CHANNEL_ID, "UPMC Dash", NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.setDescription("UPMC Dash notification channel");
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.enableVibration(true);
            mNotificationManager.createNotificationChannel(notificationChannel);
            PendingIntent dashPendingIntent = PendingIntent.getForegroundService(this, 0, dashIntent, 0);
            setupNotifBuilder.setAutoCancel(false)
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(R.drawable.common_google_signin_btn_icon_dark_normal)
                    .setContentTitle("UPMC Dash Monitor")
                    .setContentText(Constants.NOTIF_INACTIVITY)
                    .setGroup("Prompt")
                    .setOngoing(true)
                    .setContentIntent(dashPendingIntent);
        } else {

            PendingIntent dashPendingIntent = PendingIntent.getService(this, 0, dashIntent, 0);

            monitorNotifCompatBuilder = new NotificationCompat.Builder(getApplicationContext(), Constants.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.common_google_signin_btn_icon_dark_normal)
                    .setContentTitle("UPMC Dash Monitor")
                    .setContentText(Constants.NOTIF_INACTIVITY)
                    .setOngoing(true)
                    .setContentIntent(dashPendingIntent)
                    .setGroup("Prompt")
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                    .setPriority(NotificationCompat.PRIORITY_MAX);
            mNotificationManager.notify(3, monitorNotifCompatBuilder.build());
        }
    }

    public void initializeWear() {
        StringBuilder initBuilder = new StringBuilder();
        initBuilder.append(Constants.INIT_TS);
        initBuilder.append(" ");
        initBuilder.append(Aware.getSetting(getApplicationContext(), Settings.PLUGIN_UPMC_CANCER_MORNING_HOUR));
        initBuilder.append(" ");
        initBuilder.append(Aware.getSetting(getApplicationContext(), Settings.PLUGIN_UPMC_CANCER_MORNING_MINUTE));
        initBuilder.append(" ");
        initBuilder.append(Aware.getSetting(getApplicationContext(), Settings.PLUGIN_UPMC_CANCER_NIGHT_HOUR));
        initBuilder.append(" ");
        initBuilder.append(Aware.getSetting(getApplicationContext(), Settings.PLUGIN_UPMC_CANCER_NIGHT_MINUTE));
        initBuilder.append(" ");
        initBuilder.append(Aware.getSetting(getApplicationContext(), Settings.PLUGIN_UPMC_CANCER_SYMPTOM_SEVERITY));
        Log.d(Constants.TAG, "MessageService:initializeWear: " + initBuilder.toString());
//        sendMessageToWear(initBuilder.toString());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int i = super.onStartCommand(intent, flags, startId);
        Log.d(Constants.TAG, "MessageService: onStartCommand");
        String intentAction = null;
        if (intent != null)
            intentAction = intent.getAction();
        if (intentAction == null)
            return i;
        switch (intentAction) {
            case Constants.ACTION_FIRST_RUN:
                Log.d(Constants.TAG, "MessageService: onStartCommand first run");
                mAlarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
                LocalBroadcastManager.getInstance(this).registerReceiver(mBluetootLocalReceiver, new IntentFilter(Constants.BLUETOOTH_COMM));
                LocalBroadcastManager.getInstance(this).registerReceiver(mNotifLocalReceiver, new IntentFilter(Constants.NOTIF_COMM));
                LocalBroadcastManager.getInstance(this).registerReceiver(mSnoozeAlarmLocalReceiver, new IntentFilter(Constants.SNOOZE_ALARM_INTENT_FILTER));
                showSurveyNotif();
                showFitbitNotif();
                break;
            case Constants.ACTION_CHECK_PROMPT:
                Log.d(Constants.TAG, "MessageService: onStartCommand check new prompt");
                new CheckPrompt().execute();
                break;
            case Constants.ACTION_CHECK_CONN:
                Log.d(Constants.TAG, "MessageService: onStartCommand check watch connection");
//                startActivity(new Intent(this, SetupLoadingActvity.class));
                new CheckConn().execute(TABLE_CONN, "status");
                break;
//            case Constants.ACTION_SETUP_WEAR:
//                Log.d(Constants.TAG, "MessageService: onStartCommand setup wear");
//                startActivity(new Intent(this, SetupLoadingActvity.class));
//                //initiateSetup();
//                //notifyUserWithInactivity();
//                break;
            case Constants.ACTION_APPRAISAL:
                Log.d(Constants.TAG, "MessageService: onStartCommand appraisal");
                dismissAppraisal();
                break;
            case Constants.ACTION_INACTIVITY:
                Log.d(Constants.TAG, "MessageService: onStartCommand : inactivity");
                startActivity(new Intent(this, NotificationResponseActivity.class));
                break;
            case Constants.ACTION_VICINITY:
                Log.d(Constants.TAG, "MessageService: onStartCommand: vicinity");
                //checkSetup();
                break;
            case Constants.SETTINGS_CHANGED:
                Log.d(Constants.TAG, "MessageService:onStartCommand: settings changed");
                if (isWearInitializable()) {
                    initializeWear();
                } else {
                    Log.d(Constants.TAG, "MessageService: not enough information to start logging");
                }
                break;
            default:
                return i;
        }
        return i;
    }

//    public void initiateSetup() {
//        setUpNodeIdentities();
//        new Handler().postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                if(isNodeSaved()) {
//                    if(isWearConnected()) {
//                        Log.d(Constants.TAG, "onStartCommand:Setup Complete");
//                        notifySetup(Constants.CONNECTED_WEAR);
//                        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(Constants.LOADING_ACTIVITY_INTENT_FILTER).putExtra(Constants.MESSAGE_EXTRA_KEY, Constants.CONNECTED_WEAR));
//                    }
//                    else {
//                        Log.d(Constants.TAG, "onStartCommand: setupFailed");
//                        notifySetup(Constants.FAILED_WEAR);
//                        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(Constants.LOADING_ACTIVITY_INTENT_FILTER).putExtra(Constants.MESSAGE_EXTRA_KEY, Constants.FAILED_WEAR));
//                    }
//                }
//                else {
//                    Log.d(Constants.TAG, "onStartCommand:setupFailed");
//                    LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(Constants.LOADING_ACTIVITY_INTENT_FILTER).putExtra(Constants.MESSAGE_EXTRA_KEY, Constants.FAILED_WEAR));
//                    notifySetup(Constants.FAILED_WEAR);
//                }
//            }
//        }, 5000);
//    }
//
//    public void checkSetup() {
//        setUpNodeIdentities();
//        new Handler().postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                if(isNodeSaved()) {
//                    if(isWearConnected()) {
//                        Log.d(Constants.TAG, "onStartCommand:Setup Complete");
//                        notifySetup(Constants.CONNECTED_WEAR);
//                        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(Constants.VICINITY_CHECK_INTENT_FILTER).putExtra(Constants.VICINITY_RESULT_KEY, Constants.WEAR_IN_RANGE));
//                    }
//                    else {
//                        Log.d(Constants.TAG, "onStartCommand: setupFailed");
//                        notifySetup(Constants.FAILED_WEAR);
//                        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(Constants.VICINITY_CHECK_INTENT_FILTER).putExtra(Constants.VICINITY_RESULT_KEY, Constants.WEAR_NOT_IN_RANGE));
//                    }
//                }
//                else {
//                    Log.d(Constants.TAG, "onStartCommand:setupFailed");
//                    LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(Constants.VICINITY_CHECK_INTENT_FILTER).putExtra(Constants.VICINITY_RESULT_KEY, Constants.WEAR_NOT_IN_RANGE));
//                    notifySetup(Constants.FAILED_WEAR);
//                }
//            }
//        }, 5000);
//    }

    private void showSurveyNotif() {
        final Intent dashIntent = new Intent(this, UPMC.class);
        dashIntent.setAction(Constants.ACTION_SURVEY);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(Constants.NOTIFICATION_CHANNEL_ID, "UPMC Dash", NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.setDescription("UPMC Dash notification channel");
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.enableVibration(true);
            notificationManager.createNotificationChannel(notificationChannel);
            Notification.Builder notificationBuilder = new Notification.Builder(this, Constants.NOTIFICATION_CHANNEL_ID);

            PendingIntent dashPendingIntent = PendingIntent.getActivity(this, 0, dashIntent, 0);
            notificationBuilder.setAutoCancel(false)
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(R.drawable.common_google_signin_btn_icon_dark_normal)
                    .setContentTitle("UPMC Dash Survey")
                    .setGroup("Survey")
                    .setContentText(Constants.COMPLETE_SURVEY)
                    .setContentIntent(dashPendingIntent);
            startForeground(1, notificationBuilder.build());

        } else {
            PendingIntent dashPendingIntent = PendingIntent.getActivity(this, 0, dashIntent, 0);
            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID);
            notificationBuilder.setAutoCancel(false)
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(R.drawable.common_google_signin_btn_icon_dark_normal)
                    .setContentTitle("UPMC Dash Survey")
                    .setContentText(Constants.COMPLETE_SURVEY)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setGroup("Survey")
                    .setContentInfo("info")
                    .setContentIntent(dashPendingIntent);

            startForeground(1, notificationBuilder.build());
        }

    }

    private void showFitbitNotif() {
        final Intent dashIntent = new Intent(this, MessageService.class);
        dashIntent.setAction(Constants.ACTION_CHECK_CONN);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupNotifBuilder = new Notification.Builder(this, Constants.NOTIFICATION_CHANNEL_ID);
            PendingIntent dashPendingIntent = PendingIntent.getForegroundService(this, 0, dashIntent, 0);
            setupNotifBuilder.setAutoCancel(false)
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(R.drawable.common_google_signin_btn_icon_dark_normal)
                    .setContentTitle("UPMC Dash Fitbit")
                    .setContentText(Constants.CONN_STATUS)
                    .setGroup("Setup")
                    .setOngoing(true)
                    .setContentIntent(dashPendingIntent);

            notificationManager.notify(2, setupNotifBuilder.build());
        } else {
            PendingIntent dashPendingIntent = PendingIntent.getService(this, 0, dashIntent, 0);
            setupNotifCompatBuilder = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID);
            setupNotifCompatBuilder.setAutoCancel(false)
                    .setOngoing(true)
                    .setGroup("Setup")
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(R.drawable.common_google_signin_btn_icon_dark_normal)
                    .setContentTitle("UPMC Dash Fitbit")
                    .setContentText(Constants.CONN_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentInfo("info")
                    .setContentIntent(dashPendingIntent);
            notificationManager.notify(2, setupNotifCompatBuilder.build());
        }
    }

    public void notifySetup(String contentText) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupNotifBuilder.setContentText(contentText);
            notificationManager.notify(2, setupNotifBuilder.build());
        } else {
            setupNotifCompatBuilder.setContentText(contentText);
            notificationManager.notify(2, setupNotifCompatBuilder.build());
        }
        PowerManager pm = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "Wake Up");
        wl.acquire(6000);
        final Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vibrator.vibrate(500);
    }


    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        Log.d(Constants.TAG, "MessageService:onDataChanged:received");
        for (DataEvent event : dataEventBuffer) {
            if (event.getType() == DataEvent.TYPE_CHANGED &&
                    event.getDataItem().getUri().getPath().equals("/upmc-dash")) {
                DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                final Asset logfileAsset = dataMapItem.getDataMap().getAsset("logfile");
                Log.d(Constants.TAG, "MessageService:onDataChanged: received logfileasset");
                SyncFilesTask syncFilesTask = new SyncFilesTask(MessageService.this);
                syncFilesTask.execute(new SyncFilesParams(mGoogleApiClient, logfileAsset, getApplicationContext()));
            }
        }
        super.onDataChanged(dataEventBuffer);
    }

    @Override
    public void onConnectedNodes(List<Node> list) {
        Log.d(Constants.TAG, "MessageService: onConnectedNodes");
        super.onConnectedNodes(list);
    }

    @Override
    public void onPeerConnected(Node node) {
        Log.d(Constants.TAG, "MessageService: onPeerConnected");
        super.onPeerConnected(node);
    }

    @Override
    public void onPeerDisconnected(Node node) {
        Log.d(Constants.TAG, "MessageService: onPeerConnected");
        super.onPeerDisconnected(node);
    }


    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        Log.d(Constants.TAG, "MessageService: onCapability");
        super.onCapabilityChanged(capabilityInfo);
        if (capabilityInfo.getNodes().size() > 0) {
            Log.d(Constants.TAG, "MessageService: Device Connected");
        } else {
            Log.d(Constants.TAG, "MessageService: No Devices, onCapabilityChanged");
        }
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(Constants.TAG, "MessageService: onConnected");
        ;
        Wearable.CapabilityApi.addCapabilityListener(mGoogleApiClient,
                this,
                Constants.CAPABILITY_WEAR_APP);
        Uri uri = new Uri.Builder().scheme("wear").path("/upmc-dash").build();
        Wearable.MessageApi.addListener(mGoogleApiClient, this, uri, MessageApi.FILTER_PREFIX);
        Wearable.DataApi.addListener(mGoogleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(Constants.TAG, "MessageService:onConnectionSuspended");
    }


    public boolean isWearConnected() {
        return wearConnected;
    }

    public void setWearConnected(boolean wearConnected) {
        this.wearConnected = wearConnected;
    }

//    private void sendMessageToWear(final String message) {
//        Log.d(Constants.TAG, "MessageService:sendMessageToWear " + message);
//        Uri.Builder uriBuilder = new Uri.Builder();
//        uriBuilder.scheme("wear").path("/upmc-dash");
//
//        PendingResult<MessageApi.SendMessageResult> pendingResult =
//                Wearable.MessageApi.sendMessage(
//                        mGoogleApiClient,
//                        getNODE_ID(),
//                        uriBuilder.toString(),
//                        message.getBytes());
//
//        pendingResult.setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
//            @Override
//            public void onResult(@NonNull MessageApi.SendMessageResult sendMessageResult) {
//                if (!sendMessageResult.getStatus().isSuccess()) {
//                    Log.d(Constants.TAG, "MessageService:sendMessageToWear:message failed" + message);
//                    setWearConnected(false);
//                    notifySetup(Constants.FAILED_WEAR);
//                } else {
//                    Log.d(Constants.TAG, "MessageService:sendMessageToWear:message sent" + message);
//                    if (!isWearConnected()) {
//                        notifySetup(Constants.CONNECTED_WEAR);
//                    }
//                    setWearConnected(true);
//                }
//            }
//        });
//    }

//    private void isWearServiceRunning(String NODE_ID) {
//        setWearConnected(false);
//        String message = Constants.IS_WEAR_RUNNING;
//        Uri.Builder uriPath = new Uri.Builder();
//        uriPath.scheme("wear").path("/upmc-dash").build();
//        PendingResult<MessageApi.SendMessageResult> pendingResult = Wearable.MessageApi.sendMessage(
//                mGoogleApiClient,
//                NODE_ID,
//                uriPath.toString(),
//                message.getBytes());
//
//        pendingResult.setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
//            @Override
//            public void onResult(@NonNull MessageApi.SendMessageResult sendMessageResult) {
//                if (!sendMessageResult.getStatus().isSuccess()) {
//                    Log.d(Constants.TAG, "MessageService:detectWearStatus:wearStatus: Failed to send message");
//                } else {
//                    Log.d(Constants.TAG, "MessageService:detectWearStatus:wearStatus: Message successfully sent");
//                }
//            }
//        });
//    }

    @Override
    public void onSyncSuccess() {
        Log.d(Constants.TAG, "MessageService:onSyncSuccess");
    }

    @Override
    public void onSyncFailed() {
        Log.d(Constants.TAG, "MessageService:onSyncFailed");

    }

    private class CheckPrompt extends AsyncTask<String, Void, Void> {


        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
        }

        @Override
        protected Void doInBackground(String... strings) {
            Connection conn = null;
            Statement stmt = null;
            try {
                //Register JDBC driver
                Class.forName("com.mysql.jdbc.Driver");
                //Open a connection
                Log.d("yiyi", "Connecting to database to check new prompt...");
                conn = DriverManager.getConnection(DB_NAME, USER, PASS);
                stmt = conn.createStatement();
                StringBuilder sql1 = new StringBuilder();
                sql1.append("SELECT id FROM ");
                sql1.append(TABLE_NOTIF);
                sql1.append(" ORDER BY id DESC");
                ResultSet rs = stmt.executeQuery(sql1.toString());
                if (rs.next()) {
                    int id = rs.getInt("id");
                    if (id > notifCount) {
                        Log.d("yiyi", "Notification ID: " + id + ". Show new notification on phone!!!");
                        notifCount = id;
                        notifyUserWithInactivity();
                    }
                }
                StringBuilder sql2 = new StringBuilder();
                sql2.append("SELECT id FROM ");
                sql2.append(TABLE_MINI);
                sql2.append(" ORDER BY id DESC");
                rs = stmt.executeQuery(sql2.toString());
                if (rs.next()) {
                    int id = rs.getInt("id");
                    if (id > miniCount) {
                        Log.d("yiyi", "Mini-message ID: " + id + ". Show new mini-message on phone!!!");
                        miniCount = id;
                        notifyUserWithAppraisal();
                    }
                }
                //Clean-up environment
                rs.close();
                stmt.close();
                conn.close();
            } catch (SQLException se) {
                //Handle errors for JDBC
                se.printStackTrace();
            } catch (Exception e) {
                //Handle errors for Class.forName
                e.printStackTrace();
            } finally {
                try {
                    if (stmt != null)
                        stmt.close();
                } catch (SQLException se2) {
                }
                try {
                    if (conn != null)
                        conn.close();
                } catch (SQLException se) {
                    se.printStackTrace();
                }
            }
            return null;
        }
    }

    private class CheckConn extends AsyncTask<String, Void, Void> {


        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
        }

        @Override
        protected Void doInBackground(String... strings) {
            Connection conn = null;
            Statement stmt = null;
            try {
                //Register JDBC driver
                Class.forName("com.mysql.jdbc.Driver");
                //Open a connection
                conn = DriverManager.getConnection(DB_NAME, USER, PASS);
                stmt = conn.createStatement();
                Log.d("yiyi", "Connecting to database to check connection...");
                StringBuilder sql = new StringBuilder();
                sql.append("SELECT id, status FROM ");
                sql.append(strings[0]);
                sql.append(" ORDER BY id DESC");
                ResultSet rs = stmt.executeQuery(sql.toString());
                if (rs.next()) {
                    int status = rs.getInt(strings[1]);
                    Log.d("yiyi", "Current connection status is: " + status);
                    if (status == 1) {
                        notifySetup(CONNECTED_WEAR);
//                        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(Constants.LOADING_ACTIVITY_INTENT_FILTER).putExtra(Constants.MESSAGE_EXTRA_KEY, Constants.CONNECTED_WEAR));

                    } else {
                        notifySetup(FAILED_WEAR);
//                        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(Constants.LOADING_ACTIVITY_INTENT_FILTER).putExtra(Constants.MESSAGE_EXTRA_KEY, Constants.FAILED_WEAR));

                    }
                }
                //Clean-up environment
                rs.close();
                stmt.close();
                conn.close();
            } catch (SQLException se) {
                //Handle errors for JDBC
                se.printStackTrace();
            } catch (Exception e) {
                //Handle errors for Class.forName
                e.printStackTrace();
            } finally {
                try {
                    if (stmt != null)
                        stmt.close();
                } catch (SQLException se2) {
                }
                try {
                    if (conn != null)
                        conn.close();
                } catch (SQLException se) {
                    se.printStackTrace();
                }
            }
            return null;
        }
    }

}